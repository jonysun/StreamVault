package com.flower.spirit.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.service.transaction.CollectQueueTransaction;
import com.flower.spirit.service.transaction.CollectDownloadTransaction;

@Service
public class CollectRunService {

	private final CollectQueueTransaction transaction;
	private final CollectDownloadTransaction downloadTransaction;
	private final DatabaseWriteExecutor databaseWriteExecutor;

	public CollectRunService(CollectQueueTransaction transaction, CollectDownloadTransaction downloadTransaction,
			DatabaseWriteExecutor databaseWriteExecutor) {
		this.transaction = transaction;
		this.downloadTransaction = downloadTransaction;
		this.databaseWriteExecutor = databaseWriteExecutor;
	}

	public void start(long runId) {
		databaseWriteExecutor.execute("collect-run-start", () -> {
			transaction.transition(runId, CollectRunState.QUEUED, CollectRunState.FETCHING, Instant.now());
			return null;
		});
	}

	public void skipClaimed(CollectJobClaim claim, String reason) {
		databaseWriteExecutor.execute("collect-run-skip", () -> {
			transaction.skipClaimed(claim, reason, Instant.now());
			return null;
		});
	}

	public void deferForCooldown(CollectJobClaim claim, Instant availableAt, String reason) {
		databaseWriteExecutor.execute("collect-run-defer-cooldown", () -> {
			transaction.deferForCooldown(claim, availableAt, reason, Instant.now());
			return null;
		});
	}

	public void storeFetchedItems(long runId, List<CollectRunFetchedItem> items) {
		databaseWriteExecutor.execute("collect-run-store-fetched-items", () -> {
			transaction.storeFetchedItems(runId, items, Instant.now());
			return null;
		});
	}

	public void storeFetchPlan(long runId, int taskId, List<CollectRunFetchedItem> items, int observedCount,
			String stopReason, CollectRunFetchedItem.FetchWatermark watermark) {
		storeFetchPlan(runId, taskId, items, observedCount, stopReason, watermark, null);
	}

	public void storeFetchPlan(long runId, int taskId, List<CollectRunFetchedItem> items, int observedCount,
			String stopReason, CollectRunFetchedItem.FetchWatermark watermark,
			CollectBackfillProgress backfillProgress) {
		databaseWriteExecutor.execute("collect-run-store-fetch-plan", () -> {
			transaction.storeFetchPlan(runId, taskId, items, observedCount, stopReason, watermark,
					backfillProgress, Instant.now());
			return null;
		});
	}

	public void updateItem(long runId, String workId, String decision, String processState, String errorCode,
			String errorMessage) {
		databaseWriteExecutor.execute("collect-run-update-item", () -> {
			transaction.updateItem(runId, workId, decision, processState, errorCode, errorMessage, Instant.now());
			return null;
		});
	}

	public void heartbeat(long runId) {
		databaseWriteExecutor.execute("collect-run-heartbeat", () -> {
			transaction.heartbeat(runId, Instant.now());
			return null;
		});
	}

	public CollectRunState currentState(long runId) {
		return databaseWriteExecutor.execute("collect-run-current-state", () -> transaction.currentState(runId));
	}

	public void complete(long runId, long jobId) {
		databaseWriteExecutor.execute("collect-run-complete", () -> {
			transaction.complete(runId, jobId, Instant.now());
			return null;
		});
	}

	public void fail(long runId, CollectRunState expected, CollectRunState failedState, String errorCode,
			String message, String detail) {
		databaseWriteExecutor.execute("collect-run-fail", () -> {
			transaction.failRun(runId, expected, failedState, errorCode, message, detail, Instant.now());
			return null;
		});
	}

	public CollectEnqueueResult retryOrFail(CollectJobClaim claim, String errorCode, String message,
			long delaySeconds) {
		Instant now = Instant.now();
		return databaseWriteExecutor.execute("collect-job-retry-or-fail", () -> transaction.retryOrFailJob(claim, errorCode, message,
				now.plusSeconds(Math.max(1, delaySeconds)), now));
	}

	public void failJob(CollectJobClaim claim, String errorCode, String message) {
		databaseWriteExecutor.execute("collect-job-fail", () -> {
			transaction.failJob(claim, errorCode, message, Instant.now());
			return null;
		});
	}

	public Map<String, Object> retryDownloadItem(long itemId) {
		boolean updated = databaseWriteExecutor.execute("collect-download-manual-retry",
				() -> downloadTransaction.manualRetry(itemId, Instant.now()));
		if (!updated) {
			throw new IllegalArgumentException("下载项不存在、不是失败状态或不属于当前下载队列");
		}
		return Map.of("itemId", itemId, "processState", "QUEUED");
	}

	public int retryFailedDownloads(long runId) {
		return databaseWriteExecutor.execute("collect-download-retry-failed",
				() -> downloadTransaction.retryFailedRun(runId, Instant.now()));
	}

	public int manualRetryDownloads(List<Long> itemIds) {
		return databaseWriteExecutor.execute("collect-download-manual-retry-batch",
				() -> downloadTransaction.manualRetryItems(itemIds, Instant.now()));
	}

	public int moveDownloadsToRetry(List<Long> itemIds, Instant availableAt) {
		return databaseWriteExecutor.execute("collect-download-manual-retry-wait",
				() -> downloadTransaction.moveToRetry(itemIds, availableAt, Instant.now()));
	}

	public int markDownloadsFailed(List<Long> itemIds, String reason) {
		return databaseWriteExecutor.execute("collect-download-manual-fail",
				() -> downloadTransaction.markFailed(itemIds, reason, Instant.now()));
	}

	public int markDownloadsRemoteMissing(List<Long> itemIds) {
		return databaseWriteExecutor.execute("collect-download-manual-remote-missing",
				() -> downloadTransaction.markRemoteMissing(itemIds, Instant.now()));
	}

	public int cancelDownloads(List<Long> itemIds) {
		return databaseWriteExecutor.execute("collect-download-manual-cancel",
				() -> downloadTransaction.cancel(itemIds, Instant.now()));
	}
}
