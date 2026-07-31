package com.flower.spirit.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.service.transaction.CollectDownloadTransaction;

import jakarta.annotation.PreDestroy;

@Service
public class CollectDownloadWorker {

	private static final Logger logger = LoggerFactory.getLogger(CollectDownloadWorker.class);
	private final CollectDownloadTransaction transaction;
	private final CollectDownloadService downloadService;
	private final DatabaseWriteExecutor databaseWriteExecutor;
	private final RuntimeControlService runtimeControlService;
	@Autowired(required = false)
	private ApplicationReadinessGate readinessGate;
	private final int batchSize;
	private final int lockTimeoutMinutes;
	private final String workerId = "sqlite-collect-download-" + UUID.randomUUID();
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "collect-download-worker");
		thread.setDaemon(true);
		return thread;
	});

	public CollectDownloadWorker(CollectDownloadTransaction transaction, CollectDownloadService downloadService,
			DatabaseWriteExecutor databaseWriteExecutor, RuntimeControlService runtimeControlService,
			@Value("${streamvault.collect.download-batch-size:10}") int batchSize,
			@Value("${streamvault.collect.download-lock-timeout-minutes:30}") int lockTimeoutMinutes,
			@Value("${streamvault.collect.download-workers:1}") int configuredWorkers) {
		this.transaction = transaction;
		this.downloadService = downloadService;
		this.databaseWriteExecutor = databaseWriteExecutor;
		this.runtimeControlService = runtimeControlService;
		this.batchSize = Math.max(1, Math.min(batchSize, 100));
		this.lockTimeoutMinutes = Math.max(1, lockTimeoutMinutes);
		if (configuredWorkers != 1) {
			logger.warn("[CollectDownloadWorker] SQLite release supports one worker; configured={} effective=1",
					configuredWorkers);
		}
	}

	public void wakeUp() {
		if (!applicationReady()) return;
		if (!running.compareAndSet(false, true)) return;
		try {
			workerExecutor.submit(() -> {
				try {
					processAvailable();
				} catch (RuntimeException error) {
					logger.error("[CollectDownloadWorker] tick failed workerId={}", workerId, error);
				} finally {
					running.set(false);
				}
			});
		} catch (RejectedExecutionException error) {
			running.set(false);
			logger.debug("[CollectDownloadWorker] wake ignored during shutdown workerId={}", workerId);
		}
	}

	public void processAvailable() {
		if (!downloadDecision().allowed()) return;
		recoverStale();
		for (int processed = 0; processed < batchSize; processed++) {
			if (!downloadDecision().allowed()) return;
			CollectDownloadClaim claim = databaseWriteExecutor.execute("collect-download-claim",
					() -> transaction.claimNext(workerId, Instant.now()));
			if (claim == null) return;
			PauseDecision afterClaim = downloadDecision();
			if (!afterClaim.allowed()) {
				deferPausedClaim(claim, afterClaim);
				return;
			}
			try {
				downloadService.process(claim);
			} catch (RuntimeException error) {
				logger.error("[CollectDownloadWorker] item escaped processor itemId={} runId={} workId={}",
						claim.id(), claim.runId(), claim.workId(), error);
				releaseEscapedClaim(claim, error);
			}
		}
	}

	private void recoverStale() {
		Instant now = Instant.now();
		int recovered = databaseWriteExecutor.execute("collect-download-recover-stale",
				() -> transaction.recoverStale(now.minus(lockTimeoutMinutes, ChronoUnit.MINUTES), now));
		if (recovered > 0) {
			logger.warn("[CollectDownloadWorker] recovered stale items count={} workerId={}", recovered, workerId);
		}
	}

	private void deferPausedClaim(CollectDownloadClaim claim, PauseDecision decision) {
		String reason = decision.reason() == null || decision.reason().isBlank()
				? "Download paused after claim" : decision.reason();
		databaseWriteExecutor.execute("collect-download-defer-paused", () -> {
			transaction.deferPaused(claim, reason, Instant.now());
			return null;
		});
	}

	private void releaseEscapedClaim(CollectDownloadClaim claim, RuntimeException error) {
		try {
			databaseWriteExecutor.execute("collect-download-release-escaped", () -> {
				transaction.retryOrFail(claim, "WORKER_PROCESS_ESCAPED", rootMessage(error),
						stackSummary(error), Instant.now());
				return null;
			});
		} catch (RuntimeException transitionError) {
			logger.error("[CollectDownloadWorker] failed to release escaped claim itemId={} runId={} workId={}",
					claim.id(), claim.runId(), claim.workId(), transitionError);
		}
	}

	private PauseDecision downloadDecision() {
		if (!applicationReady()) {
			return PauseDecision.paused("application.readiness", "Application is not ready");
		}
		return runtimeControlService.mayRun(TaskCategory.MEDIA_DOWNLOAD);
	}

	private boolean applicationReady() {
		return readinessGate == null || readinessGate.isReady();
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
			if (result.length() >= 10000) break;
			result.append('\n').append(" at ").append(element);
		}
		return result.length() <= 10000 ? result.toString() : result.substring(0, 10000);
	}

	@PreDestroy
	public void shutdown() {
		workerExecutor.shutdownNow();
	}
}
