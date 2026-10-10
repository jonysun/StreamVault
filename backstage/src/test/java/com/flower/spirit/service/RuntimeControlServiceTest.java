package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.flower.spirit.config.Global;
import com.flower.spirit.service.RuntimeControlSnapshot.RuntimeControlValue;
import com.flower.spirit.service.transaction.RuntimeControlTransaction;

class RuntimeControlServiceTest {

	private boolean all;
	private boolean collect;
	private boolean download;
	private boolean hls;
	private String douyinProvider;

	@BeforeEach
	void rememberGlobals() {
		all = Global.backgroundTaskPauseAll;
		collect = Global.backgroundTaskPauseCollect;
		download = Global.backgroundTaskPauseDownload;
		hls = Global.backgroundTaskPauseHls;
		douyinProvider = Global.douyinProvider;
	}

	@AfterEach
	void restoreGlobals() {
		Global.backgroundTaskPauseAll = all;
		Global.backgroundTaskPauseCollect = collect;
		Global.backgroundTaskPauseDownload = download;
		Global.backgroundTaskPauseHls = hls;
		Global.douyinProvider = douyinProvider;
	}

	@Test
	void pauseAllDoesNotOverwriteIndependentCategoryControls() {
		RuntimeControlTransaction transaction = mock(RuntimeControlTransaction.class);
		Map<String, RuntimeControlValue> initial = values(false, false, true, false, false);
		Map<String, RuntimeControlValue> pausedAll = values(true, false, true, false, false);
		when(transaction.initializeAndLoad(any())).thenReturn(initial);
		when(transaction.set(eq(RuntimeControlTransaction.PAUSE_ALL), eq(true), eq("admin"), eq("维护"), any()))
				.thenReturn(pausedAll);
		SqliteWriteRetrier retrier = new SqliteWriteRetrier(1, 0, 0);
		RuntimeControlService service = new RuntimeControlService(transaction, retrier);

		service.initialize();
		assertThat(service.mayRun(TaskCategory.MEDIA_DOWNLOAD).allowed()).isFalse();
		assertThat(service.mayRun(TaskCategory.COLLECT_FETCH).allowed()).isTrue();

		RuntimeControlSnapshot snapshot = service.set("all", true, "admin", "维护");
		assertThat(snapshot.allPaused()).isTrue();
		assertThat(snapshot.downloadPaused()).isTrue();
		assertThat(service.mayRun(TaskCategory.COLLECT_FETCH).controlKey()).isEqualTo("pause.all");
	}

	@Test
	void blocksWorkersUntilPersistentControlsAreLoaded() {
		RuntimeControlTransaction transaction = mock(RuntimeControlTransaction.class);
		RuntimeControlService service = new RuntimeControlService(transaction, new SqliteWriteRetrier(1, 0, 0));

		PauseDecision decision = service.mayRun(TaskCategory.COLLECT_FETCH);

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.controlKey()).isEqualTo("runtime-control.starting");
		assertThat(service.isInitialized()).isFalse();
	}

	@Test
	void automaticF2PoolPauseBlocksOnlyCollectionAndDoesNotReplaceManualPause() {
		Global.douyinProvider = "F2";
		RuntimeControlTransaction transaction = mock(RuntimeControlTransaction.class);
		Map<String, RuntimeControlValue> initial = values(false, false, false, false, false);
		Map<String, RuntimeControlValue> automaticPause = values(false, false, false, false, true);
		Map<String, RuntimeControlValue> automaticAndManualPause = values(false, true, false, false, true);
		Map<String, RuntimeControlValue> manualOnly = values(false, true, false, false, false);
		when(transaction.initializeAndLoad(any())).thenReturn(initial);
		when(transaction.setAutomaticF2PoolPause(eq(true), eq("池耗尽"), any())).thenReturn(automaticPause);
		when(transaction.set(eq(RuntimeControlTransaction.PAUSE_COLLECT), eq(true), eq("admin"), eq("手动维护"), any()))
				.thenReturn(automaticAndManualPause);
		when(transaction.setAutomaticF2PoolPause(eq(false), eq("池恢复"), any())).thenReturn(manualOnly);
		RuntimeControlService service = new RuntimeControlService(transaction, new SqliteWriteRetrier(1, 0, 0));
		service.initialize();

		service.setAutomaticF2PoolPause(true, "池耗尽");
		assertThat(service.mayRun(TaskCategory.COLLECT_FETCH).allowed()).isFalse();
		assertThat(service.mayRun(TaskCategory.MEDIA_DOWNLOAD).allowed()).isTrue();

		service.set("collect", true, "admin", "手动维护");
		service.setAutomaticF2PoolPause(false, "池恢复");
		assertThat(service.snapshot().collectPaused()).isTrue();
		assertThat(service.snapshot().effectiveCollectPaused()).isTrue();
		assertThat(service.mayRun(TaskCategory.COLLECT_FETCH).controlKey())
				.isEqualTo(RuntimeControlTransaction.PAUSE_COLLECT);
	}

	@Test
	void automaticF2PoolPauseDoesNotBlockAutoProvider() {
		Global.douyinProvider = "AUTO";
		RuntimeControlTransaction transaction = mock(RuntimeControlTransaction.class);
		Map<String, RuntimeControlValue> initial = values(false, false, false, false, false);
		Map<String, RuntimeControlValue> automaticPause = values(false, false, false, false, true);
		when(transaction.initializeAndLoad(any())).thenReturn(initial);
		when(transaction.setAutomaticF2PoolPause(eq(true), eq("池耗尽"), any())).thenReturn(automaticPause);
		RuntimeControlService service = new RuntimeControlService(transaction, new SqliteWriteRetrier(1, 0, 0));
		service.initialize();

		service.setAutomaticF2PoolPause(true, "池耗尽");
		assertThat(service.mayRun(TaskCategory.COLLECT_FETCH).allowed()).isTrue();
		assertThat(service.snapshot().effectiveCollectPaused()).isFalse();
	}

	private Map<String, RuntimeControlValue> values(boolean pauseAll, boolean pauseCollect,
			boolean pauseDownload, boolean pauseHls, boolean pauseF2Pool) {
		Map<String, RuntimeControlValue> result = new LinkedHashMap<>();
		result.put(RuntimeControlTransaction.PAUSE_ALL, value(pauseAll));
		result.put(RuntimeControlTransaction.PAUSE_COLLECT, value(pauseCollect));
		result.put(RuntimeControlTransaction.PAUSE_DOWNLOAD, value(pauseDownload));
		result.put(RuntimeControlTransaction.PAUSE_HLS, value(pauseHls));
		result.put(RuntimeControlTransaction.PAUSE_COLLECT_F2_POOL, value(pauseF2Pool));
		return result;
	}

	private RuntimeControlValue value(boolean enabled) {
		return new RuntimeControlValue(enabled, "2026-07-25T09:00:00Z", "admin", "test");
	}
}
