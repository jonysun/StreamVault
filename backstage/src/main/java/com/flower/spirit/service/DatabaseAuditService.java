package com.flower.spirit.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.flower.spirit.database.DatabaseSchemaInspector;

@Service
public class DatabaseAuditService {

	private static final int DUPLICATE_SAMPLE_LIMIT = 20;
	private static final DateTimeFormatter SQLITE_TIMESTAMP = DateTimeFormatter
			.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

	private final JdbcTemplate jdbcTemplate;
	private final DatabaseSchemaInspector schemaInspector;

	public DatabaseAuditService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
		this.schemaInspector = new DatabaseSchemaInspector(jdbcTemplate.getDataSource());
	}

	public Map<String, Object> audit() {
		Map<String, Object> video = new LinkedHashMap<>(jdbcTemplate.queryForMap(
				"SELECT COUNT(*) AS \"rowsTotal\", "
						+ "SUM(CASE WHEN jsonData IS NOT NULL AND jsonData <> '' THEN 1 ELSE 0 END) AS \"jsonRows\", "
						+ "SUM(CASE WHEN videoinfo IS NOT NULL AND videoinfo <> '' THEN 1 ELSE 0 END) AS \"videoInfoRows\", "
						+ "SUM(CASE WHEN jsonData = videoinfo THEN 1 ELSE 0 END) AS \"exactEqualRows\", "
						+ "SUM(CASE WHEN jsonData = videoinfo THEN LENGTH(videoinfo) ELSE 0 END) AS \"exactDuplicateVideoInfoChars\", "
						+ "SUM(CASE WHEN jsonData IS NOT NULL AND videoinfo IS NOT NULL AND jsonData <> videoinfo THEN 1 ELSE 0 END) AS \"differentRows\", "
						+ "COALESCE(SUM(CASE WHEN COALESCE(jsonData, '') <> '' AND COALESCE(videoinfo, '') = '' THEN 1 ELSE 0 END), 0) AS \"jsonOnlyRows\", "
						+ "COALESCE(SUM(CASE WHEN COALESCE(jsonData, '') = '' AND COALESCE(videoinfo, '') <> '' THEN 1 ELSE 0 END), 0) AS \"videoInfoOnlyRows\", "
						+ "COALESCE(SUM(CASE WHEN COALESCE(jsonData, '') = '' AND COALESCE(videoinfo, '') = '' THEN 1 ELSE 0 END), 0) AS \"emptyRawRows\", "
						+ "SUM(LENGTH(COALESCE(jsonData, ''))) AS \"jsonChars\", "
						+ "SUM(LENGTH(COALESCE(videoinfo, ''))) AS \"videoInfoChars\", MAX(id) AS \"maxId\" FROM biz_video"));
		Map<String, Object> graphic = new LinkedHashMap<>(jdbcTemplate.queryForMap(
				"SELECT COUNT(*) AS \"rowsTotal\", SUM(LENGTH(COALESCE(jsonData, ''))) AS \"jsonChars\", "
						+ "MAX(LENGTH(COALESCE(jsonData, ''))) AS \"maxJsonChars\", MAX(id) AS \"maxId\" FROM biz_graphic_content"));
		Map<String, Object> snapshots = new LinkedHashMap<>(jdbcTemplate.queryForMap(
				"SELECT COUNT(*) AS \"taskRows\", SUM(LENGTH(COALESCE(lastfetchsnapshot, ''))) AS \"fetchChars\", "
						+ "MAX(LENGTH(COALESCE(lastfetchsnapshot, ''))) AS \"fetchMaxChars\", "
						+ "SUM(LENGTH(COALESCE(lastplanitems, ''))) AS \"planChars\", "
						+ "MAX(LENGTH(COALESCE(lastplanitems, ''))) AS \"planMaxChars\", MAX(id) AS \"maxId\" FROM biz_collect_data"));
		Map<String, Object> workDuplicates = new LinkedHashMap<>();
		workDuplicates.put("video", duplicateWorks("biz_video", "videoaddr"));
		workDuplicates.put("graphic", duplicateWorks("biz_graphic_content", "images"));
		Map<String, Object> normalization = normalizationStats();
		Map<String, Object> orphans = orphanStats();
		Map<String, Object> retentionCandidates = retentionCandidates();
		Map<String, Object> storage = storageStats();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("video", video);
		result.put("graphic", graphic);
		result.put("collectSnapshots", snapshots);
		result.put("differentSamples", differenceSamples(10));
		result.put("workDuplicates", workDuplicates);
		result.put("normalization", normalization);
		result.put("orphans", orphans);
		result.put("retentionCandidates", retentionCandidates);
		result.put("storage", storage);
		result.put("fingerprint", fingerprint(video, graphic, snapshots, workDuplicates, normalization, orphans,
				retentionCandidates, storage));
		result.put("notes", isSqlite()
				? List.of("统计长度为逻辑字符数，不等同于 VACUUM 后的物理字节数",
						"在线清理只释放 SQLite 可复用页；缩小文件必须停服后执行 VACUUM INTO")
				: List.of("统计长度为逻辑字符数，不等同于 PostgreSQL 物理存储字节数",
						"PostgreSQL 空间回收由 autovacuum 和数据库维护策略管理"));
		return result;
	}

	private Map<String, Object> duplicateWorks(String table, String mediaReferenceColumn) {
		String eligible = "TRIM(COALESCE(platformkey, '')) <> '' AND TRIM(COALESCE(videoid, '')) <> ''";
		String distinctReferences = "COUNT(DISTINCT CASE WHEN TRIM(COALESCE(" + mediaReferenceColumn
				+ ", '')) <> '' THEN " + mediaReferenceColumn + " END)";
		Map<String, Object> result = new LinkedHashMap<>(jdbcTemplate.queryForMap(
				"SELECT COUNT(*) AS \"candidateGroups\", COALESCE(SUM(\"rowCount\"), 0) AS \"candidateRows\", "
						+ "COALESCE(SUM(CASE WHEN \"distinctMediaReferences\" > 1 THEN 1 ELSE 0 END), 0) "
						+ "AS \"mediaReferenceConflictGroups\" FROM (SELECT COUNT(*) AS \"rowCount\", "
						+ distinctReferences + " AS \"distinctMediaReferences\" FROM " + table + " WHERE " + eligible
						+ " GROUP BY TRIM(platformkey), TRIM(videoid) HAVING COUNT(*) > 1) duplicateGroups"));
		List<Map<String, Object>> samples = jdbcTemplate.query(
				"SELECT TRIM(platformkey) AS \"platformKey\", TRIM(videoid) AS \"workId\", COUNT(*) AS \"rowCount\", "
						+ distinctReferences + " AS \"distinctMediaReferences\" FROM " + table + " WHERE " + eligible
						+ " GROUP BY TRIM(platformkey), TRIM(videoid) HAVING COUNT(*) > 1 "
						+ "ORDER BY TRIM(platformkey), TRIM(videoid) LIMIT " + DUPLICATE_SAMPLE_LIMIT,
				(row, index) -> {
					String platformKey = row.getString("platformKey");
					String workId = row.getString("workId");
					Map<String, Object> sample = new LinkedHashMap<>();
					sample.put("platformKey", platformKey);
					sample.put("workId", workId);
					sample.put("rowCount", row.getLong("rowCount"));
					sample.put("rowIds", jdbcTemplate.queryForList("SELECT id FROM " + table
							+ " WHERE TRIM(platformkey) = ? AND TRIM(videoid) = ? ORDER BY id", Long.class,
							platformKey, workId));
					sample.put("distinctMediaReferences", row.getLong("distinctMediaReferences"));
					return sample;
				});
		result.put("samples", samples);
		return result;
	}

	private Map<String, Object> normalizationStats() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("videoMissingPlatformKeyRows", count(
				"SELECT COUNT(*) FROM biz_video WHERE TRIM(COALESCE(platformkey, '')) = ''"));
		result.put("graphicMissingPlatformKeyRows", count(
				"SELECT COUNT(*) FROM biz_graphic_content WHERE TRIM(COALESCE(platformkey, '')) = ''"));
		result.put("authorMissingPlatformKeyRows", countIfTableExists("biz_author_profile",
				"SELECT COUNT(*) FROM biz_author_profile WHERE TRIM(COALESCE(platformkey, '')) = ''"));
		return result;
	}

	private Map<String, Object> orphanStats() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("runItemsWithoutRun", orphanCount("biz_collect_run_item", "biz_collect_run", "run_id"));
		result.put("runEventsWithoutRun", orphanCount("biz_collect_run_event", "biz_collect_run", "run_id"));
		result.put("authorNameHistoryWithoutProfile",
				orphanCount("biz_author_name_history", "biz_author_profile", "authorprofileid"));
		result.put("collectDetailsWithoutTask",
				orphanCount("biz_collect_data_detail", "biz_collect_data", "dataid"));
		return result;
	}

	private Map<String, Object> retentionCandidates() {
		Map<String, Object> result = new LinkedHashMap<>();
		Instant now = Instant.now();
		result.put("runItems", countIfTableExists("biz_collect_run_item",
				"SELECT COUNT(*) FROM biz_collect_run_item WHERE "
						+ "((UPPER(COALESCE(process_state, '')) = 'FAILED' AND created_at < ?) "
						+ "OR (UPPER(COALESCE(process_state, '')) <> 'FAILED' AND created_at < ?))",
				cutoff(now, RuntimeHistoryRetentionPolicy.FAILED_RUN_ITEM_DAYS),
				cutoff(now, RuntimeHistoryRetentionPolicy.NON_FAILED_RUN_ITEM_DAYS)));
		result.put("terminalRuns", countIfTableExists("biz_collect_run",
				"SELECT COUNT(*) FROM biz_collect_run WHERE UPPER(COALESCE(state, '')) IN "
						+ "('COMPLETED','FETCH_FAILED','DB_FAILED','INTERRUPTED','SKIPPED_PAUSED','CANCELLED') "
						+ "AND created_at < ?", cutoff(now, RuntimeHistoryRetentionPolicy.TERMINAL_HISTORY_DAYS)));
		result.put("runEvents", countIfTableExists("biz_collect_run_event",
				"SELECT COUNT(*) FROM biz_collect_run_event WHERE created_at < ?",
				cutoff(now, RuntimeHistoryRetentionPolicy.TERMINAL_HISTORY_DAYS)));
		result.put("terminalJobs", countIfTableExists("biz_job_queue",
				"SELECT COUNT(*) FROM biz_job_queue WHERE UPPER(COALESCE(state, '')) IN "
						+ "('COMPLETED','FAILED','CANCELLED') AND created_at < ?",
				cutoff(now, RuntimeHistoryRetentionPolicy.TERMINAL_HISTORY_DAYS)));
		result.put("nonFailedRunItemDays", (long) RuntimeHistoryRetentionPolicy.NON_FAILED_RUN_ITEM_DAYS);
		result.put("failedRunItemDays", (long) RuntimeHistoryRetentionPolicy.FAILED_RUN_ITEM_DAYS);
		result.put("terminalHistoryDays", (long) RuntimeHistoryRetentionPolicy.TERMINAL_HISTORY_DAYS);
		return result;
	}

	private Object cutoff(Instant now, int days) {
		Instant value = now.minus(days, ChronoUnit.DAYS);
		return isSqlite() ? SQLITE_TIMESTAMP.format(value) : Timestamp.from(value);
	}

	private long orphanCount(String childTable, String parentTable, String foreignKey) {
		if (!tableExists(childTable)) return 0L;
		if (!tableExists(parentTable)) return count("SELECT COUNT(*) FROM " + childTable);
		return count("SELECT COUNT(*) FROM " + childTable + " child LEFT JOIN " + parentTable
				+ " parent ON parent.id = child." + foreignKey + " WHERE parent.id IS NULL");
	}

	private long countIfTableExists(String table, String sql, Object... parameters) {
		if (!tableExists(table)) return 0L;
		Long value = jdbcTemplate.queryForObject(sql, Long.class, parameters);
		return value == null ? 0L : value;
	}

	private long count(String sql) {
		Long count = jdbcTemplate.queryForObject(sql, Long.class);
		return count == null ? 0L : count;
	}

	private boolean tableExists(String name) {
		return !schemaInspector.columns(name).isEmpty();
	}

	private List<Map<String, Object>> differenceSamples(int limit) {
		return jdbcTemplate.query("SELECT id, jsonData, videoinfo FROM biz_video WHERE jsonData IS NOT NULL "
				+ "AND videoinfo IS NOT NULL AND jsonData <> videoinfo ORDER BY id LIMIT " + limit, (row, index) -> {
			String json = row.getString("jsonData");
			String legacy = row.getString("videoinfo");
			Map<String, Object> sample = new LinkedHashMap<>();
			sample.put("videoId", row.getLong("id"));
			sample.put("jsonDataLength", length(json));
			sample.put("videoInfoLength", length(legacy));
			sample.put("jsonDataHash", "sha256:" + sha256(json));
			sample.put("videoInfoHash", "sha256:" + sha256(legacy));
			sample.put("equal", false);
			return sample;
		});
	}

	private Map<String, Object> storageStats() {
		Map<String, Object> result = new LinkedHashMap<>();
		if (!isSqlite()) {
			Long bytes = jdbcTemplate.queryForObject("SELECT pg_database_size(current_database())", Long.class);
			result.put("databaseBytes", bytes == null ? 0L : bytes);
			result.put("dbstatAvailable", false);
			result.put("objects", List.of());
			return result;
		}
		long pageSize = pragmaLong("page_size");
		long pageCount = pragmaLong("page_count");
		long freePages = pragmaLong("freelist_count");
		result.put("pageSize", pageSize);
		result.put("pageCount", pageCount);
		result.put("freelistPages", freePages);
		result.put("databaseBytes", pageSize * pageCount);
		result.put("reusableBytes", pageSize * freePages);
		try {
			List<Map<String, Object>> objects = jdbcTemplate.queryForList(
					"SELECT name, SUM(pgsize) AS bytes FROM dbstat GROUP BY name ORDER BY bytes DESC LIMIT 50");
			result.put("dbstatAvailable", true);
			result.put("objects", objects);
		} catch (DataAccessException error) {
			result.put("dbstatAvailable", false);
			result.put("capabilityWarning", "当前 SQLite 构建未启用 dbstat，逻辑审计仍然有效");
			result.put("objects", List.of());
		}
		return result;
	}

	private boolean isSqlite() {
		try (java.sql.Connection connection = jdbcTemplate.getDataSource().getConnection()) {
			return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("sqlite");
		} catch (Exception error) {
			return true;
		}
	}

	private long pragmaLong(String name) {
		List<Long> values = jdbcTemplate.query("PRAGMA " + name, (row, index) -> row.getLong(1));
		return values.isEmpty() ? 0L : values.get(0);
	}

	private String fingerprint(Map<String, Object> video, Map<String, Object> graphic,
			Map<String, Object> snapshots, Map<String, Object> workDuplicates, Map<String, Object> normalization,
			Map<String, Object> orphans, Map<String, Object> retentionCandidates, Map<String, Object> storage) {
		List<Object> fields = new ArrayList<>();
		fields.add(video.get("rowsTotal"));
		fields.add(video.get("maxId"));
		fields.add(video.get("exactEqualRows"));
		fields.add(video.get("differentRows"));
		fields.add(video.get("jsonOnlyRows"));
		fields.add(video.get("videoInfoOnlyRows"));
		fields.add(video.get("emptyRawRows"));
		fields.add(video.get("jsonChars"));
		fields.add(video.get("videoInfoChars"));
		fields.add(graphic.get("rowsTotal"));
		fields.add(graphic.get("maxId"));
		fields.add(snapshots.get("taskRows"));
		fields.add(snapshots.get("maxId"));
		addFields(fields, map(workDuplicates.get("video")), "candidateGroups", "candidateRows",
				"mediaReferenceConflictGroups");
		addFields(fields, map(workDuplicates.get("graphic")), "candidateGroups", "candidateRows",
				"mediaReferenceConflictGroups");
		addFields(fields, normalization, "videoMissingPlatformKeyRows", "graphicMissingPlatformKeyRows",
				"authorMissingPlatformKeyRows");
		addFields(fields, orphans, "runItemsWithoutRun", "runEventsWithoutRun",
				"authorNameHistoryWithoutProfile", "collectDetailsWithoutTask");
		addFields(fields, retentionCandidates, "runItems", "terminalRuns", "runEvents", "terminalJobs",
				"nonFailedRunItemDays", "failedRunItemDays", "terminalHistoryDays");
		fields.add(storage.get("pageCount"));
		fields.add(storage.get("freelistPages"));
		return "sha256:" + sha256(String.join("|", fields.stream().map(String::valueOf).toList()));
	}

	private void addFields(List<Object> fields, Map<String, Object> source, String... names) {
		for (String name : names) fields.add(source.get(name));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Object value) {
		return (Map<String, Object>) value;
	}

	private int length(String value) {
		return value == null ? 0 : value.length();
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest((value == null ? "" : value)
					.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}
}
