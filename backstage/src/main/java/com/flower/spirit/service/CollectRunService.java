package com.flower.spirit.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.flower.spirit.service.transaction.CollectQueueTransaction;

@Service
public class CollectRunService {

	private final CollectQueueTransaction transaction;
	private final SqliteWriteRetrier sqliteWriteRetrier;

	public CollectRunService(CollectQueueTransaction transaction, SqliteWriteRetrier sqliteWriteRetrier) {
		this.transaction = transaction;
		this.sqliteWriteRetrier = sqliteWriteRetrier;
	}

	public void start(long runId) {
		sqliteWriteRetrier.execute(() -> {
			transaction.transition(runId, CollectRunState.QUEUED, CollectRunState.FETCHING, Instant.now());
			return null;
		});
	}

	public void skipClaimed(CollectJobClaim claim, String reason) {
		sqliteWriteRetrier.execute(() -> {
			transaction.skipClaimed(claim, reason, Instant.now());
			return null;
		});
	}

	public void storeFetchedItems(long runId, List<CollectRunFetchedItem> items) {
		sqliteWriteRetrier.execute(() -> {
			transaction.storeFetchedItems(runId, items, Instant.now());
			return null;
		});
	}

	public void updateItem(long runId, String workId, String decision, String processState, String errorCode,
			String errorMessage) {
		sqliteWriteRetrier.execute(() -> {
			transaction.updateItem(runId, workId, decision, processState, errorCode, errorMessage, Instant.now());
			return null;
		});
	}

	public void heartbeat(long runId) {
		sqliteWriteRetrier.execute(() -> {
			transaction.heartbeat(runId, Instant.now());
			return null;
		});
	}

	public CollectRunState currentState(long runId) {
		return sqliteWriteRetrier.execute(() -> transaction.currentState(runId));
	}

	public void complete(long runId, long jobId) {
		sqliteWriteRetrier.execute(() -> {
			transaction.complete(runId, jobId, Instant.now());
			return null;
		});
	}

	public void fail(long runId, CollectRunState expected, CollectRunState failedState, String errorCode,
			String message, String detail) {
		sqliteWriteRetrier.execute(() -> {
			transaction.failRun(runId, expected, failedState, errorCode, message, detail, Instant.now());
			return null;
		});
	}

	public CollectEnqueueResult retryOrFail(CollectJobClaim claim, String errorCode, String message,
			long delaySeconds) {
		Instant now = Instant.now();
		return sqliteWriteRetrier.execute(() -> transaction.retryOrFailJob(claim, errorCode, message,
				now.plusSeconds(Math.max(1, delaySeconds)), now));
	}
}
