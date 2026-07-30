package com.flower.spirit.config;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.database.DatabaseSchemaInspector;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.service.transaction.DatabaseInitializationTransaction;

@Service
public class PlatformSchemaInitializer {

	private static final Logger logger = LoggerFactory.getLogger(PlatformSchemaInitializer.class);
	private static final int BACKFILL_BATCH_SIZE = 500;
	private static final Map<String, List<String>> COLUMN_DEFINITIONS = columnDefinitions();

	private final JdbcTemplate jdbcTemplate;
	private final DatabaseInitializationTransaction transaction;
	private final DatabaseWriteExecutor databaseWriteExecutor;
	private final DatabaseSchemaInspector schemaInspector;

	@Autowired
	public PlatformSchemaInitializer(JdbcTemplate jdbcTemplate, DatabaseInitializationTransaction transaction,
			DatabaseWriteExecutor databaseWriteExecutor, DatabaseSchemaInspector schemaInspector) {
		this.jdbcTemplate = jdbcTemplate;
		this.transaction = transaction;
		this.databaseWriteExecutor = databaseWriteExecutor;
		this.schemaInspector = schemaInspector;
	}

	public PlatformSchemaInitializer(JdbcTemplate jdbcTemplate, DatabaseInitializationTransaction transaction,
			DatabaseWriteExecutor databaseWriteExecutor) {
		this(jdbcTemplate, transaction, databaseWriteExecutor,
				new DatabaseSchemaInspector(jdbcTemplate.getDataSource()));
	}

	@Order(100)
	@EventListener(ApplicationReadyEvent.class)
	public void initialize() {
		for (Map.Entry<String, List<String>> entry : COLUMN_DEFINITIONS.entrySet()) {
			ensureColumns(entry.getKey(), entry.getValue());
		}
		backfillPlatformKey("biz_video", "videoplatform");
		backfillPlatformKey("biz_graphic_content", "platform");
		backfillPlatformKey("biz_author_profile", "platform");
	}

	private void ensureColumns(String table, List<String> definitions) {
		Set<String> existing = tableColumns(table);
		if (existing.isEmpty()) {
			logger.warn("Skipping platform schema upgrade because table is missing: {}", table);
			return;
		}
		for (String definition : definitions) {
			String column = definition.substring(0, definition.indexOf(' '));
			if (existing.contains(column)) {
				continue;
			}
			try {
				databaseWriteExecutor.execute("schema-add-column", () -> {
					transaction.execute("ALTER TABLE " + table + " ADD COLUMN " + definition);
					return null;
				});
				existing.add(column);
			} catch (Exception e) {
				logger.warn("Failed to add optional column {}.{}", table, column, e);
			}
		}
	}

	private Set<String> tableColumns(String table) {
		try {
			return schemaInspector.columns(table);
		} catch (Exception e) {
			logger.warn("Failed to inspect table schema: {}", table, e);
			return new java.util.HashSet<>();
		}
	}

	private void backfillPlatformKey(String table, String legacyColumn) {
		Set<String> columns = tableColumns(table);
		if (!columns.contains("platformkey") || !columns.contains(legacyColumn.toLowerCase())) {
			return;
		}
		long lastId = 0L;
		while (true) {
			List<BackfillRow> rows;
			try {
				String sql = "SELECT id, " + legacyColumn + " AS legacyplatform FROM " + table
						+ " WHERE id > ? AND (platformkey IS NULL OR trim(platformkey) = '')"
						+ " ORDER BY id LIMIT " + BACKFILL_BATCH_SIZE;
				rows = jdbcTemplate.query(sql, (rs, rowNum) -> mapBackfillRow(rs), lastId);
			} catch (Exception e) {
				logger.warn("Failed to read platform key backfill batch for table: {}", table, e);
				return;
			}
			if (rows.isEmpty()) {
				return;
			}

			List<Object[]> updates = new ArrayList<>();
			for (BackfillRow row : rows) {
				PlatformCatalog.findByAlias(row.legacyPlatform())
						.ifPresent(definition -> updates.add(new Object[] { definition.getKey(), row.id() }));
			}
			if (!updates.isEmpty()) {
				try {
					databaseWriteExecutor.execute("schema-backfill-platform-key", () -> {
						transaction.batchUpdate("UPDATE " + table
								+ " SET platformkey = ? WHERE id = ? AND (platformkey IS NULL OR trim(platformkey) = '')",
								updates);
						return null;
					});
				} catch (Exception e) {
					logger.warn("Failed to update platform key backfill batch for table: {}", table, e);
					return;
				}
			}
			lastId = rows.get(rows.size() - 1).id();
		}
	}

	private BackfillRow mapBackfillRow(ResultSet resultSet) throws SQLException {
		return new BackfillRow(resultSet.getLong("id"), resultSet.getString("legacyplatform"));
	}

	private static Map<String, List<String>> columnDefinitions() {
		Map<String, List<String>> definitions = new LinkedHashMap<>();
		List<String> workColumns = List.of(
				"platformkey varchar(64)",
				"contenttype varchar(32)",
				"authorhomepage varchar(512)",
				"metadataoverrides clob",
				"metadataeditedat datetime",
				"metadataeditedby varchar(255)");
		definitions.put("biz_video", workColumns);
		List<String> graphicColumns = new ArrayList<>(workColumns);
		graphicColumns.add("privacy varchar(32)");
		graphicColumns.add("favorite varchar(32)");
		definitions.put("biz_graphic_content", List.copyOf(graphicColumns));
		definitions.put("biz_author_profile", List.of("platformkey varchar(64)", "signature varchar(1024)"));
		return Map.copyOf(definitions);
	}

	private record BackfillRow(long id, String legacyPlatform) {
	}
}
