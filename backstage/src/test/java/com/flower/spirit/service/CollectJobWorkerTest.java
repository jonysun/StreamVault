package com.flower.spirit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.service.transaction.CollectQueueTransaction;

class CollectJobWorkerTest {

	@Test
	void workerPassesTriggerTypeAndCompletesImmediatelyAfterFetchPlanning() {
		CollectQueueTransaction transaction = mock(CollectQueueTransaction.class);
		CollectRunService runService = mock(CollectRunService.class);
		CollectDataService dataService = mock(CollectDataService.class);
		PlatformCookieService cookieService = mock(PlatformCookieService.class);
		DatabaseWriteExecutor writes = mock(DatabaseWriteExecutor.class);
		CollectJobClaim claim = new CollectJobClaim(11L, 90L, 7, CollectTriggerType.AUDIT, 1, 3);
		when(writes.execute(anyString(), org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
				.thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
		when(transaction.claimNext(anyString(), any())).thenReturn(claim);
		when(dataService.isCollectTaskEnabled(7)).thenReturn(true);
		CollectJobWorker worker = new CollectJobWorker(transaction, runService, dataService, cookieService, writes, 1);

		try {
			worker.processOne();
			InOrder order = inOrder(runService, dataService);
			order.verify(runService).start(90L);
			order.verify(dataService).executeQueuedCollectTask(7, 90L, CollectTriggerType.AUDIT);
			order.verify(runService).complete(90L, 11L);
		} finally {
			worker.shutdown();
		}
	}

	@Test
	void rejectedWakeDuringShutdownResetsRunningGuard() {
		CollectJobWorker worker = new CollectJobWorker(mock(CollectQueueTransaction.class),
				mock(CollectRunService.class), mock(CollectDataService.class), mock(PlatformCookieService.class),
				mock(DatabaseWriteExecutor.class), 1);
		worker.shutdown();

		worker.wakeUp();

		AtomicBoolean running = (AtomicBoolean) ReflectionTestUtils.getField(worker, "running");
		assertThat(running).isNotNull();
		assertThat(running.get()).isFalse();
	}

	@Test
	void activeCooldownPreventsQueueClaim() {
		CollectQueueTransaction transaction = mock(CollectQueueTransaction.class);
		PlatformCookieService cookieService = mock(PlatformCookieService.class);
		when(cookieService.isDouyinGlobalCooldownActive()).thenReturn(true);
		CollectJobWorker worker = new CollectJobWorker(transaction, mock(CollectRunService.class),
				mock(CollectDataService.class), cookieService, mock(DatabaseWriteExecutor.class), 1);

		try {
			worker.processOne();
			verify(transaction, never()).claimNext(anyString(), any());
		} finally {
			worker.shutdown();
		}
	}

	@Test
	void cooldownStartedAfterClaimDefersWithoutStartingRun() {
		CollectQueueTransaction transaction = mock(CollectQueueTransaction.class);
		CollectRunService runService = mock(CollectRunService.class);
		CollectDataService dataService = mock(CollectDataService.class);
		PlatformCookieService cookieService = mock(PlatformCookieService.class);
		DatabaseWriteExecutor writes = passthroughWrites();
		CollectJobClaim claim = new CollectJobClaim(11L, 90L, 7, CollectTriggerType.SCHEDULED, 3, 3);
		Instant retryAt = Instant.parse("2026-07-29T08:10:05Z");
		when(transaction.claimNext(anyString(), any())).thenReturn(claim);
		when(dataService.isCollectTaskEnabled(7)).thenReturn(true);
		when(cookieService.isDouyinGlobalCooldownActive()).thenReturn(false, true);
		when(cookieService.douyinGlobalCooldownRetryAt(any())).thenReturn(retryAt);
		CollectJobWorker worker = new CollectJobWorker(transaction, runService, dataService, cookieService, writes, 1);

		try {
			worker.processOne();
			verify(runService).deferForCooldown(claim, retryAt,
					"Douyin global cooldown started after queue claim");
			verify(runService, never()).start(90L);
		} finally {
			worker.shutdown();
		}
	}

	@Test
	void cookieCooldownRaisedAfterRunStartDefersWithoutFailure() {
		CollectQueueTransaction transaction = mock(CollectQueueTransaction.class);
		CollectRunService runService = mock(CollectRunService.class);
		CollectDataService dataService = mock(CollectDataService.class);
		PlatformCookieService cookieService = mock(PlatformCookieService.class);
		DatabaseWriteExecutor writes = passthroughWrites();
		CollectJobClaim claim = new CollectJobClaim(11L, 90L, 7, CollectTriggerType.SCHEDULED, 3, 3);
		Instant retryAt = Instant.parse("2026-07-29T08:10:05Z");
		when(transaction.claimNext(anyString(), any())).thenReturn(claim);
		when(dataService.isCollectTaskEnabled(7)).thenReturn(true);
		when(cookieService.douyinGlobalCooldownRetryAt(any())).thenReturn(retryAt);
		org.mockito.Mockito.doThrow(new CollectFetchException("F2_COOKIE_COOLDOWN", "cooling"))
				.when(dataService).executeQueuedCollectTask(7, 90L, CollectTriggerType.SCHEDULED);
		CollectJobWorker worker = new CollectJobWorker(transaction, runService, dataService, cookieService, writes, 1);

		try {
			worker.processOne();
			verify(runService).start(90L);
			verify(runService).deferForCooldown(claim, retryAt, "cooling");
			verify(runService, never()).fail(anyLong(), any(), any(), anyString(), anyString(), anyString());
			verify(runService, never()).retryOrFail(any(), anyString(), anyString(), anyLong());
		} finally {
			worker.shutdown();
		}
	}

	@Test
	void riskRetryUsesRemainingCooldownPlusFiveSecondBuffer() {
		assertThat(CollectJobWorker.cooldownRetryDelaySeconds(6 * 60 * 1000L + 1))
				.isEqualTo(366L);
	}

	private DatabaseWriteExecutor passthroughWrites() {
		DatabaseWriteExecutor writes = mock(DatabaseWriteExecutor.class);
		when(writes.execute(anyString(), org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
				.thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
		return writes;
	}
}
