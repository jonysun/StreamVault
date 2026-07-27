package com.flower.spirit.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.service.transaction.DatabaseInitializationTransaction;

@Service
public class CollectPipelineSchemaInitializer {

	private static final Logger logger = LoggerFactory.getLogger(CollectPipelineSchemaInitializer.class);
	private static final Map<String, List<String>> COLUMN_DEFINITIONS = columnDefinitions();

	private final JdbcTemplate jdbcTemplate;
	private final DatabaseInitializationTransaction transaction;
	private final DatabaseWriteExecutor databaseWriteExecutor;

	public CollectPipelineSchemaInitializer(JdbcTemplate jdbcTemplate, DatabaseInitializationTransaction transaction,
			DatabaseWriteExecutor databaseWriteExecutor) {
		this.jdbcTemplate = jdbcTemplate;
		this.transaction = transaction;
		this.databaseWriteExecutor = databaseWriteExecutor;
	}

	@Order(120)
	@EventListener(ApplicationReadyEvent.class)
	public void initialize() {
		COLUMN_DEFINITIONS.forEach(this::ensureColumns);
	}

	private void ensureColumns(String table, List<String> definitions) {
		Set<String> existing = tableColumns(table);
		if (existing.isEmpty()) {
			logger.warn("Skipping collection pipeline schema upgrade because table is missing: {}", table);
			return;
		}
		for (String definition : definitions) {
			String column = definition.substring(0, definition.indexOf(' '));
			if (existing.contains(column)) {
				continue;
			}
			try {
				databaseWriteExecutor.execute("schema-add-collect-pipeline-column", () -> {
					transaction.execute("ALTER TABLE " + table + " ADD COLUMN " + definition);
					return null;
				});
				existing.add(column);
			} catch (Exception e) {
				logger.warn("Failed to add collection pipeline column {}.{}", table, column, e);
			}
		}
	}

	private Set<String> tableColumns(String table) {
		try {
			return jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")").stream()
					.map(row -> String.valueOf(row.get("name")).toLowerCase(Locale.ROOT))
					.collect(Collectors.toSet());
		} catch (Exception e) {
			logger.warn("Failed to inspect collection pipeline table schema: {}", table, e);
			return Set.of();
		}
	}

	private static Map<String, List<String>> columnDefinitions() {
		Map<String, List<String>> definitions = new LinkedHashMap<>();
		definitions.put("biz_collect_data", List.of(
				"last_successful_fetch_at TIMESTAMP",
				"last_seen_publish_time VARCHAR(64)",
				"last_seen_work_id VARCHAR(255)"));
		definitions.put("biz_collect_run", List.of(
				"fetch_stop_reason VARCHAR(64)",
				"fetch_warning VARCHAR(255)"));
		definitions.put("biz_collect_run_item", List.of(
				"attempt_count INTEGER NOT NULL DEFAULT 0",
				"max_attempts INTEGER NOT NULL DEFAULT 4",
				"available_at TIMESTAMP",
				"locked_by VARCHAR(255)",
				"locked_at TIMESTAMP",
				"started_at TIMESTAMP",
				"finished_at TIMESTAMP",
				"error_detail CLOB",
				"queue_generation VARCHAR(32)"));
		return Map.copyOf(definitions);
	}
}
