package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.service.transaction.CollectDownloadTransaction;

class CollectDownloadWorkerTest {

	@Test
	void failedItemDoesNotStopTheNextClaim() {
		CollectDownloadTransaction transaction = mock(CollectDownloadTransaction.class);
		CollectDownloadService downloadService = mock(CollectDownloadService.class);
		DatabaseWriteExecutor writes = passThroughWrites();
		RuntimeControlService runtime = mock(RuntimeControlService.class);
		CollectDownloadClaim first = claim(1, "work-1");
		CollectDownloadClaim second = claim(2, "work-2");
		when(runtime.mayRun(TaskCategory.MEDIA_DOWNLOAD)).thenReturn(PauseDecision.permit());
		when(transaction.claimNext(anyString(), any())).thenReturn(first, second, null);
		doThrow(new IllegalStateException("terminal state write failed")).when(downloadService).process(first);
		CollectDownloadWorker worker = new CollectDownloadWorker(transaction, downloadService, writes, runtime,
				10, 30, 1);

		try {
			worker.processAvailable();
			InOrder order = inOrder(downloadService);
			order.verify(downloadService).process(first);
			order.verify(downloadService).process(second);
			verify(transaction).retryOrFail(org.mockito.ArgumentMatchers.eq(first),
					org.mockito.ArgumentMatchers.eq("WORKER_PROCESS_ESCAPED"), anyString(), anyString(), any());
		} finally {
			worker.shutdown();
		}
	}

	@Test
	void pausedDownloadsNeitherRecoverNorClaim() {
		CollectDownloadTransaction transaction = mock(CollectDownloadTransaction.class);
		CollectDownloadService downloadService = mock(CollectDownloadService.class);
		RuntimeControlService runtime = mock(RuntimeControlService.class);
		when(runtime.mayRun(TaskCategory.MEDIA_DOWNLOAD))
				.thenReturn(PauseDecision.paused("pause.download", "maintenance"));
		CollectDownloadWorker worker = new CollectDownloadWorker(transaction, downloadService, passThroughWrites(),
				runtime, 10, 30, 1);

		try {
			worker.processAvailable();
			verify(transaction, never()).recoverStale(any(), any());
			verify(transaction, never()).claimNext(anyString(), any());
		} finally {
			worker.shutdown();
		}
	}

	@Test
	void staleRecoveryRunsBeforeEveryBatch() {
		CollectDownloadTransaction transaction = mock(CollectDownloadTransaction.class);
		CollectDownloadService downloadService = mock(CollectDownloadService.class);
		RuntimeControlService runtime = mock(RuntimeControlService.class);
		when(runtime.mayRun(TaskCategory.MEDIA_DOWNLOAD)).thenReturn(PauseDecision.permit());
		when(transaction.claimNext(anyString(), any())).thenReturn(null);
		CollectDownloadWorker worker = new CollectDownloadWorker(transaction, downloadService, passThroughWrites(),
				runtime, 10, 30, 1);

		try {
			worker.processAvailable();
			worker.processAvailable();
			verify(transaction, times(2)).recoverStale(any(), any());
		} finally {
			worker.shutdown();
		}
	}

	@Test
	void pauseAfterClaimReturnsTheItemWithoutDownloading() {
		CollectDownloadTransaction transaction = mock(CollectDownloadTransaction.class);
		CollectDownloadService downloadService = mock(CollectDownloadService.class);
		RuntimeControlService runtime = mock(RuntimeControlService.class);
		CollectDownloadClaim claim = claim(1, "work-1");
		when(runtime.mayRun(TaskCategory.MEDIA_DOWNLOAD)).thenReturn(PauseDecision.permit(),
				PauseDecision.permit(), PauseDecision.paused("pause.download", "maintenance"));
		when(transaction.claimNext(anyString(), any())).thenReturn(claim);
		CollectDownloadWorker worker = new CollectDownloadWorker(transaction, downloadService, passThroughWrites(),
				runtime, 10, 30, 1);

		try {
			worker.processAvailable();
			verify(transaction).deferPaused(org.mockito.ArgumentMatchers.eq(claim),
					org.mockito.ArgumentMatchers.contains("maintenance"), any());
			verify(downloadService, never()).process(any());
		} finally {
			worker.shutdown();
		}
	}

	@Test
	void rejectedWakeDuringShutdownResetsRunningGuard() {
		CollectDownloadWorker worker = new CollectDownloadWorker(mock(CollectDownloadTransaction.class),
				mock(CollectDownloadService.class), passThroughWrites(), mock(RuntimeControlService.class),
				10, 30, 1);
		worker.shutdown();

		worker.wakeUp();

		AtomicBoolean running = (AtomicBoolean) ReflectionTestUtils.getField(worker, "running");
		assertThat(running).isNotNull();
		assertThat(running.get()).isFalse();
	}

	private DatabaseWriteExecutor passThroughWrites() {
		DatabaseWriteExecutor writes = mock(DatabaseWriteExecutor.class);
		when(writes.execute(anyString(), org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
				.thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
		return writes;
	}

	private CollectDownloadClaim claim(long id, String workId) {
		return new CollectDownloadClaim(id, 10, 7, "author", "douyin", workId, "video", "NEW",
				(int) id, 1, 4, "worker:lease-" + id);
	}
}
