package com.flower.spirit.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.DouyinGlobalCooldownException;
import com.flower.spirit.platform.DouyinWorkFetchException;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.service.WorkIngestService.IngestResult;
import com.flower.spirit.service.transaction.CollectDownloadTransaction;
import com.flower.spirit.utils.FileUtil;
import com.flower.spirit.utils.SqliteErrors;

@Service
public class CollectDownloadService {

	private static final Logger logger = LoggerFactory.getLogger(CollectDownloadService.class);
	private final WorkIngestService workIngestService;
	private final CollectDownloadTransaction transaction;
	private final DatabaseWriteExecutor databaseWriteExecutor;
	private final CollectEnqueueService collectEnqueueService;
	private static final Duration SNAPSHOT_REFRESH_WAIT = Duration.ofMinutes(15);

	public CollectDownloadService(WorkIngestService workIngestService, CollectDownloadTransaction transaction,
			DatabaseWriteExecutor databaseWriteExecutor, CollectEnqueueService collectEnqueueService) {
		this.workIngestService = workIngestService;
		this.transaction = transaction;
		this.databaseWriteExecutor = databaseWriteExecutor;
		this.collectEnqueueService = collectEnqueueService;
	}

	public void process(CollectDownloadClaim claim) {
		process(claim, Instant.now());
	}

	public void process(CollectDownloadClaim claim, Instant now) {
		if (claim == null) {
			throw new IllegalArgumentException("Download claim is required");
		}
		IngestResult result;
		try {
			validateClaim(claim);
			if (claim.metadataSnapshot() == null || claim.metadataSnapshot().isBlank()) {
				deferForSnapshotRefresh(claim, now);
				return;
			}
			String source = "https://www.douyin.com/video/" + claim.workId();
			Function<WorkMetadata, Path> directory = metadata -> outputDirectory(claim, metadata);
			result = workIngestService.ingest(source, directory, shouldReplaceExisting(claim), null,
					claim.metadataSnapshot());
			validateResult(claim, result);
		} catch (DouyinGlobalCooldownException cooldown) {
			if (cooldown.actualUpstreamFailure()) {
				recordFailure(claim, classify(cooldown), cooldown, now);
			} else {
				deferForCooldown(claim, cooldown, now);
			}
			return;
		} catch (RuntimeException error) {
			recordFailure(claim, classify(error), error, now);
			return;
		}
		try {
			databaseWriteExecutor.execute("collect-download-complete", () -> {
				transaction.complete(claim, result, now);
				return null;
			});
			logger.info("[CollectDownload] complete itemId={} runId={} workId={} created={}", claim.id(),
					claim.runId(), claim.workId(), result.persistence().created());
		} catch (RuntimeException error) {
			if (isLeaseLost(error)) {
				logger.warn("[CollectDownload] completion ignored after lease loss itemId={} runId={} workId={} message={}",
						claim.id(), claim.runId(), claim.workId(), rootCauseMessage(error));
				return;
			}
			String root = rootCauseMessage(error);
			CollectDownloadException classified = error instanceof DataAccessException
					? classify(error) : new CollectDownloadException("DB_WRITE_FAILED", true, root, error);
			recordFailure(claim, classified, error, now);
		}
	}

	private void deferForSnapshotRefresh(CollectDownloadClaim claim, Instant now) {
		Instant availableAt = now.plus(SNAPSHOT_REFRESH_WAIT);
		int pending = databaseWriteExecutor.execute("collect-download-list-snapshot-pending",
				() -> transaction.deferForSnapshotRefresh(claim, availableAt, now));
		try {
			CollectEnqueueResult refresh = collectEnqueueService.enqueueSnapshotRefresh(claim.taskId());
			logger.warn("[CollectDownload] event=LIST_SNAPSHOT_PENDING itemId={} runId={} taskId={} workId={} "
					+ "pending={} availableAt={} refreshRunId={} refreshInserted={}", claim.id(), claim.runId(),
					claim.taskId(), claim.workId(), pending, availableAt, refresh.runId(), refresh.inserted());
		} catch (RuntimeException error) {
			logger.error("[CollectDownload] event=LIST_SNAPSHOT_REFRESH_QUEUE_FAILED itemId={} runId={} "
					+ "taskId={} workId={} pending={} availableAt={}", claim.id(), claim.runId(), claim.taskId(),
					claim.workId(), pending, availableAt, error);
		}
	}

	private void deferForCooldown(CollectDownloadClaim claim, DouyinGlobalCooldownException cooldown, Instant now) {
		databaseWriteExecutor.execute("collect-download-douyin-cooldown", () -> {
			transaction.deferForCooldown(claim, cooldown.retryAt(), cooldown.getMessage(), now);
			return null;
		});
		logger.warn("[CollectDownload] deferred by Douyin cooldown itemId={} runId={} workId={} availableAt={}",
				claim.id(), claim.runId(), claim.workId(), cooldown.retryAt());
	}

	private boolean shouldReplaceExisting(CollectDownloadClaim claim) {
		return claim.decision() != null
				&& claim.decision().toUpperCase(Locale.ROOT).contains("AUDIT_REPAIR");
	}

	private void recordFailure(CollectDownloadClaim claim, CollectDownloadException classified,
			RuntimeException error, Instant now) {
		boolean hydrated = databaseWriteExecutor.execute("collect-download-snapshot-race-retry",
				() -> transaction.retryAfterSnapshotHydration(claim, now));
		if (hydrated) {
			logger.info("[CollectDownload] event=LIST_SNAPSHOT_HYDRATED_AFTER_CLAIM itemId={} runId={} "
					+ "taskId={} workId={}", claim.id(), claim.runId(), claim.taskId(), claim.workId());
			return;
		}
		String operation = "collect-download-" + classified.errorCode().toLowerCase(Locale.ROOT);
		String detail = stackSummary(error);
		databaseWriteExecutor.execute(operation, () -> {
			if ("WORK_BLOCKED".equals(classified.errorCode())) {
				transaction.skipBlocked(claim, classified.getMessage(), now);
			} else if (classified.retryable()) {
				transaction.retryOrFail(claim, classified.errorCode(), classified.getMessage(), detail, now);
			} else {
				transaction.fail(claim, classified.errorCode(), classified.getMessage(), detail, now);
			}
			return null;
		});
		logger.warn("[CollectDownload] item failed itemId={} runId={} workId={} code={} retryable={} message={}",
				claim.id(), claim.runId(), claim.workId(), classified.errorCode(), classified.retryable(),
				classified.getMessage(), error);
	}

	private boolean isLeaseLost(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof IllegalStateException && current.getMessage() != null
					&& current.getMessage().contains("was not RUNNING during transition")) return true;
		}
		return false;
	}

	private void validateClaim(CollectDownloadClaim claim) {
		if (!"douyin".equalsIgnoreCase(claim.platformKey())) {
			throw new CollectDownloadException("UNSUPPORTED_PLATFORM", false,
					"Persistent collection download currently supports Douyin only");
		}
		if (claim.workId() == null || claim.workId().isBlank()) {
			throw new CollectDownloadException("UPSTREAM_SCHEMA_ERROR", false, "Claimed work ID is empty");
		}
	}

	private void validateResult(CollectDownloadClaim claim, IngestResult result) {
		if (result == null) {
			throw new CollectDownloadException("INGEST_NOT_TERMINAL", true, "Work ingest returned no result");
		}
		if (result.status() != DownloadResult.Status.COMPLETED) {
			throw new CollectDownloadException("INGEST_NOT_TERMINAL", true,
					"Work ingest is not complete: " + valueOr(result.message(), result.status().name()));
		}
		if (result.metadata() == null || result.persistence() == null) {
			throw new CollectDownloadException("UPSTREAM_SCHEMA_ERROR", false,
					"Completed ingest returned incomplete metadata or persistence result");
		}
		if (!claim.workId().equals(result.metadata().getWorkId())) {
			throw new CollectDownloadException("UPSTREAM_SCHEMA_ERROR", false,
					"Ingested work ID does not match the claimed work");
		}
	}

	private Path outputDirectory(CollectDownloadClaim claim, WorkMetadata metadata) {
		String platform = valueOr(metadata.getPlatformDisplayName(), metadata.getPlatformKey());
		return Path.of(FileUtil.generateDir(true, platform, false, claim.workId(), claim.taskName(), null));
	}

	private CollectDownloadException classify(RuntimeException error) {
		if (error instanceof CollectDownloadException download) return download;
		DouyinWorkFetchException workFetch = findCause(error, DouyinWorkFetchException.class);
		if (workFetch != null) {
			return new CollectDownloadException(workFetch.errorCode(), workFetch.retryable(),
					workFetch.getMessage() + "; " + workFetch.diagnostics().summary(), error);
		}
		String root = rootCauseMessage(error);
		String normalized = root.toLowerCase(Locale.ROOT);
		if (hasCause(error, DataAccessException.class)) {
			String code = SqliteErrors.isBusy(error) ? "SQLITE_BUSY" : "DB_WRITE_FAILED";
			return new CollectDownloadException(code, true, root, error);
		}
		if (normalized.contains("work is blocked") || normalized.contains("blocked work")) {
			return new CollectDownloadException("WORK_BLOCKED", false, root, error);
		}
		if (hasCause(error, IOException.class) || containsAny(normalized,
				"unexpected end of stream", "connection reset", "broken pipe", "timed out", "timeout")) {
			return new CollectDownloadException("NETWORK_IO", true, root, error);
		}
		if (containsAny(normalized, "no aweme detail", "detail refresh", "failed to refresh")) {
			return new CollectDownloadException("DETAIL_REFRESH_FAILED", true, root, error);
		}
		if (hasCause(error, InterruptedException.class)) {
			return new CollectDownloadException("F2_RUNTIME_ERROR", true, root, error);
		}
		if (containsAny(normalized, "no media resources", "no downloadable visual media", "missing or empty",
				"media is missing or empty", "empty media")) {
			return new CollectDownloadException("EMPTY_MEDIA", true, root, error);
		}
		if (hasCause(error, IllegalStateException.class)) {
			return new CollectDownloadException("INGEST_STATE_ERROR", true, root, error);
		}
		if (error instanceof WorkMetadataValidationException || error instanceof IllegalArgumentException) {
			return new CollectDownloadException("WORK_VALIDATION_FAILED", false, root, error);
		}
		return new CollectDownloadException("DOWNLOAD_EXECUTION_FAILED", true, root, error);
	}

	private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (type.isInstance(current)) return true;
		}
		return false;
	}

	private <T extends Throwable> T findCause(Throwable error, Class<T> type) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (type.isInstance(current)) return type.cast(current);
		}
		return null;
	}

	private boolean containsAny(String value, String... candidates) {
		for (String candidate : candidates) {
			if (value.contains(candidate)) return true;
		}
		return false;
	}

	private String rootCauseMessage(Throwable error) {
		Throwable root = error;
		while (root.getCause() != null && root.getCause() != root) root = root.getCause();
		return valueOr(root.getMessage(), root.getClass().getSimpleName());
	}

	private String stackSummary(Throwable error) {
		StringBuilder result = new StringBuilder();
		for (Throwable current = error; current != null && result.length() < 10000; current = current.getCause()) {
			if (result.length() > 0) result.append("\nCaused by: ");
			result.append(current.getClass().getName()).append(": ").append(valueOr(current.getMessage(), ""));
			StackTraceElement[] stack = current.getStackTrace();
			for (int index = 0; index < Math.min(stack.length, 8); index++) {
				result.append("\n  at ").append(stack[index]);
			}
		}
		return result.length() <= 10000 ? result.toString() : result.substring(0, 10000);
	}

	private String valueOr(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}
}
