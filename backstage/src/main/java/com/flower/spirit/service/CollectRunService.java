package com.flower.spirit.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.service.transaction.CollectQueueTransaction;

@Service
public class CollectRunService {

	private final CollectQueueTransaction transaction;
	private final DatabaseWriteExecutor databaseWriteExecutor;

	public CollectRunService(CollectQueueTransaction transaction, DatabaseWriteExecutor databaseWriteExecutor) {
		this.transaction = transaction;
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

	public void storeFetchedItems(long runId, List<CollectRunFetchedItem> items) {
		databaseWriteExecutor.execute("collect-run-store-fetched-items", () -> {
			transaction.storeFetchedItems(runId, items, Instant.now());
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
}
