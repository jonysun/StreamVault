package com.flower.spirit.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;

@Service
public class RuntimeJobQueryService {

	private static final Set<String> ALLOWED_STATES = Set.of(
			"QUEUED", "RUNNING", "RETRY_WAIT", "COMPLETED", "FAILED", "CANCELLED");
	private final JdbcTemplate jdbcTemplate;

	public RuntimeJobQueryService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Map<String, Object> dashboard(int limit) {
		int safeLimit = Math.min(Math.max(limit, 1), 100);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("running", findJobs("RUNNING", safeLimit));
		result.put("queued", findJobs("QUEUED,RETRY_WAIT", safeLimit));
		result.put("recentFailed", findJobs("FAILED", Math.min(safeLimit, 20)));
		result.put("fetchQueue", queueCounts("biz_job_queue", "job_type = 'COLLECT_FETCH' "
				+ "AND state IN ('QUEUED','RUNNING','RETRY_WAIT')", "state"));
		result.put("downloadQueue", queueCounts("biz_collect_run_item",
				"queue_generation = 'FETCH_DOWNLOAD_V1' "
				+ "AND process_state IN ('QUEUED','RUNNING','RETRY_WAIT')", "process_state"));
		result.put("downloadTasks", downloadTaskProgress(safeLimit));
		return result;
	}

	private Map<String, Long> queueCounts(String table, String filter, String stateColumn) {
		Map<String, Long> result = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT " + stateColumn
				+ " AS state, COUNT(*) AS itemCount FROM " + table + " WHERE " + filter + " GROUP BY " + stateColumn)) {
			Object count = row.get("itemCount");
			result.put(String.valueOf(row.get("state")), count instanceof Number n ? n.longValue() : 0L);
		}
		return result;
	}

	public List<Map<String, Object>> findJobs(String states, int limit) {
		int safeLimit = Math.min(Math.max(limit, 1), 200);
		List<String> requested = parseStates(states);
		String placeholders = requested.stream().map(value -> "?").collect(Collectors.joining(","));
		String sql = "SELECT q.id, q.job_type, q.dedupe_key, q.payload, q.state, q.priority, q.available_at, "
				+ "q.attempt_count, q.max_attempts, q.locked_by, q.locked_at, q.last_error_code, "
				+ "q.last_error_message, q.created_at, q.updated_at FROM biz_job_queue q "
				+ "WHERE q.state IN (" + placeholders + ") "
				+ "ORDER BY q.priority ASC, q.available_at ASC, q.id ASC LIMIT " + safeLimit;
		List<Map<String, Object>> jobs = jdbcTemplate.query(sql, (rs, rowNum) -> mapJob(rs), requested.toArray());
		enrichJobs(jobs);
		return jobs;
	}

	private Map<String, Object> mapJob(ResultSet rs) throws SQLException {
		Map<String, Object> job = new LinkedHashMap<>();
		job.put("jobId", rs.getLong("id"));
		job.put("jobType", rs.getString("job_type"));
		job.put("dedupeKey", rs.getString("dedupe_key"));
		job.put("state", rs.getString("state"));
		job.put("priority", rs.getObject("priority"));
		job.put("availableAt", rs.getObject("available_at"));
		job.put("attemptCount", rs.getObject("attempt_count"));
		job.put("maxAttempts", rs.getObject("max_attempts"));
		job.put("lockedBy", rs.getString("locked_by"));
		job.put("lockedAt", rs.getObject("locked_at"));
		job.put("errorCode", rs.getString("last_error_code"));
		job.put("errorMessage", rs.getString("last_error_message"));
		job.put("createdAt", rs.getObject("created_at"));
		job.put("updatedAt", rs.getObject("updated_at"));
		JSONObject payload = parsePayload(rs.getString("payload"));
		Integer taskId = payload == null ? null : payload.getInteger("taskId");
		Long runId = payload == null ? null : payload.getLong("runId");
		if (taskId == null) taskId = taskIdFromDedupe(rs.getString("dedupe_key"));
		job.put("taskId", taskId);
		job.put("runId", runId);
		return job;
	}

	private void enrichJobs(List<Map<String, Object>> jobs) {
		Set<Integer> taskIds = new LinkedHashSet<>();
		Set<Long> runIds = new LinkedHashSet<>();
		for (Map<String, Object> job : jobs) {
			if (job.get("taskId") instanceof Number value) taskIds.add(value.intValue());
			if (job.get("runId") instanceof Number value) runIds.add(value.longValue());
		}
		Map<Integer, String> taskNames = taskNames(taskIds);
		Map<Long, Map<String, Object>> runs = runs(runIds);
		for (Map<String, Object> job : jobs) {
			Number taskId = (Number) job.get("taskId");
			job.put("taskName", taskId == null ? null : taskNames.get(taskId.intValue()));
			Number runId = (Number) job.get("runId");
			Map<String, Object> run = runId == null ? null : runs.get(runId.longValue());
			job.put("runState", value(run, "runState"));
			job.put("fetchedCount", value(run, "fetchedCount"));
			job.put("plannedCount", value(run, "plannedCount"));
			job.put("insertedCount", value(run, "insertedCount"));
			job.put("failedItemCount", value(run, "failedItemCount"));
			job.put("heartbeatAt", value(run, "heartbeatAt"));
		}
	}

	private Map<Integer, String> taskNames(Set<Integer> ids) {
		if (ids.isEmpty()) return Map.of();
		String placeholders = ids.stream().map(value -> "?").collect(Collectors.joining(","));
		Map<Integer, String> result = new LinkedHashMap<>();
		jdbcTemplate.query("SELECT id, taskname FROM biz_collect_data WHERE id IN (" + placeholders + ")",
				rs -> { result.put(rs.getInt("id"), rs.getString("taskname")); }, ids.toArray());
		return result;
	}

	private Map<Long, Map<String, Object>> runs(Set<Long> ids) {
		if (ids.isEmpty()) return Map.of();
		String placeholders = ids.stream().map(value -> "?").collect(Collectors.joining(","));
		Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
		jdbcTemplate.query("SELECT id, state, fetched_count, planned_count, inserted_count, failed_item_count, "
				+ "heartbeat_at FROM biz_collect_run WHERE id IN (" + placeholders + ")", rs -> {
			Map<String, Object> run = new LinkedHashMap<>();
			run.put("runState", rs.getString("state"));
			run.put("fetchedCount", rs.getObject("fetched_count"));
			run.put("plannedCount", rs.getObject("planned_count"));
			run.put("insertedCount", rs.getObject("inserted_count"));
			run.put("failedItemCount", rs.getObject("failed_item_count"));
			run.put("heartbeatAt", rs.getObject("heartbeat_at"));
			result.put(rs.getLong("id"), run);
		}, ids.toArray());
		return result;
	}

	private List<Map<String, Object>> downloadTaskProgress(int limit) {
		String sql = "WITH active_runs AS (SELECT i.run_id FROM biz_collect_run_item i "
				+ "WHERE i.queue_generation = 'FETCH_DOWNLOAD_V1' "
				+ "AND i.process_state IN ('QUEUED','RUNNING','RETRY_WAIT') "
				+ "GROUP BY i.run_id ORDER BY i.run_id DESC LIMIT " + limit + ") "
				+ "SELECT r.collect_task_id, t.taskname, i.run_id, MAX(r.planned_count) AS planned_count, "
				+ "SUM(CASE WHEN i.process_state = 'COMPLETED' THEN 1 ELSE 0 END) AS completed_count, "
				+ "SUM(CASE WHEN i.process_state = 'RUNNING' THEN 1 ELSE 0 END) AS running_count, "
				+ "SUM(CASE WHEN i.process_state = 'QUEUED' THEN 1 ELSE 0 END) AS queued_count, "
				+ "SUM(CASE WHEN i.process_state = 'RETRY_WAIT' THEN 1 ELSE 0 END) AS retry_wait_count, "
				+ "SUM(CASE WHEN i.process_state LIKE 'SKIPPED_%' THEN 1 ELSE 0 END) AS skipped_count, "
				+ "SUM(CASE WHEN i.process_state = 'FAILED' THEN 1 ELSE 0 END) AS failed_count "
				+ "FROM active_runs a JOIN biz_collect_run_item i ON i.run_id = a.run_id "
				+ "AND i.queue_generation = 'FETCH_DOWNLOAD_V1' "
				+ "JOIN biz_collect_run r ON r.id = i.run_id "
				+ "LEFT JOIN biz_collect_data t ON t.id = r.collect_task_id "
				+ "GROUP BY r.collect_task_id, t.taskname, i.run_id "
				+ "ORDER BY i.run_id DESC";
		return jdbcTemplate.query(sql, (rs, rowNum) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("taskId", rs.getInt("collect_task_id"));
			row.put("taskName", rs.getString("taskname"));
			row.put("runId", rs.getLong("run_id"));
			row.put("plannedCount", number(rs, "planned_count"));
			row.put("completedCount", number(rs, "completed_count"));
			row.put("runningCount", number(rs, "running_count"));
			row.put("queuedCount", number(rs, "queued_count"));
			row.put("retryWaitCount", number(rs, "retry_wait_count"));
			row.put("skippedCount", number(rs, "skipped_count"));
			row.put("failedCount", number(rs, "failed_count"));
			return row;
		});
	}

	private long number(ResultSet rs, String column) throws SQLException {
		Number value = (Number) rs.getObject(column);
		return value == null ? 0L : value.longValue();
	}

	private Object value(Map<String, Object> map, String key) {
		return map == null ? null : map.get(key);
	}

	private JSONObject parsePayload(String value) {
		if (value == null || value.isBlank()) return null;
		try {
			return JSONObject.parseObject(value);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private Integer taskIdFromDedupe(String value) {
		if (value == null || !value.startsWith("collect:")) return null;
		try {
			return Integer.valueOf(value.substring("collect:".length()));
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private List<String> parseStates(String states) {
		List<String> result = Arrays.stream((states == null ? "RUNNING,QUEUED" : states).split(","))
				.map(String::trim).filter(value -> !value.isEmpty()).map(value -> value.toUpperCase(Locale.ROOT))
				.filter(ALLOWED_STATES::contains).distinct().toList();
		return result.isEmpty() ? List.of("RUNNING", "QUEUED") : result;
	}
}
