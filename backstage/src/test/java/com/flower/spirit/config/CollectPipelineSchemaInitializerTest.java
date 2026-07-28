package com.flower.spirit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import com.flower.spirit.database.postgresql.DirectDatabaseWriteExecutor;
import com.flower.spirit.entity.CollectRunItemEntity;
import com.flower.spirit.service.transaction.DatabaseInitializationTransaction;

class CollectPipelineSchemaInitializerTest {

	@Test
	void upgradesProductionShapedTablesIdempotentlyWithoutMakingHistoricalItemsClaimable() {
		JdbcTemplate jdbcTemplate = jdbcTemplate("pipeline-upgrade.db");
		createProductionShapedTables(jdbcTemplate);
		jdbcTemplate.execute("CREATE UNIQUE INDEX uq_collect_run_item_work "
				+ "ON biz_collect_run_item(run_id, platform_key, work_id)");
		jdbcTemplate.update("INSERT INTO biz_collect_run_item(id, run_id, ordinal, platform_key, work_id, decision, "
				+ "process_state, created_at, updated_at) VALUES(1, 10, 1, 'douyin', 'work-1', 'DOWNLOAD', "
				+ "'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

		CollectPipelineSchemaInitializer initializer = initializer(jdbcTemplate);
		initializer.initialize();
		initializer.initialize();

		assertColumns(jdbcTemplate, "biz_collect_data", Map.of(
				"last_successful_fetch_at", "TIMESTAMP",
				"last_seen_publish_time", "VARCHAR(64)",
				"last_seen_work_id", "VARCHAR(255)"));
		assertColumns(jdbcTemplate, "biz_collect_run", Map.of(
				"fetch_stop_reason", "VARCHAR(64)",
				"fetch_warning", "VARCHAR(255)"));
		assertColumns(jdbcTemplate, "biz_collect_run_item", Map.of(
				"attempt_count", "INTEGER",
				"max_attempts", "INTEGER",
				"available_at", "TIMESTAMP",
				"locked_by", "VARCHAR(255)",
				"locked_at", "TIMESTAMP",
				"started_at", "TIMESTAMP",
				"finished_at", "TIMESTAMP",
				"error_detail", "CLOB",
				"queue_generation", "VARCHAR(32)"));

		Map<String, Object> attemptCount = columnInfo(jdbcTemplate, "biz_collect_run_item").get("attempt_count");
		Map<String, Object> maxAttempts = columnInfo(jdbcTemplate, "biz_collect_run_item").get("max_attempts");
		assertThat(attemptCount).containsEntry("notnull", 1).containsEntry("dflt_value", "0");
		assertThat(maxAttempts).containsEntry("notnull", 1).containsEntry("dflt_value", "4");

		Map<String, Object> historicalItem = jdbcTemplate.queryForMap(
				"SELECT process_state, attempt_count, max_attempts, queue_generation "
						+ "FROM biz_collect_run_item WHERE id = 1");
		assertThat(historicalItem).containsEntry("process_state", "PENDING")
				.containsEntry("attempt_count", 0)
				.containsEntry("max_attempts", 4)
				.containsEntry("queue_generation", null);
		assertThat(indexNames(jdbcTemplate, "biz_collect_run_item"))
				.contains("idx_collect_run_item_work")
				.doesNotContain("uq_collect_run_item_work");
		jdbcTemplate.update("INSERT INTO biz_collect_run_item(run_id, ordinal, platform_key, work_id, decision, "
				+ "process_state, created_at, updated_at) VALUES(10, 2, 'douyin', 'work-1', "
				+ "'DUPLICATE_OBSERVATION', 'SKIPPED_EXISTING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_collect_run_item WHERE run_id=10 "
				+ "AND work_id='work-1'", Integer.class)).isEqualTo(2);
	}

	@Test
	void missingTablesDoNotAbortInitialization() {
		assertThatCode(() -> initializer(jdbcTemplate("missing.db")).initialize()).doesNotThrowAnyException();
	}

	@Test
	void hibernateFreshSchemaDeclaresRetryDefaultsAndRawInsertCanOmitRetryColumns() {
		String jdbcUrl = jdbcUrl("hibernate-fresh.db");
		StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
				.applySetting("hibernate.connection.driver_class", "org.sqlite.JDBC")
				.applySetting("hibernate.connection.url", jdbcUrl)
				.applySetting("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect")
				.applySetting("hibernate.hbm2ddl.auto", "create")
				.build();
		try (SessionFactory ignored = new MetadataSources(registry)
				.addAnnotatedClass(CollectRunItemEntity.class)
				.buildMetadata()
				.buildSessionFactory()) {
			// Building the session factory creates the fresh Hibernate schema.
		} finally {
			StandardServiceRegistryBuilder.destroy(registry);
		}

		JdbcTemplate jdbcTemplate = jdbcTemplateForUrl(jdbcUrl);
		Map<String, Object> attemptCount = columnInfo(jdbcTemplate, "biz_collect_run_item").get("attempt_count");
		Map<String, Object> maxAttempts = columnInfo(jdbcTemplate, "biz_collect_run_item").get("max_attempts");
		assertThat(attemptCount).containsEntry("notnull", 1).containsEntry("dflt_value", "0");
		assertThat(maxAttempts).containsEntry("notnull", 1).containsEntry("dflt_value", "4");

		jdbcTemplate.update("INSERT OR IGNORE INTO biz_collect_run_item(run_id, ordinal, platform_key, work_id, "
				+ "decision, process_state, created_at, updated_at) VALUES(1, 1, 'douyin', 'work-1', "
				+ "'DOWNLOAD', 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_collect_run_item", Integer.class)).isOne();
		assertThat(jdbcTemplate.queryForMap("SELECT attempt_count, max_attempts FROM biz_collect_run_item"))
				.containsEntry("attempt_count", 0)
				.containsEntry("max_attempts", 4);
	}

	private CollectPipelineSchemaInitializer initializer(JdbcTemplate jdbcTemplate) {
		return new CollectPipelineSchemaInitializer(jdbcTemplate,
				new DatabaseInitializationTransaction(jdbcTemplate), new DirectDatabaseWriteExecutor());
	}

	private JdbcTemplate jdbcTemplate(String filename) {
		return jdbcTemplateForUrl(jdbcUrl(filename));
	}

	private JdbcTemplate jdbcTemplateForUrl(String jdbcUrl) {
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl(jdbcUrl);
		return new JdbcTemplate(dataSource);
	}

	private String jdbcUrl(String filename) {
		Path databaseDirectory = Path.of("target", "test-databases");
		try {
			Files.createDirectories(databaseDirectory);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Failed to create SQLite test directory", e);
		}
		return "jdbc:sqlite:" + databaseDirectory.resolve(UUID.randomUUID() + "-" + filename);
	}

	private void createProductionShapedTables(JdbcTemplate jdbcTemplate) {
		jdbcTemplate.execute("CREATE TABLE biz_collect_data (id INTEGER PRIMARY KEY, taskid TEXT, platform TEXT, "
				+ "taskname TEXT, taskstatus TEXT, createtime TEXT, endtime TEXT, count TEXT, carriedout TEXT, "
				+ "originaladdress TEXT, monitoring TEXT, taskenabled TEXT, lastCheckTime TEXT, lastid TEXT, "
				+ "maxcur INTEGER, omaxcur INTEGER, generatenfo TEXT, taskcron TEXT, lastfetchsnapshot TEXT, "
				+ "lastplanitems TEXT, lastfetchtime TEXT, lastfetchcount INTEGER)");
		jdbcTemplate.execute("CREATE TABLE biz_collect_run (id INTEGER PRIMARY KEY AUTOINCREMENT, "
				+ "collect_task_id INTEGER NOT NULL, trigger_type TEXT NOT NULL, state TEXT NOT NULL, "
				+ "requested_limit INTEGER, fetched_count INTEGER, planned_count INTEGER, inserted_count INTEGER, "
				+ "skipped_existing_count INTEGER, failed_item_count INTEGER, started_at DATETIME, "
				+ "heartbeat_at DATETIME, finished_at DATETIME, error_code TEXT, error_message TEXT, "
				+ "error_detail TEXT, created_at DATETIME NOT NULL)");
		jdbcTemplate.execute("CREATE TABLE biz_collect_run_item (id INTEGER PRIMARY KEY AUTOINCREMENT, "
				+ "run_id INTEGER NOT NULL, ordinal INTEGER NOT NULL, platform_key TEXT NOT NULL, "
				+ "work_id TEXT NOT NULL, author_uid TEXT, nickname_snapshot TEXT, title_snapshot TEXT, "
				+ "publish_time TEXT, media_type TEXT, decision TEXT NOT NULL, process_state TEXT NOT NULL, "
				+ "error_code TEXT, error_message TEXT, created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL)");
	}

	private void assertColumns(JdbcTemplate jdbcTemplate, String table, Map<String, String> expectedTypes) {
		Map<String, Map<String, Object>> columns = columnInfo(jdbcTemplate, table);
		expectedTypes.forEach((name, type) -> {
			assertThat(columns).containsKey(name);
			assertThat(String.valueOf(columns.get(name).get("type"))).isEqualTo(type);
		});
	}

	private Map<String, Map<String, Object>> columnInfo(JdbcTemplate jdbcTemplate, String table) {
		return jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")").stream()
				.collect(Collectors.toMap(row -> String.valueOf(row.get("name")), Function.identity()));
	}

	private java.util.List<String> indexNames(JdbcTemplate jdbcTemplate, String table) {
		return jdbcTemplate.queryForList("PRAGMA index_list(" + table + ")").stream()
				.map(row -> String.valueOf(row.get("name")))
				.toList();
	}
}
