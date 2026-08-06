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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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

	@Test
	void queuedRiskRetryIsWarnWithoutStackTrace() {
		CollectRunService runService = mock(CollectRunService.class);
		CollectDataService dataService = mock(CollectDataService.class);
		PlatformCookieService cookieService = mock(PlatformCookieService.class);
		CollectJobClaim claim = new CollectJobClaim(3251L, 4997L, 9,
				CollectTriggerType.RETRY, 2, 3);
		when(dataService.isCollectTaskEnabled(9)).thenReturn(true);
		when(runService.currentState(4997L)).thenReturn(CollectRunState.FETCHING);
		when(runService.retryOrFail(claim, "F2_UPSTREAM_RATE_LIMIT",
				"Douyin author-work endpoint returned empty responses", 605L))
				.thenReturn(new CollectEnqueueResult(4998L, 3251L, CollectRunState.QUEUED, true, false));
		when(cookieService.douyinGlobalCooldownRemainingMillis()).thenReturn(600_000L);
		org.mockito.Mockito.doThrow(new CollectFetchException("F2_UPSTREAM_RATE_LIMIT",
				"Douyin author-work endpoint returned empty responses"))
				.when(dataService).executeQueuedCollectTask(9, 4997L, CollectTriggerType.RETRY);
		CollectJobWorker worker = new CollectJobWorker(mock(CollectQueueTransaction.class), runService,
				dataService, cookieService, passthroughWrites(), 1);
		Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(CollectJobWorker.class);
		ListAppender<ILoggingEvent> events = new ListAppender<>();
		events.start();
		logger.addAppender(events);

		try {
			ReflectionTestUtils.invokeMethod(worker, "process", claim);
			assertThat(events.list).anySatisfy(event -> {
				assertThat(event.getLevel()).isEqualTo(Level.WARN);
				assertThat(event.getFormattedMessage()).contains("upstream risk; retry queued",
						"nextRunId=4998", "nextState=QUEUED");
				assertThat(event.getThrowableProxy()).isNull();
			});
			assertThat(events.list).noneMatch(event -> event.getLevel() == Level.ERROR);
		} finally {
			logger.detachAppender(events);
			events.stop();
			worker.shutdown();
		}
	}

	@Test
	void invalidAuthorIdFailsJobWithoutRetryOrCooldown() {
		CollectRunService runService = mock(CollectRunService.class);
		CollectDataService dataService = mock(CollectDataService.class);
		PlatformCookieService cookieService = mock(PlatformCookieService.class);
		CollectJobClaim claim = new CollectJobClaim(4100L, 6200L, 98,
				CollectTriggerType.SCHEDULED, 1, 3);
		when(dataService.isCollectTaskEnabled(98)).thenReturn(true);
		when(runService.currentState(6200L)).thenReturn(CollectRunState.FETCHING);
		org.mockito.Mockito.doThrow(new CollectFetchException("INVALID_AUTHOR_ID",
				"Douyin author identifier is invalid; update the task source"))
				.when(dataService).executeQueuedCollectTask(98, 6200L, CollectTriggerType.SCHEDULED);
		CollectJobWorker worker = new CollectJobWorker(mock(CollectQueueTransaction.class), runService,
				dataService, cookieService, passthroughWrites(), 1);

		try {
			ReflectionTestUtils.invokeMethod(worker, "process", claim);
			verify(runService).failJob(claim, "INVALID_AUTHOR_ID",
					"Douyin author identifier is invalid; update the task source");
			verify(runService, never()).retryOrFail(any(), anyString(), anyString(), anyLong());
			verify(cookieService, never()).douyinGlobalCooldownRemainingMillis();
		} finally {
			worker.shutdown();
		}
	}

	@Test
	void upstreamUnavailableUsesOrdinaryRetryWithoutCooldown() {
		CollectRunService runService = mock(CollectRunService.class);
		CollectDataService dataService = mock(CollectDataService.class);
		PlatformCookieService cookieService = mock(PlatformCookieService.class);
		CollectJobClaim claim = new CollectJobClaim(4200L, 6300L, 12,
				CollectTriggerType.RETRY, 2, 3);
		when(dataService.isCollectTaskEnabled(12)).thenReturn(true);
		when(runService.currentState(6300L)).thenReturn(CollectRunState.FETCHING);
		when(runService.retryOrFail(claim, "F2_UPSTREAM_UNAVAILABLE",
				"Douyin author-work endpoint did not provide a usable response", 900L))
				.thenReturn(new CollectEnqueueResult(6301L, 4200L, CollectRunState.QUEUED, true, false));
		org.mockito.Mockito.doThrow(new CollectFetchException("F2_UPSTREAM_UNAVAILABLE",
				"Douyin author-work endpoint did not provide a usable response"))
				.when(dataService).executeQueuedCollectTask(12, 6300L, CollectTriggerType.RETRY);
		CollectJobWorker worker = new CollectJobWorker(mock(CollectQueueTransaction.class), runService,
				dataService, cookieService, passthroughWrites(), 1);

		try {
			ReflectionTestUtils.invokeMethod(worker, "process", claim);
			verify(runService).retryOrFail(claim, "F2_UPSTREAM_UNAVAILABLE",
					"Douyin author-work endpoint did not provide a usable response", 900L);
			verify(cookieService, never()).douyinGlobalCooldownRemainingMillis();
			verify(runService, never()).failJob(any(), anyString(), anyString());
		} finally {
			worker.shutdown();
		}
	}

	private DatabaseWriteExecutor passthroughWrites() {
		DatabaseWriteExecutor writes = mock(DatabaseWriteExecutor.class);
		when(writes.execute(anyString(), org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
				.thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
		return writes;
	}
}
