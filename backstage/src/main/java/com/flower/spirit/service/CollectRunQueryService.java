package com.flower.spirit.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;

@Service
public class CollectRunQueryService {

	private static final Logger logger = LoggerFactory.getLogger(CollectRunQueryService.class);
	private static final long LEGACY_WARNING_INTERVAL_MS = 60 * 60 * 1000L;
	private final JdbcTemplate jdbcTemplate;
	private final SnapshotCodec snapshotCodec;
	private final Map<Integer, Long> legacyWarningTimes = new ConcurrentHashMap<>();
	private MediaPathService mediaPathService;

	public CollectRunQueryService(JdbcTemplate jdbcTemplate, SnapshotCodec snapshotCodec) {
		this.jdbcTemplate = jdbcTemplate;
		this.snapshotCodec = snapshotCodec;
	}

	@Autowired
	void setMediaPathService(MediaPathService mediaPathService) {
		this.mediaPathService = mediaPathService;
	}

	public Set<String> findKnownWorkIds(int taskId) {
		LinkedHashSet<String> result = new LinkedHashSet<>();
		addNonblank(result, jdbcTemplate.queryForList(
				"SELECT videoid FROM biz_collect_data_detail WHERE dataid = ? "
						+ "AND videoid IS NOT NULL AND TRIM(videoid) <> '' ORDER BY id ASC",
				String.class, taskId));
		addNonblank(result, jdbcTemplate.queryForList(
				"SELECT i.work_id FROM biz_collect_run_item i "
						+ "JOIN biz_collect_run r ON r.id = i.run_id "
						+ "WHERE r.collect_task_id = ? AND i.queue_generation = 'FETCH_DOWNLOAD_V1' "
						+ "AND i.process_state IN ('QUEUED','RUNNING','RETRY_WAIT','COMPLETED') "
						+ "AND i.work_id IS NOT NULL AND TRIM(i.work_id) <> '' ORDER BY i.id ASC",
				String.class, taskId));
		return Collections.unmodifiableSet(result);
	}

	public boolean needsAuditRequeue(int taskId, String platformKey, String workId, String mediaType) {
		Integer failedDetails = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM biz_collect_data_detail WHERE dataid = ? AND videoid = ? "
						+ "AND (COALESCE(TRIM(errorcode), '') <> '' OR status LIKE '%失败%')",
				Integer.class, taskId, workId);
		if (failedDetails != null && failedDetails > 0) return true;
		String normalizedType = mediaType == null ? "video" : mediaType.trim().toLowerCase();
		if ("image".equals(normalizedType)) {
			List<String> storedImageSets = jdbcTemplate.queryForList(
					"SELECT images FROM biz_graphic_content WHERE videoid = ? "
							+ "AND (platformkey = ? OR (? = 'douyin' AND platform IN ('抖音', 'douyin'))) "
							+ "AND COALESCE(TRIM(images), '') <> ''",
					String.class, workId, platformKey, platformKey);
			return storedImageSets.stream().noneMatch(this::hasCompleteGraphicFiles);
		}
		List<String> storedVideoPaths = jdbcTemplate.queryForList(
				"SELECT videoaddr FROM biz_video WHERE videoid = ? "
						+ "AND (platformkey = ? OR (? = 'douyin' AND videoplatform IN ('抖音', 'douyin'))) "
						+ "AND COALESCE(TRIM(videoaddr), '') <> ''",
				String.class, workId, platformKey, platformKey);
		return storedVideoPaths.stream().noneMatch(this::isNonEmptyLocalFile);
	}

	private boolean hasCompleteGraphicFiles(String storedImages) {
		try {
			List<String> paths = JSON.parseArray(storedImages, String.class);
			return paths != null && !paths.isEmpty() && paths.stream().allMatch(this::isNonEmptyLocalFile);
		} catch (RuntimeException e) {
			logger.debug("Invalid graphic media paths during collection audit", e);
			return false;
		}
	}

	private boolean isNonEmptyLocalFile(String storedPath) {
		if (storedPath == null || storedPath.isBlank()) return false;
		try {
			Path path = mediaPathService == null
					? Path.of(storedPath.trim()).toAbsolutePath().normalize()
					: mediaPathService.requireOwnedLocalPath(storedPath);
			return Files.isRegularFile(path) && Files.size(path) > 0;
		} catch (Exception e) {
			logger.debug("Unavailable media path during collection audit: {}", storedPath, e);
			return false;
		}
	}

	private void addNonblank(Set<String> target, List<String> values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) target.add(value.trim());
		}
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
				+ "error_message AS errorMessage, error_detail AS errorDetail, attempt_count AS attemptCount, "
				+ "max_attempts AS maxAttempts, available_at AS availableAt, locked_by AS lockedBy, "
				+ "locked_at AS lockedAt, started_at AS startedAt, finished_at AS finishedAt, "
				+ "queue_generation AS queueGeneration, created_at AS createdAt, updated_at AS updatedAt "
				+ "FROM biz_collect_run_item WHERE run_id = ? AND id > ?";
		boolean plan = "plan".equalsIgnoreCase(decision);
		if (plan) sql += " AND (UPPER(decision) = 'NEW' OR UPPER(decision) LIKE '%RETRY%' "
				+ "OR UPPER(decision) LIKE '%AUDIT_REPAIR%')";
		else if (decision != null && !decision.isBlank() && !"all".equalsIgnoreCase(decision)) sql += " AND decision = ?";
		sql += " ORDER BY ordinal ASC, id ASC LIMIT " + safeLimit;
		return !plan && decision != null && !decision.isBlank() && !"all".equalsIgnoreCase(decision)
				? jdbcTemplate.queryForList(sql, runId, afterId, decision)
				: jdbcTemplate.queryForList(sql, runId, afterId);
	}

	public Map<String, Object> downloadQueue(Integer taskId, int limit) {
		int safeLimit = Math.min(Math.max(limit, 1), 200);
		Timestamp now = Timestamp.from(Instant.now());
		String taskFilter = taskId == null ? "" : " AND r.collect_task_id = ?";
		Object[] taskArgs = taskId == null ? new Object[0] : new Object[] { taskId };
		Object[] eligibleArgs = taskId == null ? new Object[] { now } : new Object[] { now, taskId };
		List<Map<String, Object>> countRows = jdbcTemplate.queryForList(
				"SELECT i.process_state AS processState, COUNT(*) AS itemCount FROM biz_collect_run_item i "
						+ "JOIN biz_collect_run r ON r.id = i.run_id WHERE i.queue_generation = 'FETCH_DOWNLOAD_V1'"
						+ " AND i.process_state IN ('QUEUED','RUNNING','RETRY_WAIT')"
						+ taskFilter + " GROUP BY i.process_state", taskArgs);
		Map<String, Long> counts = new LinkedHashMap<>();
		for (Map<String, Object> row : countRows) {
			Object count = row.get("itemCount");
			counts.put(String.valueOf(row.get("processState")), count instanceof Number n ? n.longValue() : 0L);
		}
		String itemsSql = "SELECT i.id AS itemId, i.run_id AS runId, r.collect_task_id AS taskId, "
				+ "t.taskname AS taskName, i.work_id AS workId, i.media_type AS mediaType, i.decision, "
				+ "i.process_state AS processState, i.attempt_count AS attemptCount, i.max_attempts AS maxAttempts, "
				+ "i.available_at AS availableAt, i.locked_by AS lockedBy, i.locked_at AS lockedAt, "
				+ "i.error_code AS errorCode, i.error_message AS errorMessage, i.created_at AS createdAt "
				+ "FROM biz_collect_run_item i JOIN biz_collect_run r ON r.id = i.run_id "
				+ "LEFT JOIN biz_collect_data t ON t.id = r.collect_task_id "
				+ "WHERE i.queue_generation = 'FETCH_DOWNLOAD_V1' "
				+ "AND i.process_state IN ('QUEUED','RETRY_WAIT') "
				+ "AND i.attempt_count < i.max_attempts "
				+ "AND (i.available_at IS NULL OR i.available_at <= ?)" + taskFilter
				+ " ORDER BY CASE WHEN i.decision LIKE 'MANUAL_RETRY%' THEN 0 ELSE 1 END, "
				+ "i.ordinal ASC, i.available_at ASC, i.created_at ASC, i.id ASC LIMIT " + safeLimit;
		List<Map<String, Object>> items = jdbcTemplate.queryForList(itemsSql, eligibleArgs);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("counts", counts);
		result.put("items", items);
		result.put("oldestQueuedAt", scalarTime("SELECT MIN(i.created_at) FROM biz_collect_run_item i "
				+ "JOIN biz_collect_run r ON r.id=i.run_id WHERE i.queue_generation='FETCH_DOWNLOAD_V1' "
				+ "AND i.process_state IN ('QUEUED','RETRY_WAIT') "
				+ "AND i.attempt_count < i.max_attempts "
				+ "AND (i.available_at IS NULL OR i.available_at <= ?)" + taskFilter, eligibleArgs));
		result.put("nextRetryAt", scalarTime("SELECT MIN(i.available_at) FROM biz_collect_run_item i "
				+ "JOIN biz_collect_run r ON r.id=i.run_id WHERE i.queue_generation='FETCH_DOWNLOAD_V1' "
				+ "AND i.process_state='RETRY_WAIT' AND i.attempt_count < i.max_attempts" + taskFilter, taskArgs));
		return result;
	}

	private Object scalarTime(String sql, Object[] args) {
		return jdbcTemplate.queryForObject(sql, Object.class, args);
	}

	public Map<String, Object> findLatestItems(int taskId, String view, int limit, long afterId) {
		int safeLimit = Math.min(Math.max(limit, 1), 500);
		List<Long> runIds = jdbcTemplate.queryForList(
				"SELECT r.id FROM biz_collect_run r WHERE r.collect_task_id = ? "
						+ "AND EXISTS (SELECT 1 FROM biz_collect_run_item i WHERE i.run_id = r.id) "
						+ "ORDER BY r.id DESC LIMIT 1", Long.class, taskId);
		if (!runIds.isEmpty()) {
			long runId = runIds.get(0);
			List<Map<String, Object>> items = findItems(runId, "plan".equalsIgnoreCase(view) ? "plan" : "all",
					safeLimit, afterId);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("source", "run-item");
			result.put("runId", runId);
			result.put("items", items);
			result.put("hasMore", items.size() == safeLimit);
			return result;
		}
		return findLegacyItems(taskId, view, safeLimit, afterId);
	}

	private Map<String, Object> findLegacyItems(int taskId, String view, int limit, long afterId) {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"SELECT lastfetchsnapshot, lastplanitems FROM biz_collect_data WHERE id = ?", taskId);
		if (rows.isEmpty()) return Map.of("source", "none", "items", List.of(), "hasMore", false,
				"warningCode", "TASK_NOT_FOUND", "warningMessage", "收藏任务不存在");
		String column = "plan".equalsIgnoreCase(view) ? "lastplanitems" : "lastfetchsnapshot";
		Object rawValue = rows.get(0).get(column);
		SnapshotReadResult snapshot = snapshotCodec.read(rawValue == null ? null : String.valueOf(rawValue));
		if (!snapshot.available()) warnLegacySnapshot(taskId, snapshot);
		List<SnapshotItem> filtered = snapshot.items().stream()
				.filter(item -> item.ordinal() > afterId)
				.filter(item -> !"plan".equalsIgnoreCase(view) || isLegacyPlanItem(item.decision()))
				.limit(limit).toList();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("source", "legacy-snapshot");
		result.put("version", snapshot.version());
		result.put("items", filtered);
		result.put("totalCount", snapshot.totalCount());
		result.put("storedCount", snapshot.storedCount());
		result.put("truncated", snapshot.truncated());
		result.put("hasMore", filtered.size() == limit);
		if (snapshot.warningCode() != null) {
			result.put("warningCode", snapshot.warningCode());
			result.put("warningMessage", snapshot.warningMessage());
		}
		return result;
	}

	private boolean isLegacyPlanItem(String decision) {
		if (decision == null) return false;
		String value = decision.toLowerCase();
		return value.contains("download") || value.contains("success") || value.contains("retry")
				|| value.contains("repair") || "new".equals(value);
	}

	private void warnLegacySnapshot(int taskId, SnapshotReadResult result) {
		long now = System.currentTimeMillis();
		Long previous = legacyWarningTimes.putIfAbsent(taskId, now);
		if (previous == null || now - previous >= LEGACY_WARNING_INTERVAL_MS) {
			legacyWarningTimes.put(taskId, now);
			logger.warn("[CollectSnapshot] legacy snapshot unavailable taskId={} code={} message={}", taskId,
					result.warningCode(), result.warningMessage());
		}
	}

	public List<Map<String, Object>> findEvents(long runId, int afterSequence, int limit) {
		int safeLimit = Math.min(Math.max(limit, 1), 500);
		return jdbcTemplate.queryForList("SELECT sequence, level, stage, event_code AS eventCode, message, work_id AS workId, "
				+ "created_at AS createdAt FROM biz_collect_run_event WHERE run_id = ? AND sequence > ? "
				+ "ORDER BY sequence ASC LIMIT " + safeLimit, runId, afterSequence);
	}

	public Map<String, Long> latestMediaTotals(int taskId) {
		Map<String, Long> result = new LinkedHashMap<>();
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"SELECT media_type AS mediaType, COUNT(*) AS itemCount FROM biz_collect_run_item "
						+ "WHERE run_id = (SELECT r.id FROM biz_collect_run r WHERE r.collect_task_id = ? "
						+ "AND EXISTS (SELECT 1 FROM biz_collect_run_item i WHERE i.run_id = r.id) "
						+ "ORDER BY r.id DESC LIMIT 1) GROUP BY media_type", taskId);
		for (Map<String, Object> row : rows) {
			String mediaType = String.valueOf(row.get("mediaType"));
			if ("image".equalsIgnoreCase(mediaType)) mediaType = "graphic";
			Object count = row.get("itemCount");
			result.put(mediaType, count instanceof Number number ? number.longValue() : 0L);
		}
		return result;
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
