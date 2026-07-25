package com.flower.spirit.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseAuditService {

	private final JdbcTemplate jdbcTemplate;

	public DatabaseAuditService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Map<String, Object> audit() {
		Map<String, Object> video = new LinkedHashMap<>(jdbcTemplate.queryForMap(
				"SELECT COUNT(*) AS rowsTotal, "
						+ "SUM(CASE WHEN jsonData IS NOT NULL AND jsonData <> '' THEN 1 ELSE 0 END) AS jsonRows, "
						+ "SUM(CASE WHEN videoinfo IS NOT NULL AND videoinfo <> '' THEN 1 ELSE 0 END) AS videoInfoRows, "
						+ "SUM(CASE WHEN jsonData = videoinfo THEN 1 ELSE 0 END) AS exactEqualRows, "
						+ "SUM(CASE WHEN jsonData = videoinfo THEN LENGTH(videoinfo) ELSE 0 END) AS exactDuplicateVideoInfoChars, "
						+ "SUM(CASE WHEN jsonData IS NOT NULL AND videoinfo IS NOT NULL AND jsonData <> videoinfo THEN 1 ELSE 0 END) AS differentRows, "
						+ "SUM(LENGTH(COALESCE(jsonData, ''))) AS jsonChars, "
						+ "SUM(LENGTH(COALESCE(videoinfo, ''))) AS videoInfoChars, MAX(id) AS maxId FROM biz_video"));
		Map<String, Object> graphic = new LinkedHashMap<>(jdbcTemplate.queryForMap(
				"SELECT COUNT(*) AS rowsTotal, SUM(LENGTH(COALESCE(jsonData, ''))) AS jsonChars, "
						+ "MAX(LENGTH(COALESCE(jsonData, ''))) AS maxJsonChars, MAX(id) AS maxId FROM biz_graphic_content"));
		Map<String, Object> snapshots = new LinkedHashMap<>(jdbcTemplate.queryForMap(
				"SELECT COUNT(*) AS taskRows, SUM(LENGTH(COALESCE(lastfetchsnapshot, ''))) AS fetchChars, "
						+ "MAX(LENGTH(COALESCE(lastfetchsnapshot, ''))) AS fetchMaxChars, "
						+ "SUM(LENGTH(COALESCE(lastplanitems, ''))) AS planChars, "
						+ "MAX(LENGTH(COALESCE(lastplanitems, ''))) AS planMaxChars, MAX(id) AS maxId FROM biz_collect_data"));
		Map<String, Object> storage = storageStats();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("video", video);
		result.put("graphic", graphic);
		result.put("collectSnapshots", snapshots);
		result.put("differentSamples", differenceSamples(10));
		result.put("storage", storage);
		result.put("fingerprint", fingerprint(video, graphic, snapshots, storage));
		result.put("notes", List.of(
				"统计长度为逻辑字符数，不等同于 VACUUM 后的物理字节数",
				"在线清理只释放 SQLite 可复用页；缩小文件必须停服后执行 VACUUM INTO"));
		return result;
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

	private long pragmaLong(String name) {
		List<Long> values = jdbcTemplate.query("PRAGMA " + name, (row, index) -> row.getLong(1));
		return values.isEmpty() ? 0L : values.get(0);
	}

	private String fingerprint(Map<String, Object> video, Map<String, Object> graphic,
			Map<String, Object> snapshots, Map<String, Object> storage) {
		List<Object> fields = new ArrayList<>();
		fields.add(video.get("rowsTotal"));
		fields.add(video.get("maxId"));
		fields.add(video.get("exactEqualRows"));
		fields.add(video.get("differentRows"));
		fields.add(video.get("jsonChars"));
		fields.add(video.get("videoInfoChars"));
		fields.add(graphic.get("rowsTotal"));
		fields.add(graphic.get("maxId"));
		fields.add(snapshots.get("taskRows"));
		fields.add(snapshots.get("maxId"));
		fields.add(storage.get("pageCount"));
		fields.add(storage.get("freelistPages"));
		return "sha256:" + sha256(String.join("|", fields.stream().map(String::valueOf).toList()));
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
