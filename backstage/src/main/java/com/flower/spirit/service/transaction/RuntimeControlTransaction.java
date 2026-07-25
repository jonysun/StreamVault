package com.flower.spirit.service.transaction;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flower.spirit.service.RuntimeControlSnapshot.RuntimeControlValue;

@Service
public class RuntimeControlTransaction {

	public static final String PAUSE_ALL = "pause.all";
	public static final String PAUSE_COLLECT = "pause.collect";
	public static final String PAUSE_DOWNLOAD = "pause.download";
	public static final String PAUSE_HLS = "pause.hls";
	private static final java.util.List<String> KEYS = java.util.List.of(
			PAUSE_ALL, PAUSE_COLLECT, PAUSE_DOWNLOAD, PAUSE_HLS);

	private final JdbcTemplate jdbcTemplate;

	public RuntimeControlTransaction(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Map<String, RuntimeControlValue> initializeAndLoad(Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		for (String key : KEYS) {
			jdbcTemplate.update("INSERT OR IGNORE INTO biz_runtime_control "
					+ "(control_key, enabled, updated_at, updated_by, reason) VALUES (?, 0, ?, 'system', 'initial')",
					key, timestamp);
		}
		return loadInCurrentTransaction();
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public Map<String, RuntimeControlValue> load() {
		return loadInCurrentTransaction();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Map<String, RuntimeControlValue> set(String key, boolean enabled, String updatedBy, String reason,
			Instant now) {
		if (!KEYS.contains(key)) throw new IllegalArgumentException("Unknown runtime control: " + key);
		jdbcTemplate.update("INSERT INTO biz_runtime_control(control_key, enabled, updated_at, updated_by, reason) "
				+ "VALUES (?, ?, ?, ?, ?) ON CONFLICT(control_key) DO UPDATE SET enabled = excluded.enabled, "
				+ "updated_at = excluded.updated_at, updated_by = excluded.updated_by, reason = excluded.reason",
				key, enabled ? 1 : 0, Timestamp.from(now), trim(updatedBy, 255), trim(reason, 1000));
		return loadInCurrentTransaction();
	}

	private Map<String, RuntimeControlValue> loadInCurrentTransaction() {
		Map<String, RuntimeControlValue> result = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbcTemplate.queryForList(
				"SELECT control_key, enabled, updated_at, updated_by, reason FROM biz_runtime_control")) {
			String key = String.valueOf(row.get("control_key"));
			Object enabled = row.get("enabled");
			boolean active = enabled instanceof Number number ? number.intValue() != 0
					: "1".equals(String.valueOf(enabled));
			result.put(key, new RuntimeControlValue(active, String.valueOf(row.get("updated_at")),
					stringValue(row.get("updated_by")), stringValue(row.get("reason"))));
		}
		return result;
	}

	private String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private String trim(String value, int maxLength) {
		if (value == null) return null;
		String trimmed = value.trim();
		return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
	}
}
