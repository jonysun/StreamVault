package com.flower.spirit.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
			"biz_author_name_history",
			"biz_author_enrichment_job",
			"biz_collect_data_detail",
			"biz_collect_run",
			"biz_collect_run_item",
			"biz_collect_run_event",
			"biz_job_queue",
			"biz_database_maintenance_operation",
			"biz_video",
			"biz_graphic_content");
	private static final Map<String, List<String>> REQUIRED_PIPELINE_COLUMNS = requiredPipelineColumns();

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

	@Order(180)
	@EventListener(ApplicationReadyEvent.class)
	public void verifyPipelineSchemas() {
		REQUIRED_PIPELINE_COLUMNS.forEach(this::verifyRequiredColumns);
		verifyRunItemObservationIndex();
	}

	private void verifyRunItemObservationIndex() {
		List<Map<String, Object>> indexes = jdbcTemplate.queryForList("PRAGMA index_list(biz_collect_run_item)");
		boolean legacyUniquePresent = indexes.stream()
				.anyMatch(index -> "uq_collect_run_item_work".equalsIgnoreCase(String.valueOf(index.get("name"))));
		boolean lookupIndexPresent = indexes.stream()
				.anyMatch(index -> "idx_collect_run_item_work".equalsIgnoreCase(String.valueOf(index.get("name")))
						&& intValue(index.get("unique")) == 0);
		if (legacyUniquePresent || !lookupIndexPresent) {
			throw new IllegalStateException("SQLite collection pipeline index migration is incomplete for "
					+ "biz_collect_run_item: expected nonunique idx_collect_run_item_work and no "
					+ "uq_collect_run_item_work");
		}
		logger.info("SQLite collection pipeline preflight passed: biz_collect_run_item observation index");
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

	private void verifyRequiredColumns(String table, List<String> requiredColumns) {
		List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")");
		if (columns.isEmpty()) {
			throw new IllegalStateException("SQLite schema is missing required collection pipeline table: " + table);
		}

		Set<String> existingColumns = columns.stream()
				.map(column -> String.valueOf(column.get("name")).toLowerCase(Locale.ROOT))
				.collect(Collectors.toSet());
		List<String> missingColumns = requiredColumns.stream()
				.filter(column -> !existingColumns.contains(column))
				.toList();
		if (!missingColumns.isEmpty()) {
			throw new IllegalStateException("SQLite schema is missing required collection pipeline columns for "
					+ table + ": " + String.join(", ", missingColumns));
		}
		logger.info("SQLite collection pipeline preflight passed: {}", table);
	}

	private static Map<String, List<String>> requiredPipelineColumns() {
		Map<String, List<String>> columns = new LinkedHashMap<>();
		columns.put("biz_collect_data", List.of(
				"last_successful_fetch_at", "last_seen_publish_time", "last_seen_work_id",
				"backfill_cursor", "backfill_complete", "backfill_source_id", "backfill_verifying",
				"backfill_clean_passes", "backfill_verified_at", "remote_account_state",
				"remote_account_reason", "remote_account_detected_at"));
		columns.put("biz_collect_run", List.of("fetch_stop_reason", "fetch_warning"));
		columns.put("biz_collect_run_item", List.of(
				"attempt_count", "max_attempts", "available_at", "locked_by", "locked_at", "started_at",
				"finished_at", "error_detail", "queue_generation"));
		return Collections.unmodifiableMap(columns);
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
