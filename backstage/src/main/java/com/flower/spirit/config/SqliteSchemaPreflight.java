package com.flower.spirit.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "streamvault.database.kind", havingValue = "sqlite", matchIfMissing = true)
public class SqliteSchemaPreflight {

	private static final Logger logger = LoggerFactory.getLogger(SqliteSchemaPreflight.class);
	private static final List<String> IDENTITY_TABLES = List.of(
			"biz_author_profile",
			"biz_author_name_history");

	private final JdbcTemplate jdbcTemplate;

	public SqliteSchemaPreflight(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Order(10)
	@EventListener(ApplicationReadyEvent.class)
	public void verifyIdentitySchemas() {
		for (String table : IDENTITY_TABLES) {
			verifyIdentitySchema(table);
		}
	}

	void verifyIdentitySchema(String table) {
		List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")");
		if (columns.isEmpty()) {
			logger.info("SQLite identity preflight skipped because Hibernate has not created table: {}", table);
			return;
		}

		List<Map<String, Object>> primaryKeys = columns.stream()
				.filter(column -> intValue(column.get("pk")) > 0)
				.toList();
		Map<String, Object> idColumn = columns.stream()
				.filter(column -> "id".equalsIgnoreCase(String.valueOf(column.get("name"))))
				.findFirst()
				.orElse(null);
		boolean compatible = idColumn != null
				&& "INTEGER".equals(String.valueOf(idColumn.get("type")).trim().toUpperCase(Locale.ROOT))
				&& intValue(idColumn.get("pk")) == 1
				&& primaryKeys.size() == 1;
		if (!compatible) {
			throw new IllegalStateException("SQLite schema is incompatible with native identity generation for "
					+ table + ": expected a single id INTEGER PRIMARY KEY; no automatic table rebuild was attempted");
		}
		logger.info("SQLite identity preflight passed: {}.id", table);
	}

	private int intValue(Object value) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		try {
			return Integer.parseInt(String.valueOf(value));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
