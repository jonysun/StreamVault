package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.flower.spirit.dao.CollectdDataDao;
import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.entity.CollectDataEntity;
import com.flower.spirit.service.transaction.CollectQueueTransaction;

class CollectEnqueueServiceTest {

	@Test
	void unsupportedPlatformDoesNotCreateRunOrQueueJob() {
		CollectdDataDao taskDao = mock(CollectdDataDao.class);
		CollectQueueTransaction transaction = mock(CollectQueueTransaction.class);
		RuntimeControlService runtimeControl = mock(RuntimeControlService.class);
		CollectDataEntity task = task("bilibili");
		when(taskDao.findById(7)).thenReturn(Optional.of(task));

		CollectEnqueueResult result = service(taskDao, transaction, runtimeControl).enqueueManual(7);

		assertThat(result.skippedUnsupported()).isTrue();
		assertThat(result.runId()).isNull();
		assertThat(result.jobId()).isNull();
		assertThat(result.reason()).contains("仅支持抖音");
		verify(transaction, never()).enqueue(anyInt(), any(), any(), any(), anyInt(), anyInt());
		verify(transaction, never()).recordSkipped(anyInt(), any(), any(), anyString(), any(Instant.class));
		verify(runtimeControl, never()).mayRun(any());
	}

	@Test
	void douyinAliasContinuesToPersistentQueue() {
		CollectdDataDao taskDao = mock(CollectdDataDao.class);
		CollectQueueTransaction transaction = mock(CollectQueueTransaction.class);
		RuntimeControlService runtimeControl = mock(RuntimeControlService.class);
		CollectDataEntity task = task("douyin");
		when(taskDao.findById(7)).thenReturn(Optional.of(task));
		when(runtimeControl.mayRun(TaskCategory.COLLECT_FETCH)).thenReturn(PauseDecision.permit());
		when(transaction.enqueue(anyInt(), any(), any(), any(), anyInt(), anyInt()))
				.thenReturn(new CollectEnqueueResult(10L, 20L, CollectRunState.QUEUED, true, false));

		CollectEnqueueResult result = service(taskDao, transaction, runtimeControl)
				.enqueueScheduled(7, Instant.parse("2026-07-27T00:00:00Z"));

		assertThat(result.skippedUnsupported()).isFalse();
		assertThat(result.runId()).isEqualTo(10L);
		verify(transaction).enqueue(anyInt(), any(), any(), any(), anyInt(), anyInt());
	}

	private CollectEnqueueService service(CollectdDataDao taskDao, CollectQueueTransaction transaction,
			RuntimeControlService runtimeControl) {
		DatabaseWriteExecutor writes = mock(DatabaseWriteExecutor.class);
		when(writes.execute(anyString(), org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
				.thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
		return new CollectEnqueueService(taskDao, transaction, writes, runtimeControl, 3);
	}

	private CollectDataEntity task(String platform) {
		CollectDataEntity task = new CollectDataEntity();
		task.setId(7);
		task.setPlatform(platform);
		task.setTaskenabled("Y");
		task.setMaxcur(20);
		return task;
	}
}
