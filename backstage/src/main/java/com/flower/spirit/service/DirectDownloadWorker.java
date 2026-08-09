package com.flower.spirit.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.service.transaction.DirectDownloadQueueTransaction;

import jakarta.annotation.PreDestroy;

@Service
public class DirectDownloadWorker {

	private static final Logger logger = LoggerFactory.getLogger(DirectDownloadWorker.class);
	private final DirectDownloadQueueTransaction transaction;
	private final DatabaseWriteExecutor databaseWriteExecutor;
	private final AnalysisService analysisService;
	private final ProcessHistoryService processHistoryService;
	private final RuntimeControlService runtimeControlService;
	private final ApplicationReadinessGate readinessGate;
	private final String workerId = "direct-download-" + UUID.randomUUID();
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean recovered = new AtomicBoolean(false);
	private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "direct-download-worker");
		thread.setDaemon(true);
		return thread;
	});

	public DirectDownloadWorker(DirectDownloadQueueTransaction transaction,
			DatabaseWriteExecutor databaseWriteExecutor, AnalysisService analysisService,
			ProcessHistoryService processHistoryService, RuntimeControlService runtimeControlService,
			ApplicationReadinessGate readinessGate,
			@Value("${streamvault.direct-download.workers:1}") int configuredWorkers) {
		this.transaction = transaction;
		this.databaseWriteExecutor = databaseWriteExecutor;
		this.analysisService = analysisService;
		this.processHistoryService = processHistoryService;
		this.runtimeControlService = runtimeControlService;
		this.readinessGate = readinessGate;
		if (configuredWorkers != 1) {
			logger.warn("[DirectDownloadWorker] effective workers=1 configured={}", configuredWorkers);
		}
	}

	public void wakeUp() {
		if (!readinessGate.isReady() || !running.compareAndSet(false, true)) return;
		try {
			workerExecutor.submit(this::runOne);
		} catch (RejectedExecutionException ignored) {
			running.set(false);
		}
	}

	private void runOne() {
		try {
			recoverOnce();
			if (!runtimeControlService.mayRun(TaskCategory.MEDIA_DOWNLOAD).allowed()) return;
			DirectDownloadClaim claim = databaseWriteExecutor.execute("direct-download-claim",
					() -> transaction.claimNext(workerId, Instant.now()));
			if (claim != null) process(claim);
		} catch (RuntimeException error) {
			logger.error("[DirectDownloadWorker] tick failed", error);
		} finally {
			running.set(false);
		}
	}

	private void process(DirectDownloadClaim claim) {
		logger.info("[DirectDownloadWorker] start jobId={} source={} attempt={}/{} url={}", claim.jobId(),
				claim.sourceType(), claim.attemptCount(), claim.maxAttempts(), claim.sourceUrl());
		if (!runtimeControlService.mayRun(TaskCategory.MEDIA_DOWNLOAD).allowed()) {
			databaseWriteExecutor.execute("direct-download-defer-paused", () -> {
				transaction.deferPaused(claim, "Download queue is paused", Instant.now(), Instant.now().plusSeconds(15));
				return null;
			});
			return;
		}
		try {
			analysisService.executeQueuedDownload(claim.sourceUrl(), claim.historyId());
			databaseWriteExecutor.execute("direct-download-complete", () -> {
				transaction.complete(claim, Instant.now());
				return null;
			});
			processHistoryService.completePlatformProcess(claim.historyId());
			logger.info("[DirectDownloadWorker] complete jobId={} source={}", claim.jobId(), claim.sourceType());
		} catch (Exception error) {
			String message = rootMessage(error);
			String code = error.getClass().getSimpleName().toUpperCase();
			Instant retryAt = Instant.now().plus(Duration.ofSeconds(Math.min(300, 20L * claim.attemptCount())));
			boolean retry = databaseWriteExecutor.execute("direct-download-fail",
					() -> transaction.fail(claim, code, message, Instant.now(), retryAt));
			if (!retry) processHistoryService.failPlatformProcess(claim.historyId(), "DOWNLOAD_FAILED", message);
			logger.error("[DirectDownloadWorker] failed jobId={} retryable={} code={} message={}", claim.jobId(),
					retry, code, message, error);
		}
	}

	private void recoverOnce() {
		if (!recovered.compareAndSet(false, true)) return;
		int count = databaseWriteExecutor.execute("direct-download-recover",
				() -> transaction.recoverStale(Instant.now(), Instant.now()));
		if (count > 0) logger.warn("[DirectDownloadWorker] recovered stale jobs count={}", count);
	}

	private String rootMessage(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) current = current.getCause();
		String message = current.getMessage();
		return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
	}

	@PreDestroy
	public void shutdown() {
		workerExecutor.shutdownNow();
	}
}
