package com.flower.spirit.service;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import com.flower.spirit.service.transaction.CollectQueueTransaction;

@Service
public class CollectJobWorker {

	private static final Logger logger = LoggerFactory.getLogger(CollectJobWorker.class);

	private final CollectQueueTransaction transaction;
	private final CollectRunService collectRunService;
	private final CollectDataService collectDataService;
	private final SqliteWriteRetrier sqliteWriteRetrier;
	private final String workerId = "sqlite-collect-" + UUID.randomUUID();
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicLong activeRunId = new AtomicLong(0);
	private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "collect-fetch-worker");
		thread.setDaemon(true);
		return thread;
	});
	private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "collect-run-heartbeat");
		thread.setDaemon(true);
		return thread;
	});

	public CollectJobWorker(CollectQueueTransaction transaction, CollectRunService collectRunService,
			CollectDataService collectDataService, SqliteWriteRetrier sqliteWriteRetrier) {
		this.transaction = transaction;
		this.collectRunService = collectRunService;
		this.collectDataService = collectDataService;
		this.sqliteWriteRetrier = sqliteWriteRetrier;
	}

	public void processOne() {
		if (!running.compareAndSet(false, true)) return;
		runClaimedTick();
	}

	private void runClaimedTick() {
		ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(this::heartbeatActiveRun,
				15, 15, TimeUnit.SECONDS);
		try {
			CollectJobClaim claim = sqliteWriteRetrier.execute(() -> transaction.claimNext(workerId, Instant.now()));
			if (claim != null) process(claim);
		} catch (RuntimeException error) {
			logger.error("[CollectWorker] tick failed", error);
		} finally {
			heartbeat.cancel(false);
			activeRunId.set(0);
			running.set(false);
		}
	}

	public void wakeUp() {
		if (!running.compareAndSet(false, true)) return;
		workerExecutor.submit(this::runClaimedTick);
	}

	public void heartbeatActiveRun() {
		long runId = activeRunId.get();
		if (runId <= 0) return;
		try {
			collectRunService.heartbeat(runId);
		} catch (RuntimeException error) {
			logger.error("[CollectWorkerHeartbeat] failed runId={} workerId={}", runId, workerId, error);
		}
	}

	private void process(CollectJobClaim claim) {
		activeRunId.set(claim.runId());
		logger.info("[CollectWorker] start jobId={} runId={} taskId={} attempt={}/{} trigger={}", claim.jobId(),
				claim.runId(), claim.taskId(), claim.attemptCount(), claim.maxAttempts(), claim.triggerType());
		try {
			if (!collectDataService.isCollectTaskEnabled(claim.taskId())) {
				collectRunService.skipClaimed(claim, "收藏任务在领取后已被停用");
				logger.info("[CollectWorker] skipped paused task jobId={} runId={} taskId={}", claim.jobId(),
						claim.runId(), claim.taskId());
				return;
			}
			collectRunService.start(claim.runId());
			collectDataService.executeQueuedCollectTask(claim.taskId(), claim.runId());
			collectRunService.complete(claim.runId(), claim.jobId());
			logger.info("[CollectWorker] complete jobId={} runId={} taskId={}", claim.jobId(), claim.runId(),
					claim.taskId());
		} catch (CollectFetchException error) {
			CollectRunState expected = currentExpectedState(claim.runId(), CollectRunState.FETCHING);
			recordFailure(claim, expected, expected == CollectRunState.FETCHING ? CollectRunState.FETCH_FAILED
					: CollectRunState.DB_FAILED, error.getErrorCode(),
					rootMessage(error), error, retryDelaySeconds(error));
		} catch (CollectExecutionPausedException error) {
			CollectRunState expected = currentExpectedState(claim.runId(), CollectRunState.PROCESSING);
			recordFailure(claim, expected, CollectRunState.INTERRUPTED, "PAUSED_DURING_EXECUTION",
					rootMessage(error), error, 30);
		} catch (DataAccessException error) {
			recordFailure(claim, currentExpectedState(claim.runId(), CollectRunState.PROCESSING), CollectRunState.DB_FAILED, "DB_WRITE_FAILED",
					rootMessage(error), error, 60);
		} catch (RuntimeException error) {
			recordFailure(claim, currentExpectedState(claim.runId(), CollectRunState.PROCESSING), CollectRunState.DB_FAILED, "UNEXPECTED",
					rootMessage(error), error, 60);
		}
	}

	private CollectRunState currentExpectedState(long runId, CollectRunState fallback) {
		try {
			CollectRunState state = collectRunService.currentState(runId);
			return state == CollectRunState.FETCHING || state == CollectRunState.PROCESSING ? state : fallback;
		} catch (RuntimeException ignored) {
			return fallback;
		}
	}

	private void recordFailure(CollectJobClaim claim, CollectRunState expected, CollectRunState failedState,
			String errorCode, String message, Throwable error, long retryDelaySeconds) {
		try {
			collectRunService.fail(claim.runId(), expected, failedState, errorCode, message, stackSummary(error));
		} catch (RuntimeException terminalWriteError) {
			logger.error("[CollectRunTerminalWrite] failed runId={} jobId={} taskId={} targetState={} "
					+ "originalCode={} originalMessage={}", claim.runId(), claim.jobId(), claim.taskId(), failedState,
					errorCode, message, terminalWriteError);
			return;
		}
		try {
			CollectEnqueueResult retry = collectRunService.retryOrFail(claim, errorCode, message, retryDelaySeconds);
			logger.error("[CollectWorker] failed jobId={} runId={} taskId={} code={} root={} nextRunId={} nextState={}",
					claim.jobId(), claim.runId(), claim.taskId(), errorCode, message, retry.runId(), retry.state(), error);
		} catch (RuntimeException queueWriteError) {
			logger.error("[CollectJobTerminalWrite] failed jobId={} runId={} taskId={} errorCode={} root={}",
					claim.jobId(), claim.runId(), claim.taskId(), errorCode, message, queueWriteError);
		}
	}

	private long retryDelaySeconds(Throwable error) {
		String message = rootMessage(error).toLowerCase(Locale.ROOT);
		if (message.contains("cookie") || message.contains("风控") || message.contains("risk")
				|| message.contains("429")) return 3600;
		return 900;
	}

	private String rootMessage(Throwable error) {
		Throwable root = error;
		while (root.getCause() != null && root.getCause() != root) root = root.getCause();
		return root.getMessage() == null || root.getMessage().isBlank()
				? root.getClass().getSimpleName() : root.getMessage();
	}

	private String stackSummary(Throwable error) {
		StringBuilder result = new StringBuilder(error.toString());
		for (StackTraceElement element : error.getStackTrace()) {
			if (result.length() >= 9000) break;
			result.append('\n').append(" at ").append(element);
		}
		return result.toString();
	}

	@PreDestroy
	public void shutdown() {
		workerExecutor.shutdownNow();
		heartbeatExecutor.shutdownNow();
	}
}
