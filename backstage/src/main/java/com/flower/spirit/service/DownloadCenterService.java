package com.flower.spirit.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.database.DatabaseWriteExecutor;

@Service
public class DownloadCenterService {

	private static final Set<String> ACTIVE_STATES = Set.of("QUEUED", "RUNNING", "RETRY_WAIT");
	private static final Set<String> HISTORY_STATES = Set.of("COMPLETED", "FAILED", "CANCELLED",
			"SKIPPED_EXISTING", "SKIPPED_BLOCKED", "SKIPPED_REMOTE_MISSING", "SKIPPED_EXISTING_ACTIVE_DOWNLOAD");
	private static final Set<String> SOURCES = Set.of("ALL", "COLLECT", "SINGLE_LINK", "YOUTUBE_COLLECTION");
	private final JdbcTemplate jdbcTemplate;
	private final DirectDownloadQueueService directDownloadQueueService;
	private final CollectRunService collectRunService;
	private final RuntimeControlService runtimeControlService;
	private final DatabaseWriteExecutor databaseWriteExecutor;
	@org.springframework.beans.factory.annotation.Autowired(required = false)
	private BlockedWorkService blockedWorkService;

	public DownloadCenterService(JdbcTemplate jdbcTemplate, DirectDownloadQueueService directDownloadQueueService,
			CollectRunService collectRunService, RuntimeControlService runtimeControlService,
			DatabaseWriteExecutor databaseWriteExecutor) {
		this.jdbcTemplate = jdbcTemplate;
		this.directDownloadQueueService = directDownloadQueueService;
		this.collectRunService = collectRunService;
		this.runtimeControlService = runtimeControlService;
		this.databaseWriteExecutor = databaseWriteExecutor;
	}

	public Map<String, Object> summary() {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String state : List.of("RUNNING", "QUEUED", "RETRY_WAIT", "FAILED", "COMPLETED")) counts.put(state, 0L);
		mergeCounts(counts, "SELECT process_state AS state, COUNT(*) AS item_count FROM biz_collect_run_item "
				+ "WHERE queue_generation='FETCH_DOWNLOAD_V1' GROUP BY process_state");
		mergeCounts(counts, "SELECT state, COUNT(*) AS item_count FROM biz_job_queue WHERE job_type='DIRECT_DOWNLOAD' "
				+ "GROUP BY state");
		Instant start = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
		Long collectToday = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_collect_run_item WHERE "
				+ "queue_generation='FETCH_DOWNLOAD_V1' AND process_state='COMPLETED' AND finished_at>=?", Long.class,
				Timestamp.from(start));
		Long directToday = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_job_queue WHERE "
				+ "job_type='DIRECT_DOWNLOAD' AND state='COMPLETED' AND updated_at>=?", Long.class,
				Timestamp.from(start));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("counts", counts);
		result.put("todayCompleted", number(collectToday) + number(directToday));
		result.put("paused", runtimeControlService.snapshot().effectiveDownloadPaused());
		return result;
	}

	public Map<String, Object> items(String view, String source, String state, String keyword, int page,
			int pageSize) {
		boolean active = !"history".equalsIgnoreCase(view);
		String normalizedSource = normalizeSource(source);
		String normalizedState = normalizeState(state, active);
		int safePage = Math.max(0, page);
		int safeSize = Math.min(Math.max(1, pageSize), 100);
		List<Object> args = new ArrayList<>();
		List<String> branches = new ArrayList<>();
		if ("ALL".equals(normalizedSource) || "COLLECT".equals(normalizedSource)) {
			branches.add(collectBranch(active, normalizedState, keyword, args));
		}
		if (!"COLLECT".equals(normalizedSource)) {
			branches.add(directBranch(active, normalizedSource, normalizedState, keyword, args));
		}
		String union = String.join(" UNION ALL ", branches);
		long total = countUnion(union, args);
		String order = active
				? " ORDER BY CASE state WHEN 'RUNNING' THEN 0 WHEN 'QUEUED' THEN 1 ELSE 2 END, available_at ASC, raw_id ASC"
				: " ORDER BY sort_at DESC, raw_id DESC";
		List<Object> pagedArgs = new ArrayList<>(args);
		pagedArgs.add(safeSize);
		pagedArgs.add((long) safePage * safeSize);
		List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM (" + union + ") download_items"
				+ order + " LIMIT ? OFFSET ?", pagedArgs.toArray());
		for (Map<String, Object> row : rows) enrich(row);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("content", rows);
		result.put("page", safePage);
		result.put("pageSize", safeSize);
		result.put("totalElements", total);
		result.put("totalPages", total == 0 ? 0 : (total + safeSize - 1) / safeSize);
		return result;
	}

	public boolean retry(String recordKey) {
		RecordKey key = parseKey(recordKey);
		if ("DIRECT".equals(key.source())) return directDownloadQueueService.retry(key.id());
		if ("COLLECT".equals(key.source())) {
			if (isBlockedCollectionItem(key.id())) return false;
			collectRunService.retryDownloadItem(key.id());
			return true;
		}
		return false;
	}

	public int retry(List<String> recordKeys) {
		if (recordKeys == null) return 0;
		int retried = 0;
		for (String recordKey : recordKeys.stream().distinct().limit(200).toList()) {
			try {
				if (retry(recordKey)) retried++;
			} catch (IllegalArgumentException ignored) {
				// The item may have changed state between selection and submission.
			}
		}
		return retried;
	}

	private boolean isBlockedCollectionItem(long itemId) {
		if (blockedWorkService == null) return false;
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"SELECT platform_key, work_id FROM biz_collect_run_item WHERE id=? AND queue_generation='FETCH_DOWNLOAD_V1'", itemId);
		if (rows.isEmpty()) return false;
		Map<String, Object> row = rows.get(0);
		return blockedWorkService.isBlocked(String.valueOf(row.get("platform_key")),
				String.valueOf(row.get("work_id")), "video");
	}

	/** Remove selected collection records from the visible queue and reuse the existing work blacklist. */
	public int deleteAndBlock(List<String> recordKeys) {
		if (recordKeys == null || recordKeys.isEmpty()) return 0;
		return databaseWriteExecutor.execute("download-delete-and-block", () -> {
			int changed = 0;
			for (String value : recordKeys.stream().filter(java.util.Objects::nonNull).distinct().limit(200).toList()) {
				RecordKey key = parseKey(value);
				if (!"COLLECT".equals(key.source())) continue;
				List<Map<String, Object>> rows = jdbcTemplate.queryForList(
						"SELECT platform_key, work_id, title_snapshot, nickname_snapshot FROM biz_collect_run_item "
						+ "WHERE id=? AND queue_generation='FETCH_DOWNLOAD_V1'", key.id());
				if (rows.isEmpty()) continue;
				Map<String, Object> row = rows.get(0);
				String platform = String.valueOf(row.getOrDefault("platform_key", ""));
				String workId = String.valueOf(row.getOrDefault("work_id", ""));
				if (platform.isBlank() || workId.isBlank()) continue;
				if (blockedWorkService != null) {
					blockedWorkService.blockWork(platform, workId, "video",
							String.valueOf(row.getOrDefault("title_snapshot", "")),
							String.valueOf(row.getOrDefault("nickname_snapshot", "")), null,
							"douyin".equalsIgnoreCase(platform) ? "https://www.douyin.com/video/" + workId : null,
							"用户从下载中心删除并拉黑");
				}
				changed += jdbcTemplate.update("UPDATE biz_collect_run_item SET process_state='SKIPPED_BLOCKED', "
						+ "available_at=NULL, locked_by=NULL, locked_at=NULL, finished_at=CURRENT_TIMESTAMP, "
						+ "error_code='WORK_BLOCKED', error_message='用户从下载中心删除并拉黑', updated_at=CURRENT_TIMESTAMP "
						+ "WHERE queue_generation='FETCH_DOWNLOAD_V1' AND platform_key=? AND work_id=? "
						+ "AND process_state IN ('QUEUED','RETRY_WAIT','FAILED')", platform, workId);
				changed += jdbcTemplate.update("INSERT INTO biz_download_history_hidden(record_key, source_type, source_id, hidden_at) "
						+ "VALUES (?, 'COLLECT', ?, CURRENT_TIMESTAMP) ON CONFLICT(record_key) DO NOTHING", value, key.id());
			}
			return changed;
		});
	}

	public int hideHistory(List<String> recordKeys) {
		if (recordKeys == null) return 0;
		return databaseWriteExecutor.execute("download-history-hide", () -> {
			int hidden = 0;
			for (String value : recordKeys.stream().distinct().limit(200).toList()) {
				RecordKey key = parseKey(value);
				if (!Set.of("DIRECT", "COLLECT").contains(key.source())) continue;
				if (!isTerminal(key)) continue;
				hidden += jdbcTemplate.update("INSERT INTO biz_download_history_hidden "
						+ "(record_key, source_type, source_id, hidden_at) VALUES (?, ?, ?, ?) "
						+ "ON CONFLICT(record_key) DO NOTHING", value, key.source(), key.id(),
						Timestamp.from(Instant.now()));
			}
			return hidden;
		});
	}

	private boolean isTerminal(RecordKey key) {
		String sql;
		if ("DIRECT".equals(key.source())) {
			sql = "SELECT COUNT(*) FROM biz_job_queue WHERE id=? AND job_type='DIRECT_DOWNLOAD' "
					+ "AND state IN ('COMPLETED','FAILED','CANCELLED')";
		} else {
			sql = "SELECT COUNT(*) FROM biz_collect_run_item WHERE id=? AND queue_generation='FETCH_DOWNLOAD_V1' "
					+ "AND process_state IN ('COMPLETED','FAILED','CANCELLED','SKIPPED_EXISTING','SKIPPED_BLOCKED','SKIPPED_REMOTE_MISSING',"
					+ "'SKIPPED_EXISTING_ACTIVE_DOWNLOAD')";
		}
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, key.id());
		return count != null && count > 0;
	}

	private String collectBranch(boolean active, String state, String keyword, List<Object> args) {
		StringBuilder sql = new StringBuilder("SELECT 'COLLECT:' || CAST(i.id AS VARCHAR) AS record_key, "
				+ "'COLLECT' AS source_kind, i.id AS raw_id, COALESCE(t.taskname,'') AS task_name, "
				+ "COALESCE(i.title_snapshot,'') AS title, COALESCE(i.nickname_snapshot,'') AS author, "
				+ "COALESCE(i.platform_key,'') AS platform, COALESCE(i.work_id,'') AS work_id, "
				+ "CASE WHEN LOWER(COALESCE(i.platform_key,''))='douyin' AND COALESCE(i.work_id,'')<>'' "
				+ "THEN 'https://www.douyin.com/video/' || i.work_id ELSE '' END AS source_url, "
				+ "i.process_state AS state, i.attempt_count AS attempt_count, i.max_attempts AS max_attempts, "
				+ "i.available_at AS available_at, i.started_at AS started_at, i.finished_at AS completed_at, "
				+ "COALESCE(i.error_code,'') AS error_code, COALESCE(i.error_message,'') AS error_message, "
				+ "i.created_at AS created_at, '' AS payload, COALESCE(i.finished_at,i.updated_at) AS sort_at "
				+ "FROM biz_collect_run_item i JOIN biz_collect_run r ON r.id=i.run_id "
				+ "LEFT JOIN biz_collect_data t ON t.id=r.collect_task_id WHERE i.queue_generation='FETCH_DOWNLOAD_V1' "
				+ "AND NOT EXISTS (SELECT 1 FROM biz_download_history_hidden h WHERE h.record_key="
				+ "'COLLECT:' || CAST(i.id AS VARCHAR))");
		appendState(sql, "i.process_state", active, state, args);
		if (keyword != null && !keyword.isBlank()) {
			sql.append(" AND LOWER(COALESCE(t.taskname,'') || ' ' || COALESCE(i.title_snapshot,'') || ' ' || "
					+ "COALESCE(i.nickname_snapshot,'') || ' ' || COALESCE(i.work_id,'')) LIKE ?");
			args.add("%" + keyword.trim().toLowerCase(Locale.ROOT) + "%");
		}
		return sql.toString();
	}

	private String directBranch(boolean active, String source, String state, String keyword, List<Object> args) {
		StringBuilder sql = new StringBuilder("SELECT 'DIRECT:' || CAST(q.id AS VARCHAR) AS record_key, "
				+ "'DIRECT' AS source_kind, q.id AS raw_id, '' AS task_name, '' AS title, '' AS author, "
				+ "'' AS platform, '' AS work_id, '' AS source_url, q.state AS state, "
				+ "q.attempt_count AS attempt_count, q.max_attempts AS max_attempts, q.available_at AS available_at, "
				+ "q.locked_at AS started_at, CASE WHEN q.state IN ('COMPLETED','FAILED','CANCELLED') THEN q.updated_at "
				+ "ELSE NULL END AS completed_at, COALESCE(q.last_error_code,'') AS error_code, "
				+ "COALESCE(q.last_error_message,'') AS error_message, q.created_at AS created_at, q.payload AS payload, "
				+ "q.updated_at AS sort_at FROM biz_job_queue q WHERE q.job_type='DIRECT_DOWNLOAD' "
				+ "AND NOT EXISTS (SELECT 1 FROM biz_download_history_hidden h WHERE h.record_key="
				+ "'DIRECT:' || CAST(q.id AS VARCHAR))");
		appendState(sql, "q.state", active, state, args);
		if (!"ALL".equals(source)) {
			sql.append(" AND q.payload LIKE ?");
			args.add("%\"sourceType\":\"" + source + "\"%");
		}
		if (keyword != null && !keyword.isBlank()) {
			sql.append(" AND LOWER(q.payload) LIKE ?");
			args.add("%" + keyword.trim().toLowerCase(Locale.ROOT) + "%");
		}
		return sql.toString();
	}

	private void appendState(StringBuilder sql, String column, boolean active, String state, List<Object> args) {
		if (state != null) {
			sql.append(" AND ").append(column).append("=?");
			args.add(state);
			return;
		}
		Set<String> states = active ? ACTIVE_STATES : HISTORY_STATES;
		sql.append(" AND ").append(column).append(" IN (")
				.append(String.join(",", states.stream().map(value -> "'" + value + "'").toList())).append(")");
	}

	private long countUnion(String union, List<Object> args) {
		Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM (" + union + ") download_count", Long.class,
				args.toArray());
		return number(value);
	}

	private void enrich(Map<String, Object> row) {
		if (!"DIRECT".equals(String.valueOf(row.get("source_kind")))) {
			row.put("sourceType", "COLLECT");
			return;
		}
		try {
			JSONObject payload = JSONObject.parseObject(String.valueOf(row.get("payload")));
			row.put("title", payload.getString("title"));
			row.put("author", payload.getString("author"));
			row.put("platform", payload.getString("platformName"));
			row.put("source_url", payload.getString("sourceUrl"));
			row.put("sourceType", DirectDownloadSource.from(payload.getString("sourceType")).name());
			row.put("batchId", payload.getString("batchId"));
		} catch (RuntimeException error) {
			row.put("sourceType", "SINGLE_LINK");
		}
		row.remove("payload");
	}

	private void mergeCounts(Map<String, Long> target, String sql) {
		for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) {
			String state = String.valueOf(row.get("state"));
			Object count = row.get("item_count");
			target.merge(state, count instanceof Number value ? value.longValue() : 0L, Long::sum);
		}
	}

	private String normalizeSource(String source) {
		String value = source == null ? "ALL" : source.trim().toUpperCase(Locale.ROOT);
		return SOURCES.contains(value) ? value : "ALL";
	}

	private String normalizeState(String state, boolean active) {
		if (state == null || state.isBlank() || "ALL".equalsIgnoreCase(state)) return null;
		String value = state.trim().toUpperCase(Locale.ROOT);
		return (active ? ACTIVE_STATES : HISTORY_STATES).contains(value) ? value : null;
	}

	private RecordKey parseKey(String value) {
		if (value == null || !value.matches("(DIRECT|COLLECT):[0-9]+")) {
			throw new IllegalArgumentException("Invalid download record key");
		}
		int separator = value.indexOf(':');
		return new RecordKey(value.substring(0, separator), Long.parseLong(value.substring(separator + 1)));
	}

	private long number(Number value) {
		return value == null ? 0L : value.longValue();
	}

	private record RecordKey(String source, long id) {
	}
}
