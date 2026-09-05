package com.flower.spirit.service.transaction;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flower.spirit.service.CollectDownloadClaim;
import com.flower.spirit.service.WorkIngestService.IngestResult;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkMetadata;

@Service
public class CollectDownloadTransaction {

	public static final String QUEUE_GENERATION = "FETCH_DOWNLOAD_V1";
	private static final List<Duration> RETRY_DELAYS = List.of(
			Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30));
	public static final String SNAPSHOT_PENDING = "LIST_SNAPSHOT_PENDING";

	private final JdbcTemplate jdbcTemplate;

	public CollectDownloadTransaction(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public CollectDownloadClaim claimNext(String workerId, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		List<DownloadCandidate> candidates = jdbcTemplate.query(
				"SELECT i.id, i.run_id, r.collect_task_id, t.taskname, i.platform_key, i.work_id, "
						+ "i.media_type, i.decision, i.ordinal, i.attempt_count, i.max_attempts, i.metadata_snapshot "
						+ "FROM biz_collect_run_item i "
						+ "JOIN biz_collect_run r ON r.id = i.run_id "
						+ "JOIN biz_collect_data t ON t.id = r.collect_task_id "
						+ "WHERE i.queue_generation = ? "
						+ "AND i.process_state IN ('QUEUED','RETRY_WAIT') "
						+ "AND i.attempt_count < i.max_attempts "
						+ "AND (i.available_at IS NULL OR i.available_at <= ?) "
						+ "ORDER BY CASE WHEN i.decision LIKE 'MANUAL_RETRY%' THEN 0 ELSE 1 END, "
						+ "CASE WHEN i.metadata_snapshot IS NOT NULL AND TRIM(i.metadata_snapshot) <> '' THEN 0 ELSE 1 END, "
						+ "i.ordinal ASC, i.available_at ASC, i.created_at ASC, i.id ASC LIMIT 1",
				(rs, rowNum) -> new DownloadCandidate(rs.getLong("id"), rs.getLong("run_id"),
						rs.getInt("collect_task_id"), rs.getString("taskname"), rs.getString("platform_key"),
						rs.getString("work_id"), rs.getString("media_type"), rs.getString("decision"),
						rs.getInt("ordinal"),
						rs.getInt("attempt_count"), rs.getInt("max_attempts"), rs.getString("metadata_snapshot")),
				QUEUE_GENERATION, timestamp);
		if (candidates.isEmpty()) {
			return null;
		}
		DownloadCandidate candidate = candidates.get(0);
		String lockToken = workerId + ":" + UUID.randomUUID();
		int updated = jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state = 'RUNNING', "
				+ "attempt_count = attempt_count + 1, locked_by = ?, locked_at = ?, "
				+ "started_at = COALESCE(started_at, ?), updated_at = ? "
				+ "WHERE id = ? AND queue_generation = ? "
				+ "AND process_state IN ('QUEUED','RETRY_WAIT') AND attempt_count < max_attempts",
				lockToken, timestamp, timestamp, timestamp, candidate.id(), QUEUE_GENERATION);
		if (updated != 1) return null;
		return new CollectDownloadClaim(candidate.id(), candidate.runId(), candidate.taskId(), candidate.taskName(),
				candidate.platformKey(), candidate.workId(), candidate.mediaType(), candidate.decision(),
				candidate.ordinal(), candidate.attemptCount() + 1, candidate.maxAttempts(), lockToken,
				candidate.metadataSnapshot());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void retryOrFail(CollectDownloadClaim claim, String errorCode, String errorMessage,
			String errorDetail, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		boolean exhausted = claim.attemptCount() >= claim.maxAttempts();
		int updated;
		if (exhausted) {
			updated = jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state = 'FAILED', "
					+ "available_at = NULL, locked_by = NULL, locked_at = NULL, finished_at = ?, "
					+ "error_code = ?, error_message = ?, error_detail = ?, updated_at = ? "
					+ "WHERE id = ? AND queue_generation = ? AND process_state = 'RUNNING' AND locked_by = ?",
					timestamp, errorCode, truncate(errorMessage, 2048), truncate(errorDetail, 10000), timestamp,
					claim.id(), QUEUE_GENERATION, claim.lockToken());
		} else {
			Instant availableAt = now.plus(retryDelay(claim.attemptCount()));
			updated = jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state = 'RETRY_WAIT', "
					+ "available_at = ?, locked_by = NULL, locked_at = NULL, finished_at = NULL, "
					+ "error_code = ?, error_message = ?, error_detail = ?, updated_at = ? "
					+ "WHERE id = ? AND queue_generation = ? AND process_state = 'RUNNING' AND locked_by = ?",
					Timestamp.from(availableAt), errorCode, truncate(errorMessage, 2048),
					truncate(errorDetail, 10000), timestamp, claim.id(), QUEUE_GENERATION, claim.lockToken());
		}
		requireTransition(updated, claim.id(), exhausted ? "FAILED" : "RETRY_WAIT");
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void fail(CollectDownloadClaim claim, String errorCode, String errorMessage,
			String errorDetail, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		int updated = jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state = 'FAILED', "
				+ "available_at = NULL, locked_by = NULL, locked_at = NULL, finished_at = ?, "
				+ "error_code = ?, error_message = ?, error_detail = ?, updated_at = ? "
				+ "WHERE id = ? AND queue_generation = ? AND process_state = 'RUNNING' AND locked_by = ?",
				timestamp, errorCode, truncate(errorMessage, 2048), truncate(errorDetail, 10000), timestamp,
				claim.id(), QUEUE_GENERATION, claim.lockToken());
		requireTransition(updated, claim.id(), "FAILED");
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void skipBlocked(CollectDownloadClaim claim, String reason, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		int updated = jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state = 'SKIPPED_BLOCKED', "
				+ "available_at = NULL, locked_by = NULL, locked_at = NULL, finished_at = ?, "
				+ "error_code = 'WORK_BLOCKED', error_message = ?, error_detail = NULL, metadata_snapshot = NULL, updated_at = ? "
				+ "WHERE id = ? AND queue_generation = ? AND process_state = 'RUNNING' AND locked_by = ?",
				timestamp, truncate(reason, 2048), timestamp, claim.id(), QUEUE_GENERATION, claim.lockToken());
		requireTransition(updated, claim.id(), "SKIPPED_BLOCKED");
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void deferPaused(CollectDownloadClaim claim, String reason, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		int updated = jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state = 'RETRY_WAIT', "
				+ "attempt_count = CASE WHEN attempt_count > 0 THEN attempt_count - 1 ELSE 0 END, "
				+ "available_at = ?, locked_by = NULL, locked_at = NULL, finished_at = NULL, "
				+ "error_code = 'PAUSED_AFTER_CLAIM', error_message = ?, error_detail = NULL, updated_at = ? "
				+ "WHERE id = ? AND queue_generation = ? AND process_state = 'RUNNING' AND locked_by = ?",
				timestamp, truncate(reason, 2048), timestamp, claim.id(), QUEUE_GENERATION, claim.lockToken());
		requireTransition(updated, claim.id(), "RETRY_WAIT");
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void deferForCooldown(CollectDownloadClaim claim, Instant availableAt, String reason, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		int updated = jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state = 'RETRY_WAIT', "
				+ "attempt_count = CASE WHEN attempt_count > 0 THEN attempt_count - 1 ELSE 0 END, "
				+ "available_at = ?, locked_by = NULL, locked_at = NULL, finished_at = NULL, "
				+ "error_code = 'DOUYIN_GLOBAL_COOLDOWN', error_message = ?, error_detail = NULL, updated_at = ? "
				+ "WHERE id = ? AND queue_generation = ? AND process_state = 'RUNNING' AND locked_by = ?",
				Timestamp.from(availableAt), truncate(reason, 2048), timestamp, claim.id(), QUEUE_GENERATION,
				claim.lockToken());
		requireTransition(updated, claim.id(), "RETRY_WAIT");
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int deferForSnapshotRefresh(CollectDownloadClaim claim, Instant availableAt, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		Timestamp retryAt = Timestamp.from(availableAt);
		int updated = jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state = 'RETRY_WAIT', "
				+ "attempt_count = CASE WHEN attempt_count > 0 THEN attempt_count - 1 ELSE 0 END, "
				+ "available_at = ?, locked_by = NULL, locked_at = NULL, finished_at = NULL, "
				+ "error_code = ?, error_message = 'Waiting for a refreshed author-list snapshot', "
				+ "error_detail = NULL, updated_at = ? "
				+ "WHERE id = ? AND queue_generation = ? AND process_state = 'RUNNING' AND locked_by = ?",
				retryAt, SNAPSHOT_PENDING, timestamp, claim.id(), QUEUE_GENERATION, claim.lockToken());
		requireTransition(updated, claim.id(), "RETRY_WAIT");
		jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state = 'RETRY_WAIT', available_at = ?, "
				+ "error_code = ?, error_message = 'Waiting for a refreshed author-list snapshot', "
				+ "error_detail = NULL, updated_at = ? "
				+ "WHERE queue_generation = ? AND (metadata_snapshot IS NULL OR TRIM(metadata_snapshot) = '') "
				+ "AND process_state IN ('QUEUED','RETRY_WAIT') AND run_id IN "
				+ "(SELECT id FROM biz_collect_run WHERE collect_task_id = ?)",
				retryAt, SNAPSHOT_PENDING, timestamp, QUEUE_GENERATION, claim.taskId());
		jdbcTemplate.update("UPDATE biz_collect_data SET backfill_cursor = '0', backfill_complete = 0, "
				+ "backfill_verifying = 1, backfill_clean_passes = 0, backfill_verified_at = NULL WHERE id = ?",
				claim.taskId());
		Integer pending = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_collect_run_item "
				+ "WHERE queue_generation = ? AND (metadata_snapshot IS NULL OR TRIM(metadata_snapshot) = '') "
				+ "AND error_code = ? "
				+ "AND process_state IN ('QUEUED','RETRY_WAIT','RUNNING') AND run_id IN "
				+ "(SELECT id FROM biz_collect_run WHERE collect_task_id = ?)", Integer.class,
				QUEUE_GENERATION, SNAPSHOT_PENDING, claim.taskId());
		return pending == null ? 0 : pending;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean retryAfterSnapshotHydration(CollectDownloadClaim claim, Instant now) {
		if (claim.metadataSnapshot() != null && !claim.metadataSnapshot().isBlank()) return false;
		Timestamp timestamp = Timestamp.from(now);
		return jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state = 'QUEUED', attempt_count = 0, "
				+ "available_at = ?, locked_by = NULL, locked_at = NULL, finished_at = NULL, "
				+ "error_code = NULL, error_message = NULL, error_detail = NULL, updated_at = ? "
				+ "WHERE id = ? AND queue_generation = ? AND process_state = 'RUNNING' AND locked_by = ? "
				+ "AND metadata_snapshot IS NOT NULL AND TRIM(metadata_snapshot) <> ''",
				timestamp, timestamp, claim.id(), QUEUE_GENERATION, claim.lockToken()) == 1;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(CollectDownloadClaim claim, IngestResult result, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		WorkMetadata metadata = result.metadata();
		boolean graphic = metadata.getContentType() != WorkContentType.VIDEO;
		String mediaType = graphic ? "image" : "video";
		String status = graphic ? "图文已完成" : "已完成";
		String source = valueOr(metadata.getOriginalAddress(),
				valueOr(metadata.getSourceUrl(), "https://www.douyin.com/video/" + claim.workId()));
		String processState = result.persistence().created() ? "COMPLETED" : "SKIPPED_EXISTING";
		String processLog = "download item completed; runId=" + claim.runId() + "; persistenceId="
				+ result.persistence().id() + "; created=" + result.persistence().created();
		List<Long> existing = jdbcTemplate.queryForList("SELECT id FROM biz_collect_data_detail "
				+ "WHERE dataid = ? AND videoid = ? ORDER BY id ASC LIMIT 1", Long.class,
				claim.taskId(), claim.workId());
		if (existing.isEmpty()) {
			jdbcTemplate.update("INSERT INTO biz_collect_data_detail "
					+ "(dataid, videoid, videoname, originaladdress, status, mediatype, processlog, "
					+ "errorcode, errormsg, createtime) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
					claim.taskId(), claim.workId(), valueOr(metadata.getTitle(), claim.workId()), source, status,
					mediaType, processLog, now.toString());
		} else {
			jdbcTemplate.update("UPDATE biz_collect_data_detail SET videoname = ?, originaladdress = ?, status = ?, "
					+ "mediatype = ?, processlog = ?, errorcode = NULL, errormsg = NULL "
					+ "WHERE dataid = ? AND videoid = ?",
					valueOr(metadata.getTitle(), claim.workId()), source, status, mediaType, processLog,
					claim.taskId(), claim.workId());
		}
		int updated = jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state = ?, "
				+ "available_at = NULL, locked_by = NULL, locked_at = NULL, finished_at = ?, "
				+ "error_code = NULL, error_message = NULL, error_detail = NULL, metadata_snapshot = NULL, updated_at = ? "
				+ "WHERE id = ? AND queue_generation = ? AND process_state = 'RUNNING' AND locked_by = ?",
				processState, timestamp, timestamp, claim.id(), QUEUE_GENERATION, claim.lockToken());
		requireTransition(updated, claim.id(), processState);
		Integer completed = jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT videoid) FROM biz_collect_data_detail "
				+ "WHERE dataid = ? AND (status LIKE '已完成%' OR status = '图文已完成')", Integer.class,
				claim.taskId());
		jdbcTemplate.update("UPDATE biz_collect_data SET carriedout = ? WHERE id = ?",
				String.valueOf(completed == null ? 0 : completed), claim.taskId());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean manualRetry(long itemId, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		return jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state = 'QUEUED', "
				+ "decision = CASE WHEN decision LIKE '%AUDIT_REPAIR%' "
				+ "THEN 'MANUAL_RETRY_AUDIT_REPAIR' ELSE 'MANUAL_RETRY' END, "
				+ "attempt_count = 0, available_at = ?, locked_by = NULL, "
				+ "locked_at = NULL, finished_at = NULL, updated_at = ? "
				+ "WHERE id = ? AND queue_generation = ? AND process_state = 'FAILED'",
				timestamp, timestamp, itemId, QUEUE_GENERATION) == 1;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int retryFailedRun(long runId, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		return jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state = 'QUEUED', "
				+ "decision = CASE WHEN decision LIKE '%AUDIT_REPAIR%' "
				+ "THEN 'MANUAL_RETRY_AUDIT_REPAIR' ELSE 'MANUAL_RETRY' END, "
				+ "attempt_count = 0, available_at = ?, locked_by = NULL, "
				+ "locked_at = NULL, finished_at = NULL, updated_at = ? "
				+ "WHERE run_id = ? AND queue_generation = ? AND process_state = 'FAILED'",
				timestamp, timestamp, runId, QUEUE_GENERATION);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int recoverStale(Instant staleBefore, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		Timestamp staleTimestamp = Timestamp.from(staleBefore);
		int exhausted = jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state = 'FAILED', "
				+ "available_at = NULL, locked_by = NULL, locked_at = NULL, finished_at = ?, "
				+ "error_code = 'WORKER_RESTART_RECOVERY', "
				+ "error_message = 'Download worker stopped after exhausting all attempts', updated_at = ? "
				+ "WHERE queue_generation = ? AND process_state = 'RUNNING' "
				+ "AND attempt_count >= max_attempts AND (locked_at IS NULL OR locked_at < ?)",
				timestamp, timestamp, QUEUE_GENERATION, staleTimestamp);
		int retryable = jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state = 'RETRY_WAIT', "
				+ "available_at = ?, locked_by = NULL, locked_at = NULL, finished_at = NULL, "
				+ "error_code = 'WORKER_RESTART_RECOVERY', "
				+ "error_message = 'Download worker stopped before completing this item', updated_at = ? "
				+ "WHERE queue_generation = ? AND process_state = 'RUNNING' "
				+ "AND attempt_count < max_attempts AND (locked_at IS NULL OR locked_at < ?)",
				timestamp, timestamp, QUEUE_GENERATION, staleTimestamp);
		return exhausted + retryable;
	}

	private Duration retryDelay(int completedAttempt) {
		int index = Math.max(0, Math.min(completedAttempt - 1, RETRY_DELAYS.size() - 1));
		return RETRY_DELAYS.get(index);
	}

	private void requireTransition(int updated, long itemId, String nextState) {
		if (updated != 1) {
			throw new IllegalStateException("Collection download item " + itemId
					+ " was not RUNNING during transition to " + nextState);
		}
	}

	private String truncate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) return value;
		int end = maxLength;
		if (Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) {
			end--;
		}
		return value.substring(0, end);
	}

	private String valueOr(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private record DownloadCandidate(long id, long runId, int taskId, String taskName, String platformKey,
			String workId, String mediaType, String decision, int ordinal, int attemptCount, int maxAttempts,
			String metadataSnapshot) {
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int manualRetryItems(List<Long> itemIds, Instant now) {
		if (itemIds == null || itemIds.isEmpty()) return 0;
		Timestamp ts = Timestamp.from(now);
		int changed = 0;
		for (Long id : itemIds) {
			if (id == null) continue;
			changed += jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state='QUEUED', "
					+ "decision=CASE WHEN decision LIKE '%AUDIT_REPAIR%' THEN 'MANUAL_RETRY_AUDIT_REPAIR' ELSE 'MANUAL_RETRY' END, "
					+ "attempt_count=0, available_at=?, locked_by=NULL, locked_at=NULL, finished_at=NULL, "
					+ "error_code=NULL, error_message=NULL, error_detail=NULL, updated_at=? "
					+ "WHERE id=? AND queue_generation=? AND process_state IN ('FAILED','RETRY_WAIT','QUEUED')",
				ts, ts, id, QUEUE_GENERATION);
		}
		return changed;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int moveToRetry(List<Long> itemIds, Instant availableAt, Instant now) {
		if (itemIds == null || itemIds.isEmpty()) return 0;
		Timestamp ts = Timestamp.from(now), retryAt = Timestamp.from(availableAt);
		int changed = 0;
		for (Long id : itemIds) {
			if (id == null) continue;
			changed += jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state='RETRY_WAIT', available_at=?, "
					+ "locked_by=NULL, locked_at=NULL, finished_at=NULL, error_code='MANUAL_RETRY_WAIT', "
					+ "error_message='用户手动移入等待重试', error_detail=NULL, updated_at=? WHERE id=? "
					+ "AND queue_generation=? AND process_state IN ('FAILED','RETRY_WAIT','QUEUED')",
					retryAt, ts, id, QUEUE_GENERATION);
		}
		return changed;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int markFailed(List<Long> itemIds, String reason, Instant now) {
		if (itemIds == null || itemIds.isEmpty()) return 0;
		Timestamp ts = Timestamp.from(now);
		int changed = 0;
		for (Long id : itemIds) {
			if (id == null) continue;
			changed += jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state='FAILED', available_at=NULL, "
					+ "locked_by=NULL, locked_at=NULL, finished_at=?, error_code='MANUAL_FAILED', error_message=?, "
					+ "error_detail=NULL, updated_at=? WHERE id=? AND queue_generation=? "
					+ "AND process_state IN ('QUEUED','RETRY_WAIT','FAILED')", ts, truncate(reason, 2048), ts, id, QUEUE_GENERATION);
		}
		return changed;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int markRemoteMissing(List<Long> itemIds, Instant now) {
		if (itemIds == null || itemIds.isEmpty()) return 0;
		Timestamp ts = Timestamp.from(now);
		int changed = 0;
		for (Long id : itemIds) {
			if (id == null) continue;
			changed += jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state='SKIPPED_REMOTE_MISSING', "
					+ "available_at=NULL, locked_by=NULL, locked_at=NULL, finished_at=?, "
					+ "error_code='REMOTE_LIST_MISSING', error_message='用户确认：作品可能已被服务器端删除', "
					+ "metadata_snapshot=NULL, updated_at=? WHERE id=? AND queue_generation=? "
					+ "AND error_code='LIST_SNAPSHOT_PENDING' AND process_state IN ('QUEUED','RETRY_WAIT')",
				ts, ts, id, QUEUE_GENERATION);
		}
		return changed;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int cancel(List<Long> itemIds, Instant now) {
		if (itemIds == null || itemIds.isEmpty()) return 0;
		Timestamp ts = Timestamp.from(now);
		int changed = 0;
		for (Long id : itemIds) {
			if (id == null) continue;
			changed += jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state='CANCELLED', available_at=NULL, "
					+ "locked_by=NULL, locked_at=NULL, finished_at=?, error_code='CANCELLED_BY_USER', "
					+ "error_message='用户取消下载队列', error_detail=NULL, updated_at=? WHERE id=? AND queue_generation=? "
					+ "AND process_state IN ('QUEUED','RETRY_WAIT','FAILED')", ts, ts, id, QUEUE_GENERATION);
		}
		return changed;
	}
}
