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

	@BeforeEach
	void rememberGlobals() {
		all = Global.backgroundTaskPauseAll;
		collect = Global.backgroundTaskPauseCollect;
		download = Global.backgroundTaskPauseDownload;
		hls = Global.backgroundTaskPauseHls;
	}

	@AfterEach
	void restoreGlobals() {
		Global.backgroundTaskPauseAll = all;
		Global.backgroundTaskPauseCollect = collect;
		Global.backgroundTaskPauseDownload = download;
		Global.backgroundTaskPauseHls = hls;
	}

	@Test
	void pauseAllDoesNotOverwriteIndependentCategoryControls() {
		RuntimeControlTransaction transaction = mock(RuntimeControlTransaction.class);
		Map<String, RuntimeControlValue> initial = values(false, false, true, false);
		Map<String, RuntimeControlValue> pausedAll = values(true, false, true, false);
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

	private Map<String, RuntimeControlValue> values(boolean pauseAll, boolean pauseCollect,
			boolean pauseDownload, boolean pauseHls) {
		Map<String, RuntimeControlValue> result = new LinkedHashMap<>();
		result.put(RuntimeControlTransaction.PAUSE_ALL, value(pauseAll));
		result.put(RuntimeControlTransaction.PAUSE_COLLECT, value(pauseCollect));
		result.put(RuntimeControlTransaction.PAUSE_DOWNLOAD, value(pauseDownload));
		result.put(RuntimeControlTransaction.PAUSE_HLS, value(pauseHls));
		return result;
	}

	private RuntimeControlValue value(boolean enabled) {
		return new RuntimeControlValue(enabled, "2026-07-25T09:00:00Z", "admin", "test");
	}
}
