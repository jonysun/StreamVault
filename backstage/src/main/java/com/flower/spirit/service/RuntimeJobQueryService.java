package com.flower.spirit.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
		List<Object> parameters = new ArrayList<>(requested);
		String sql = "SELECT q.id AS jobId, q.job_type AS jobType, q.dedupe_key AS dedupeKey, q.state, "
				+ "q.priority, q.available_at AS availableAt, q.attempt_count AS attemptCount, "
				+ "q.max_attempts AS maxAttempts, q.locked_by AS lockedBy, q.locked_at AS lockedAt, "
				+ "q.last_error_code AS errorCode, q.last_error_message AS errorMessage, "
				+ "q.created_at AS createdAt, q.updated_at AS updatedAt, "
				+ "CAST(SUBSTR(q.dedupe_key, 9) AS INTEGER) AS taskId, t.taskname AS taskName, "
				+ "r.id AS runId, r.state AS runState, r.fetched_count AS fetchedCount, "
				+ "r.planned_count AS plannedCount, r.inserted_count AS insertedCount, "
				+ "r.failed_item_count AS failedItemCount, r.heartbeat_at AS heartbeatAt "
				+ "FROM biz_job_queue q "
				+ "LEFT JOIN biz_collect_data t ON t.id = CAST(SUBSTR(q.dedupe_key, 9) AS INTEGER) "
				+ "LEFT JOIN biz_collect_run r ON r.id = (SELECT rr.id FROM biz_collect_run rr "
				+ "WHERE rr.collect_task_id = t.id ORDER BY rr.id DESC LIMIT 1) "
				+ "WHERE q.state IN (" + placeholders + ") ORDER BY q.priority ASC, q.available_at ASC, q.id ASC "
				+ "LIMIT " + safeLimit;
		return jdbcTemplate.queryForList(sql, parameters.toArray());
	}

	private List<String> parseStates(String states) {
		List<String> result = Arrays.stream((states == null ? "RUNNING,QUEUED" : states).split(","))
				.map(String::trim).filter(value -> !value.isEmpty()).map(value -> value.toUpperCase(Locale.ROOT))
				.filter(ALLOWED_STATES::contains).distinct().toList();
		return result.isEmpty() ? List.of("RUNNING", "QUEUED") : result;
	}
}
