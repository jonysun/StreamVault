package com.flower.spirit.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CollectRunQueryService {

	private final JdbcTemplate jdbcTemplate;

	public CollectRunQueryService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<Map<String, Object>> findRuns(int taskId, int limit, long afterId) {
		int safeLimit = Math.min(Math.max(limit, 1), 100);
		return jdbcTemplate.queryForList("SELECT id AS runId, collect_task_id AS taskId, trigger_type AS triggerType, "
				+ "state, requested_limit AS requestedLimit, fetched_count AS fetchedCount, "
				+ "planned_count AS plannedCount, inserted_count AS insertedCount, "
				+ "skipped_existing_count AS skippedExistingCount, failed_item_count AS failedItemCount, "
				+ "created_at AS queuedAt, started_at AS startedAt, heartbeat_at AS heartbeatAt, "
				+ "finished_at AS finishedAt, error_code AS errorCode, error_message AS errorMessage "
				+ "FROM biz_collect_run WHERE collect_task_id = ? AND (? = 0 OR id < ?) "
				+ "ORDER BY id DESC LIMIT " + safeLimit, taskId, afterId, afterId).stream()
				.map(this::withStateLabel).toList();
	}

	public Map<String, Object> findRun(long runId) {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT r.id AS runId, r.collect_task_id AS taskId, "
				+ "t.taskname AS taskName, r.trigger_type AS triggerType, r.state, r.requested_limit AS requestedLimit, "
				+ "r.fetched_count AS fetchedCount, r.planned_count AS plannedCount, r.inserted_count AS insertedCount, "
				+ "r.skipped_existing_count AS skippedExistingCount, r.failed_item_count AS failedItemCount, "
				+ "r.created_at AS queuedAt, r.started_at AS startedAt, r.heartbeat_at AS heartbeatAt, "
				+ "r.finished_at AS finishedAt, r.error_code AS errorCode, r.error_message AS errorMessage, "
				+ "r.error_detail AS errorDetail FROM biz_collect_run r "
				+ "LEFT JOIN biz_collect_data t ON t.id = r.collect_task_id WHERE r.id = ?", runId);
		if (rows.isEmpty()) return Map.of();
		Map<String, Object> result = withStateLabel(rows.get(0));
		Map<String, Object> error = new HashMap<>();
		error.put("code", result.get("errorCode"));
		error.put("message", result.get("errorMessage"));
		error.put("detail", result.get("errorDetail"));
		result.put("error", error);
		return result;
	}

	public List<Map<String, Object>> findItems(long runId, String decision, int limit, long afterId) {
		int safeLimit = Math.min(Math.max(limit, 1), 500);
		String sql = "SELECT id, ordinal, platform_key AS platformKey, work_id AS workId, author_uid AS authorUid, "
				+ "nickname_snapshot AS nicknameSnapshot, title_snapshot AS titleSnapshot, publish_time AS publishTime, "
				+ "media_type AS mediaType, decision, process_state AS processState, error_code AS errorCode, "
				+ "error_message AS errorMessage, created_at AS createdAt, updated_at AS updatedAt "
				+ "FROM biz_collect_run_item WHERE run_id = ? AND id > ?";
		if (decision != null && !decision.isBlank() && !"all".equalsIgnoreCase(decision)) sql += " AND decision = ?";
		sql += " ORDER BY ordinal ASC, id ASC LIMIT " + safeLimit;
		return decision != null && !decision.isBlank() && !"all".equalsIgnoreCase(decision)
				? jdbcTemplate.queryForList(sql, runId, afterId, decision)
				: jdbcTemplate.queryForList(sql, runId, afterId);
	}

	public List<Map<String, Object>> findEvents(long runId, int afterSequence, int limit) {
		int safeLimit = Math.min(Math.max(limit, 1), 500);
		return jdbcTemplate.queryForList("SELECT sequence, level, stage, event_code AS eventCode, message, work_id AS workId, "
				+ "created_at AS createdAt FROM biz_collect_run_event WHERE run_id = ? AND sequence > ? "
				+ "ORDER BY sequence ASC LIMIT " + safeLimit, runId, afterSequence);
	}

	public Map<String, Object> requeuePreview(long runId, boolean paused) {
		Map<String, Object> result = new HashMap<>();
		Map<String, Object> run = findRun(runId);
		if (run.isEmpty()) return Map.of("exists", false);
		String state = String.valueOf(run.get("state"));
		boolean terminal = List.of("FETCH_FAILED", "DB_FAILED", "INTERRUPTED", "CANCELLED").contains(state);
		Integer taskId = ((Number) run.get("taskId")).intValue();
		boolean active = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_job_queue "
				+ "WHERE job_type = 'COLLECT_FETCH' AND dedupe_key = ? AND state IN ('QUEUED','RUNNING','RETRY_WAIT')",
				Integer.class, "collect:" + taskId) > 0;
		result.put("exists", true);
		result.put("runId", runId);
		result.put("taskId", taskId);
		result.put("terminal", terminal);
		result.put("hasActiveJob", active);
		result.put("paused", paused);
		result.put("canRequeue", terminal && !active && !paused);
		result.put("scope", "重新抓取整次运行");
		return result;
	}

	private Map<String, Object> withStateLabel(Map<String, Object> source) {
		Map<String, Object> result = new HashMap<>(source);
		result.put("stateLabel", stateLabel(String.valueOf(source.get("state"))));
		return result;
	}

	private String stateLabel(String state) {
		try {
			return switch (CollectRunState.valueOf(state)) {
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
		} catch (IllegalArgumentException error) {
			return state;
		}
	}
}
