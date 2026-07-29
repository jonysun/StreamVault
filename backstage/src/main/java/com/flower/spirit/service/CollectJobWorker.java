package com.flower.spirit.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.service.transaction.CollectQueueTransaction;
import com.flower.spirit.utils.SqliteErrors;

@Service
public class CollectJobWorker {

	private static final Logger logger = LoggerFactory.getLogger(CollectJobWorker.class);

	private final CollectQueueTransaction transaction;
	private final CollectRunService collectRunService;
	private final CollectDataService collectDataService;
	private final PlatformCookieService platformCookieService;
	private final DatabaseWriteExecutor databaseWriteExecutor;
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
			CollectDataService collectDataService, PlatformCookieService platformCookieService,
			DatabaseWriteExecutor databaseWriteExecutor,
			@Value("${streamvault.collect.fetch-workers:1}") int configuredWorkers) {
		this.transaction = transaction;
		this.collectRunService = collectRunService;
		this.collectDataService = collectDataService;
		this.platformCookieService = platformCookieService;
		this.databaseWriteExecutor = databaseWriteExecutor;
		if (configuredWorkers != 1) {
			logger.warn("[CollectWorker] SQLite release supports one fetch worker; configured={} effective=1",
					configuredWorkers);
		}
	}

	public void processOne() {
		if (!running.compareAndSet(false, true)) return;
		runClaimedTick();
	}

	private void runClaimedTick() {
		ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(this::heartbeatActiveRun,
				15, 15, TimeUnit.SECONDS);
		try {
			if (platformCookieService.isDouyinGlobalCooldownActive()) {
				logger.debug("[CollectWorker] claim deferred by Douyin global cooldown remainingMs={}",
						platformCookieService.douyinGlobalCooldownRemainingMillis());
				return;
			}
			CollectJobClaim claim = databaseWriteExecutor.execute("collect-job-claim",
					() -> transaction.claimNext(workerId, Instant.now()));
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
		try {
			workerExecutor.submit(this::runClaimedTick);
		} catch (RejectedExecutionException error) {
			running.set(false);
			logger.debug("[CollectWorker] wake ignored during shutdown workerId={}", workerId);
		}
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
			if (platformCookieService.isDouyinGlobalCooldownActive()) {
				deferForCooldown(claim, "Douyin global cooldown started after queue claim");
				return;
			}
			collectRunService.start(claim.runId());
			collectDataService.executeQueuedCollectTask(claim.taskId(), claim.runId(), claim.triggerType());
			collectRunService.complete(claim.runId(), claim.jobId());
			logger.info("[CollectWorker] complete jobId={} runId={} taskId={}", claim.jobId(), claim.runId(),
					claim.taskId());
		} catch (CollectFetchException error) {
			if ("F2_COOKIE_COOLDOWN".equals(error.getErrorCode())) {
				deferForCooldown(claim, rootMessage(error));
				return;
			}
			CollectRunState expected = currentExpectedState(claim.runId(), CollectRunState.FETCHING);
			recordFailure(claim, expected, expected == CollectRunState.FETCHING ? CollectRunState.FETCH_FAILED
					: CollectRunState.DB_FAILED, error.getErrorCode(),
					rootMessage(error), error, retryDelaySeconds(error));
		} catch (CollectExecutionPausedException error) {
			CollectRunState expected = currentExpectedState(claim.runId(), CollectRunState.PROCESSING);
			recordFailure(claim, expected, CollectRunState.INTERRUPTED, "PAUSED_DURING_EXECUTION",
					rootMessage(error), error, 30);
		} catch (DataAccessException error) {
			boolean sqliteBusy = SqliteErrors.isBusy(error);
			recordFailure(claim, currentExpectedState(claim.runId(), CollectRunState.PROCESSING),
					CollectRunState.DB_FAILED, sqliteBusy ? "SQLITE_BUSY" : "DB_WRITE_FAILED",
					rootMessage(error), error, sqliteBusy ? 30 : 60);
		} catch (RuntimeException error) {
			boolean sqliteBusy = SqliteErrors.isBusy(error);
			recordFailure(claim, currentExpectedState(claim.runId(), CollectRunState.PROCESSING),
					CollectRunState.DB_FAILED, sqliteBusy ? "SQLITE_BUSY" : "UNEXPECTED",
					rootMessage(error), error, sqliteBusy ? 30 : 900);
		}
	}

	private void deferForCooldown(CollectJobClaim claim, String reason) {
		Instant availableAt = platformCookieService.douyinGlobalCooldownRetryAt(Duration.ofSeconds(5));
		try {
			collectRunService.deferForCooldown(claim, availableAt, reason);
			logger.warn("[CollectWorker] deferred by Douyin cooldown jobId={} runId={} taskId={} availableAt={}",
					claim.jobId(), claim.runId(), claim.taskId(), availableAt);
		} catch (RuntimeException queueWriteError) {
			logger.error("[CollectCooldownDeferralWrite] failed jobId={} runId={} taskId={}", claim.jobId(),
					claim.runId(), claim.taskId(), queueWriteError);
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
			if (retry.inserted() && isExpectedDouyinRisk(errorCode)) {
				logger.warn("[CollectWorker] upstream risk; retry queued jobId={} runId={} taskId={} code={} "
						+ "root={} nextRunId={} nextState={}", claim.jobId(), claim.runId(), claim.taskId(),
						errorCode, message, retry.runId(), retry.state());
			} else {
				logger.error("[CollectWorker] failed jobId={} runId={} taskId={} code={} root={} nextRunId={} nextState={}",
						claim.jobId(), claim.runId(), claim.taskId(), errorCode, message, retry.runId(), retry.state(), error);
			}
		} catch (RuntimeException queueWriteError) {
			logger.error("[CollectJobTerminalWrite] failed jobId={} runId={} taskId={} errorCode={} root={}",
					claim.jobId(), claim.runId(), claim.taskId(), errorCode, message, queueWriteError);
		}
	}

	private static boolean isExpectedDouyinRisk(String errorCode) {
		return "F2_UPSTREAM_RATE_LIMIT".equals(errorCode)
				|| "F2_COOKIE_OR_VERIFY_REQUIRED".equals(errorCode);
	}

	private long retryDelaySeconds(Throwable error) {
		if (error instanceof CollectFetchException fetchError) {
			String errorCode = fetchError.getErrorCode();
			if ("F2_UPSTREAM_RATE_LIMIT".equals(errorCode)
					|| "F2_COOKIE_OR_VERIFY_REQUIRED".equals(errorCode)) {
				return cooldownRetryDelaySeconds(platformCookieService.douyinGlobalCooldownRemainingMillis());
			}
		}
		String message = rootMessage(error).toLowerCase(Locale.ROOT);
		if ((message.contains("cookie") || message.contains("风控") || message.contains("risk")
				|| message.contains("429")) && platformCookieService.isDouyinGlobalCooldownActive()) {
			return cooldownRetryDelaySeconds(platformCookieService.douyinGlobalCooldownRemainingMillis());
		}
		return 900;
	}

	static long cooldownRetryDelaySeconds(long remainingMillis) {
		return Math.max(5, (Math.max(0, remainingMillis) + 999) / 1000 + 5);
	}

	private static String rootMessage(Throwable error) {
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
