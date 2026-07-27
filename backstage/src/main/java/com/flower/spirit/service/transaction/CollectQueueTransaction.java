package com.flower.spirit.service.transaction;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.service.CollectEnqueueResult;
import com.flower.spirit.service.CollectJobClaim;
import com.flower.spirit.service.CollectRunFetchedItem;
import com.flower.spirit.service.CollectRunState;
import com.flower.spirit.service.CollectTriggerType;
import com.flower.spirit.service.IllegalCollectRunTransitionException;
import com.flower.spirit.service.JobState;
import com.flower.spirit.service.JobType;

@Service
public class CollectQueueTransaction {

	private static final String ACTIVE_JOB_STATES = "'QUEUED','RUNNING','RETRY_WAIT'";
	private final JdbcTemplate jdbcTemplate;
	@Value("${streamvault.collect.download-max-retries:3}")
	private int downloadMaxRetries;

	public CollectQueueTransaction(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public CollectEnqueueResult enqueue(int taskId, CollectTriggerType triggerType, Integer requestedLimit,
			Instant availableAt, int priority, int maxAttempts) {
		String dedupeKey = dedupeKey(taskId);
		List<CollectEnqueueResult> existing = jdbcTemplate.query(
				"SELECT id, payload, state FROM biz_job_queue WHERE job_type = ? AND dedupe_key = ? "
						+ "AND state IN (" + ACTIVE_JOB_STATES + ") ORDER BY id DESC LIMIT 1",
				(rs, rowNum) -> {
					JSONObject payload = JSONObject.parseObject(rs.getString("payload"));
					return new CollectEnqueueResult(payload.getLongValue("runId"), rs.getLong("id"),
							CollectRunState.QUEUED, false, false);
				}, JobType.COLLECT_FETCH.name(), dedupeKey);
		if (!existing.isEmpty()) {
			CollectEnqueueResult active = existing.get(0);
			return new CollectEnqueueResult(active.runId(), active.jobId(), currentRunState(active.runId()), false,
					false);
		}

		long runId = insertRun(taskId, triggerType, requestedLimit, CollectRunState.QUEUED, availableAt);
		JSONObject payload = payload(taskId, runId, triggerType);
		long jobId = insertJob(dedupeKey, payload.toJSONString(), availableAt, priority, maxAttempts);
		jdbcTemplate.update("UPDATE biz_collect_data SET taskstatus = ? WHERE id = ?", "排队中", taskId);
		appendEvent(runId, "INFO", "QUEUE", "RUN_QUEUED", "收藏运行已进入持久队列", null, availableAt);
		return new CollectEnqueueResult(runId, jobId, CollectRunState.QUEUED, true, false);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public CollectEnqueueResult recordSkipped(int taskId, CollectTriggerType triggerType, Integer requestedLimit,
			String reason, Instant now) {
		long runId = insertRun(taskId, triggerType, requestedLimit, CollectRunState.SKIPPED_PAUSED, now);
		jdbcTemplate.update("UPDATE biz_collect_run SET finished_at = ?, error_code = ?, error_message = ? WHERE id = ?",
				Timestamp.from(now), "COLLECT_PAUSED", truncate(reason, 2048), runId);
		jdbcTemplate.update("UPDATE biz_collect_data SET taskstatus = ? WHERE id = ?", "暂停期间已跳过", taskId);
		appendEvent(runId, "INFO", "QUEUE", "SKIPPED_PAUSED", reason, null, now);
		return new CollectEnqueueResult(runId, null, CollectRunState.SKIPPED_PAUSED, true, true);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public CollectJobClaim claimNext(String workerId, Instant now) {
		List<JobRow> due = jdbcTemplate.query(
				"SELECT id, payload, attempt_count, max_attempts FROM biz_job_queue "
						+ "WHERE job_type = ? AND state IN ('QUEUED','RETRY_WAIT') AND available_at <= ? "
						+ "ORDER BY priority ASC, available_at ASC, id ASC LIMIT 1",
				(rs, rowNum) -> new JobRow(rs.getLong("id"), rs.getString("payload"),
						rs.getInt("attempt_count"), rs.getInt("max_attempts")),
				JobType.COLLECT_FETCH.name(), Timestamp.from(now));
		if (due.isEmpty()) {
			return null;
		}
		JobRow candidate = due.get(0);
		int updated = jdbcTemplate.update("UPDATE biz_job_queue SET state = 'RUNNING', "
				+ "attempt_count = attempt_count + 1, locked_by = ?, locked_at = ?, updated_at = ? "
				+ "WHERE id = ? AND state IN ('QUEUED','RETRY_WAIT')", workerId, Timestamp.from(now),
				Timestamp.from(now), candidate.id());
		if (updated != 1) {
			return null;
		}
		JSONObject payload = JSONObject.parseObject(candidate.payload());
		return new CollectJobClaim(candidate.id(), payload.getLongValue("runId"), payload.getIntValue("taskId"),
				CollectTriggerType.valueOf(payload.getString("triggerType")), candidate.attemptCount() + 1,
				candidate.maxAttempts());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void transition(long runId, CollectRunState expected, CollectRunState next, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		int updated = jdbcTemplate.update("UPDATE biz_collect_run SET state = ?, "
				+ "started_at = CASE WHEN ? = 'FETCHING' AND started_at IS NULL THEN ? ELSE started_at END, "
				+ "heartbeat_at = ? WHERE id = ? AND state = ?", next.name(), next.name(), timestamp, timestamp,
				runId, expected.name());
		if (updated != 1) {
			throw new IllegalCollectRunTransitionException(runId, expected, next);
		}
		Integer taskId = taskId(runId);
		jdbcTemplate.update("UPDATE biz_collect_data SET taskstatus = ? WHERE id = ?", displayLabel(next), taskId);
		appendEvent(runId, "INFO", next.name(), "STATE_TRANSITION", expected + " -> " + next, null, now);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void storeFetchedItems(long runId, List<CollectRunFetchedItem> items, Instant now) {
		storeFetchPlan(runId, taskId(runId), items, "LEGACY_FETCH",
				new CollectRunFetchedItem.FetchWatermark(null, null, 0, 0, ""), now);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void storeFetchPlan(long runId, int taskId, List<CollectRunFetchedItem> items, String stopReason,
			CollectRunFetchedItem.FetchWatermark watermark, Instant now) {
		Integer runTaskId = taskId(runId);
		if (runTaskId == null || runTaskId != taskId) {
			throw new IllegalArgumentException("collection run does not belong to task: runId=" + runId
					+ " taskId=" + taskId);
		}
		Timestamp timestamp = Timestamp.from(now);
		transitionInCurrentTransaction(runId, CollectRunState.FETCHING, CollectRunState.PROCESSING, now);
		appendEvent(runId, "INFO", "PROCESSING", "STATE_TRANSITION", "FETCHING -> PROCESSING", null, now);
		for (CollectRunFetchedItem item : items) {
			boolean queued = "QUEUED".equals(item.processState());
			boolean activeElsewhere = queued && hasActiveDownload(runId, item.platformKey(), item.workId());
			String decision = activeElsewhere ? "EXISTING_ACTIVE_DOWNLOAD" : valueOr(item.decision(), "EXISTING");
			String processState = activeElsewhere ? "SKIPPED_EXISTING_ACTIVE_DOWNLOAD"
					: valueOr(item.processState(), "SKIPPED_EXISTING");
			boolean claimable = "QUEUED".equals(processState);
			jdbcTemplate.update("INSERT INTO biz_collect_run_item "
					+ "(run_id, ordinal, platform_key, work_id, author_uid, nickname_snapshot, title_snapshot, "
					+ "publish_time, media_type, decision, process_state, attempt_count, max_attempts, available_at, "
					+ "queue_generation, created_at, updated_at) "
					+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)", runId, item.ordinal(),
					item.platformKey(), item.workId(), item.authorUid(), item.nickname(), truncate(item.title(), 2000),
					item.publishTime(), item.mediaType(), decision, processState,
					claimable ? Math.max(1, downloadMaxRetries + 1) : 0,
					claimable ? timestamp : null, claimable ? "FETCH_DOWNLOAD_V1" : null, timestamp, timestamp);
		}
		CollectRunFetchedItem.FetchWatermark safeWatermark = watermark == null
				? new CollectRunFetchedItem.FetchWatermark(null, null, 0, 0, "") : watermark;
		String incomingPublishTime = blankToNull(safeWatermark.publishTime());
		jdbcTemplate.update("UPDATE biz_collect_data SET last_successful_fetch_at = ?, "
				+ "last_seen_publish_time = CASE WHEN ? IS NOT NULL AND (last_seen_publish_time IS NULL "
				+ "OR CAST(? AS INTEGER) >= CAST(last_seen_publish_time AS INTEGER)) THEN ? "
				+ "ELSE last_seen_publish_time END, "
				+ "last_seen_work_id = CASE WHEN ? IS NOT NULL AND (last_seen_publish_time IS NULL "
				+ "OR CAST(? AS INTEGER) >= CAST(last_seen_publish_time AS INTEGER)) THEN ? "
				+ "ELSE last_seen_work_id END WHERE id = ?", timestamp,
				incomingPublishTime, incomingPublishTime, incomingPublishTime,
				incomingPublishTime, incomingPublishTime, blankToNull(safeWatermark.workId()), taskId);
		jdbcTemplate.update("UPDATE biz_collect_run SET fetched_count = ?, fetch_stop_reason = ?, fetch_warning = ?, "
				+ "heartbeat_at = ? WHERE id = ? AND state = 'PROCESSING'", items.size(), stopReason,
				warningFor(stopReason), timestamp, runId);
		appendEvent(runId, warningFor(stopReason) == null ? "INFO" : "WARN", "FETCH", "FETCH_STOP",
				"outcome=" + valueOr(stopReason, "UNKNOWN") + ", pages=" + safeWatermark.pagesFetched()
						+ ", emptyPages=" + safeWatermark.emptyPages() + ", cursor="
						+ valueOr(safeWatermark.lastCursor(), ""), null, now);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateItem(long runId, String workId, String decision, String processState, String errorCode,
			String errorMessage, Instant now) {
		int updated = jdbcTemplate.update("UPDATE biz_collect_run_item SET decision = ?, process_state = ?, "
				+ "error_code = ?, error_message = ?, updated_at = ? WHERE run_id = ? AND work_id = ?",
				valueOr(decision, "UNKNOWN"), valueOr(processState, "COMPLETED"), errorCode,
				truncate(errorMessage, 2048), Timestamp.from(now), runId, workId);
		if (updated == 0) {
			appendEvent(runId, "WARN", "PROCESS", "ITEM_NOT_IN_FETCH_LIST",
					"处理结果无法匹配抓取明细", workId, now);
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void heartbeat(long runId, Instant now) {
		jdbcTemplate.update("UPDATE biz_collect_run SET heartbeat_at = ? WHERE id = ? "
				+ "AND state IN ('FETCHING','PROCESSING')", Timestamp.from(now), runId);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void skipClaimed(CollectJobClaim claim, String reason, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		int updated = jdbcTemplate.update("UPDATE biz_collect_run SET state = 'SKIPPED_PAUSED', finished_at = ?, "
				+ "heartbeat_at = ?, error_code = 'TASK_PAUSED', error_message = ? "
				+ "WHERE id = ? AND state = 'QUEUED'", timestamp, timestamp, truncate(reason, 2048), claim.runId());
		if (updated != 1) {
			throw new IllegalCollectRunTransitionException(claim.runId(), CollectRunState.QUEUED,
					CollectRunState.SKIPPED_PAUSED);
		}
		jdbcTemplate.update("UPDATE biz_job_queue SET state = 'COMPLETED', locked_by = NULL, locked_at = NULL, "
				+ "updated_at = ? WHERE id = ? AND state = 'RUNNING'", timestamp, claim.jobId());
		jdbcTemplate.update("UPDATE biz_collect_data SET taskstatus = ? WHERE id = ?", "暂停期间已跳过", claim.taskId());
		appendEvent(claim.runId(), "INFO", "QUEUE", "SKIPPED_PAUSED", reason, null, now);
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public CollectRunState currentState(long runId) {
		return currentRunState(runId);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(long runId, long jobId, Instant now) {
		RunCounts counts = jdbcTemplate.queryForObject("SELECT COUNT(*) AS fetched, "
				+ "SUM(CASE WHEN process_state IN ('QUEUED','RUNNING','RETRY_WAIT','COMPLETED') THEN 1 ELSE 0 END) AS planned, "
				+ "SUM(CASE WHEN process_state IN ('SKIPPED_EXISTING','SKIPPED_EXISTING_ACTIVE_DOWNLOAD') THEN 1 ELSE 0 END) AS skipped, "
				+ "SUM(CASE WHEN process_state = 'FAILED' THEN 1 ELSE 0 END) AS failed "
				+ "FROM biz_collect_run_item WHERE run_id = ?", (rs, rowNum) -> new RunCounts(
					rs.getInt("fetched"), rs.getInt("planned"), 0, rs.getInt("skipped"),
					rs.getInt("failed")), runId);
		Timestamp timestamp = Timestamp.from(now);
		int updated = jdbcTemplate.update("UPDATE biz_collect_run SET state = 'COMPLETED', fetched_count = ?, "
				+ "planned_count = ?, inserted_count = ?, skipped_existing_count = ?, failed_item_count = ?, "
				+ "heartbeat_at = ?, finished_at = ? WHERE id = ? AND state = 'PROCESSING'", counts.fetched(),
				counts.planned(), counts.inserted(), counts.skipped(), counts.failed(), timestamp, timestamp, runId);
		if (updated != 1) {
			throw new IllegalCollectRunTransitionException(runId, CollectRunState.PROCESSING, CollectRunState.COMPLETED);
		}
		Integer taskId = taskId(runId);
		String taskStatus = "抓取完成，下载排队 " + counts.planned();
		jdbcTemplate.update("UPDATE biz_collect_data SET taskstatus = ?, count = ?, endtime = ? WHERE id = ?",
				taskStatus, String.valueOf(counts.fetched()), timestamp.toString(), taskId);
		jdbcTemplate.update("UPDATE biz_job_queue SET state = 'COMPLETED', locked_by = NULL, locked_at = NULL, "
				+ "updated_at = ? WHERE id = ? AND state = 'RUNNING'", timestamp, jobId);
		appendEvent(runId, "INFO", "COMPLETE", "RUN_COMPLETED",
				"抓取完成，观察 " + counts.fetched() + "，下载排队 " + counts.planned()
						+ "，跳过 " + counts.skipped() + "，失败 " + counts.failed(),
				null, now);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void failRun(long runId, CollectRunState expected, CollectRunState failedState, String errorCode,
			String message, String detail, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		int updated = jdbcTemplate.update("UPDATE biz_collect_run SET state = ?, finished_at = ?, heartbeat_at = ?, "
				+ "error_code = ?, error_message = ?, error_detail = ? WHERE id = ? AND state = ?",
				failedState.name(), timestamp, timestamp, errorCode, truncate(message, 2048), truncate(detail, 10000),
				runId, expected.name());
		if (updated != 1) {
			throw new IllegalCollectRunTransitionException(runId, expected, failedState);
		}
		jdbcTemplate.update("UPDATE biz_collect_data SET taskstatus = ?, endtime = ? WHERE id = ?",
				displayLabel(failedState), timestamp.toString(), taskId(runId));
		appendEvent(runId, "ERROR", failedState.name(), errorCode, valueOr(message, errorCode), null, now);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public CollectEnqueueResult retryOrFailJob(CollectJobClaim claim, String errorCode, String message,
			Instant nextAttemptAt, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		if (claim.attemptCount() >= claim.maxAttempts()) {
			jdbcTemplate.update("UPDATE biz_job_queue SET state = 'FAILED', locked_by = NULL, locked_at = NULL, "
					+ "last_error_code = ?, last_error_message = ?, updated_at = ? WHERE id = ? AND state = 'RUNNING'",
					errorCode, truncate(message, 2048), timestamp, claim.jobId());
			return new CollectEnqueueResult(claim.runId(), claim.jobId(), currentRunState(claim.runId()), false, false);
		}
		Integer requestedLimit = jdbcTemplate.queryForObject(
				"SELECT requested_limit FROM biz_collect_run WHERE id = ?", Integer.class, claim.runId());
		long nextRunId = insertRun(claim.taskId(), CollectTriggerType.RETRY, requestedLimit,
				CollectRunState.QUEUED, now);
		JSONObject payload = payload(claim.taskId(), nextRunId, CollectTriggerType.RETRY);
		int updated = jdbcTemplate.update("UPDATE biz_job_queue SET payload = ?, state = 'RETRY_WAIT', "
				+ "available_at = ?, locked_by = NULL, locked_at = NULL, last_error_code = ?, "
				+ "last_error_message = ?, updated_at = ? WHERE id = ? AND state = 'RUNNING'", payload.toJSONString(),
				Timestamp.from(nextAttemptAt), errorCode, truncate(message, 2048), timestamp, claim.jobId());
		if (updated != 1) {
			throw new IllegalStateException("Collect job " + claim.jobId() + " was not RUNNING during retry");
		}
		jdbcTemplate.update("UPDATE biz_collect_data SET taskstatus = ? WHERE id = ?", "等待重试", claim.taskId());
		appendEvent(nextRunId, "WARN", "QUEUE", "RETRY_SCHEDULED",
				"上次失败：" + valueOr(message, errorCode), null, now);
		return new CollectEnqueueResult(nextRunId, claim.jobId(), CollectRunState.QUEUED, true, false);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int recoverStale(Instant staleBefore, Instant now) {
		List<JobRow> staleJobs = jdbcTemplate.query("SELECT id, payload, attempt_count, max_attempts "
				+ "FROM biz_job_queue WHERE job_type = ? AND state = 'RUNNING' AND (locked_at IS NULL OR locked_at < ?)",
				(rs, rowNum) -> new JobRow(rs.getLong("id"), rs.getString("payload"), rs.getInt("attempt_count"),
						rs.getInt("max_attempts")), JobType.COLLECT_FETCH.name(), Timestamp.from(staleBefore));
		for (JobRow job : staleJobs) {
			JSONObject payload = JSONObject.parseObject(job.payload());
			long runId = payload.getLongValue("runId");
			int taskId = payload.getIntValue("taskId");
			CollectRunState previousState = currentRunState(runId);
			if (previousState == CollectRunState.COMPLETED || previousState == CollectRunState.SKIPPED_PAUSED
					|| previousState == CollectRunState.CANCELLED) {
				jdbcTemplate.update("UPDATE biz_job_queue SET state = 'COMPLETED', locked_by = NULL, locked_at = NULL, "
						+ "updated_at = ? WHERE id = ?", Timestamp.from(now), job.id());
				continue;
			}
			jdbcTemplate.update("UPDATE biz_collect_run SET state = 'INTERRUPTED', finished_at = ?, "
					+ "error_code = 'PROCESS_RESTART', error_message = '应用重启前运行未正常结束' "
					+ "WHERE id = ? AND state IN ('QUEUED','FETCHING','PROCESSING')", Timestamp.from(now), runId);
			if (job.attemptCount() >= job.maxAttempts()) {
				jdbcTemplate.update("UPDATE biz_job_queue SET state = 'FAILED', locked_by = NULL, locked_at = NULL, "
						+ "last_error_code = 'PROCESS_RESTART', updated_at = ? WHERE id = ?", Timestamp.from(now), job.id());
				jdbcTemplate.update("UPDATE biz_collect_data SET taskstatus = '执行中断' WHERE id = ?", taskId);
				continue;
			}
			Integer requestedLimit = jdbcTemplate.queryForObject(
					"SELECT requested_limit FROM biz_collect_run WHERE id = ?", Integer.class, runId);
			long retryRunId = insertRun(taskId, CollectTriggerType.RETRY, requestedLimit, CollectRunState.QUEUED, now);
			JSONObject retryPayload = payload(taskId, retryRunId, CollectTriggerType.RETRY);
			jdbcTemplate.update("UPDATE biz_job_queue SET payload = ?, state = 'QUEUED', available_at = ?, "
					+ "locked_by = NULL, locked_at = NULL, last_error_code = 'PROCESS_RESTART', updated_at = ? "
					+ "WHERE id = ?", retryPayload.toJSONString(), Timestamp.from(now), Timestamp.from(now), job.id());
			jdbcTemplate.update("UPDATE biz_collect_data SET taskstatus = '排队中' WHERE id = ?", taskId);
		}
		return staleJobs.size();
	}

	private long insertRun(int taskId, CollectTriggerType triggerType, Integer requestedLimit,
			CollectRunState state, Instant now) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO biz_collect_run (collect_task_id, trigger_type, state, requested_limit, "
							+ "fetched_count, planned_count, inserted_count, skipped_existing_count, "
							+ "failed_item_count, created_at) VALUES (?, ?, ?, ?, 0, 0, 0, 0, 0, ?)",
					Statement.RETURN_GENERATED_KEYS);
			statement.setInt(1, taskId);
			statement.setString(2, triggerType.name());
			statement.setString(3, state.name());
			if (requestedLimit == null) statement.setNull(4, java.sql.Types.INTEGER);
			else statement.setInt(4, requestedLimit);
			statement.setTimestamp(5, Timestamp.from(now));
			return statement;
		}, keys);
		return requiredKey(keys, "collect run");
	}

	private long insertJob(String dedupeKey, String payload, Instant availableAt, int priority, int maxAttempts) {
		KeyHolder keys = new GeneratedKeyHolder();
		Timestamp timestamp = Timestamp.from(availableAt);
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO biz_job_queue (job_type, dedupe_key, payload, state, priority, available_at, "
							+ "attempt_count, max_attempts, created_at, updated_at) "
							+ "VALUES (?, ?, ?, 'QUEUED', ?, ?, 0, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
			statement.setString(1, JobType.COLLECT_FETCH.name());
			statement.setString(2, dedupeKey);
			statement.setString(3, payload);
			statement.setInt(4, priority);
			statement.setTimestamp(5, timestamp);
			statement.setInt(6, Math.max(1, maxAttempts));
			statement.setTimestamp(7, timestamp);
			statement.setTimestamp(8, timestamp);
			return statement;
		}, keys);
		return requiredKey(keys, "collect job");
	}

	private void transitionInCurrentTransaction(long runId, CollectRunState expected, CollectRunState next,
			Instant now) {
		int updated = jdbcTemplate.update("UPDATE biz_collect_run SET state = ?, heartbeat_at = ? "
				+ "WHERE id = ? AND state = ?", next.name(), Timestamp.from(now), runId, expected.name());
		if (updated != 1) {
			throw new IllegalCollectRunTransitionException(runId, expected, next);
		}
		jdbcTemplate.update("UPDATE biz_collect_data SET taskstatus = ? WHERE id = ?", displayLabel(next), taskId(runId));
	}

	private void appendEvent(long runId, String level, String stage, String eventCode, String message,
			String workId, Instant now) {
		jdbcTemplate.update("INSERT INTO biz_collect_run_event "
				+ "(run_id, sequence, level, stage, event_code, message, work_id, created_at) "
				+ "SELECT ?, COALESCE(MAX(sequence), 0) + 1, ?, ?, ?, ?, ?, ? "
				+ "FROM biz_collect_run_event WHERE run_id = ?", runId, level, stage, eventCode,
				truncate(message, 4000), workId, Timestamp.from(now), runId);
	}

	private Integer taskId(long runId) {
		return jdbcTemplate.queryForObject("SELECT collect_task_id FROM biz_collect_run WHERE id = ?", Integer.class,
				runId);
	}

	private boolean hasActiveDownload(long runId, String platformKey, String workId) {
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_collect_run_item "
				+ "WHERE run_id <> ? AND platform_key = ? AND work_id = ? "
				+ "AND queue_generation = 'FETCH_DOWNLOAD_V1' "
				+ "AND process_state IN ('QUEUED','RUNNING','RETRY_WAIT')", Integer.class,
				runId, platformKey, workId);
		return count != null && count > 0;
	}

	private String warningFor(String stopReason) {
		if (stopReason == null) return null;
		return switch (stopReason) {
		case "NO_PUBLIC_WORKS", "ACCOUNT_DEACTIVATED", "WORKS_UNAVAILABLE", "EMPTY_PAGINATION",
				"MAX_PAGE_GUARD" -> stopReason;
		default -> null;
		};
	}

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private CollectRunState currentRunState(long runId) {
		return CollectRunState.valueOf(jdbcTemplate.queryForObject(
				"SELECT state FROM biz_collect_run WHERE id = ?", String.class, runId));
	}

	private JSONObject payload(int taskId, long runId, CollectTriggerType triggerType) {
		JSONObject payload = new JSONObject();
		payload.put("taskId", taskId);
		payload.put("runId", runId);
		payload.put("triggerType", triggerType.name());
		return payload;
	}

	private long requiredKey(KeyHolder keys, String type) {
		Number key = keys.getKey();
		if (key == null) throw new IllegalStateException("No generated ID returned for " + type);
		return key.longValue();
	}

	private String dedupeKey(int taskId) {
		return "collect:" + taskId;
	}

	private String displayLabel(CollectRunState state) {
		return switch (state) {
		case QUEUED -> "排队中";
		case FETCHING -> "正在抓取";
		case PROCESSING -> "正在入库";
		case COMPLETED -> "已完成";
		case FETCH_FAILED -> "抓取失败";
		case DB_FAILED -> "数据库失败";
		case INTERRUPTED -> "执行中断";
		case SKIPPED_PAUSED -> "暂停期间已跳过";
		case CANCELLED -> "已取消";
		};
	}

	private String truncate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) return value;
		return value.substring(0, maxLength);
	}

	private String valueOr(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private record JobRow(long id, String payload, int attemptCount, int maxAttempts) {
	}

	private record RunCounts(int fetched, int planned, int inserted, int skipped, int failed) {
	}
}
