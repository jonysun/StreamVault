package com.flower.spirit.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.sqlite.SQLiteDataSource;

class DatabaseMaintenanceTransactionTest {

	@Test
	void clearsOnlyExactDuplicatesInResumableBatches() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-maintenance.db"));
		try (AnnotationConfigApplicationContext context = context(dataSource)) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			jdbc.update("INSERT INTO biz_video(id,jsonData,videoinfo) VALUES (1,'same-1','same-1'), "
					+ "(2,'canonical','different'), (3,'same-3','same-3')");
			DatabaseMaintenanceTransaction transaction = context.getBean(DatabaseMaintenanceTransaction.class);
			Instant now = Instant.parse("2026-07-25T08:00:00Z");
			long operationId = transaction.create("token", "fingerprint", "[\"CLEAR_EXACT_DUPLICATE_VIDEOINFO\"]",
					2, 1, "CLEAR_EXACT_DUPLICATE_VIDEOINFO", now);

			DatabaseMaintenanceTransaction.BatchResult first = transaction.clearDuplicateVideoInfo(operationId, 0, 1,
					now.plusSeconds(1));
			DatabaseMaintenanceTransaction.BatchResult second = transaction.clearDuplicateVideoInfo(operationId,
					first.lastProcessedId(), 1, now.plusSeconds(2));

			assertThat(first.affectedRows()).isEqualTo(1);
			assertThat(second.affectedRows()).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT videoinfo FROM biz_video WHERE id=2", String.class))
					.isEqualTo("different");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_video WHERE videoinfo IS NOT NULL", Integer.class))
					.isEqualTo(1);
			Map<String, Object> operation = transaction.find(operationId);
			assertThat(((Number) operation.get("processedRows")).longValue()).isEqualTo(2L);
		}
	}

	@Test
	void purgesRuntimeHistoryInReferentialOrderWithoutDeletingActiveOrReferencedRows() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-retention.db"));
		try (AnnotationConfigApplicationContext context = context(dataSource)) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			createRuntimeSchema(jdbc);
			jdbc.update("INSERT INTO biz_collect_run(id,state,created_at) VALUES "
					+ "(1,'COMPLETED','2020-01-01 00:00:00'),"
					+ "(2,'FETCH_FAILED','2020-01-01 00:00:00'),"
					+ "(3,'PROCESSING','2020-01-01 00:00:00'),"
					+ "(4,'COMPLETED',CURRENT_TIMESTAMP),"
					+ "(5,'CANCELLED','2020-01-01 00:00:00')");
			jdbc.update("INSERT INTO biz_collect_run_item(id,run_id,process_state,created_at) VALUES "
					+ "(1,1,'COMPLETED','2020-01-01 00:00:00'),"
					+ "(2,2,'FAILED','2026-01-29 08:00:00')");
			jdbc.update("INSERT INTO biz_collect_run_event(id,run_id,created_at) VALUES "
					+ "(1,1,'2020-01-01 00:00:00'),"
					+ "(2,2,'2020-01-01 00:00:00'),"
					+ "(3,5,CURRENT_TIMESTAMP)");
			jdbc.update("INSERT INTO biz_job_queue(id,state,created_at) VALUES "
					+ "(1,'COMPLETED','2020-01-01 00:00:00'),"
					+ "(2,'FAILED','2020-01-01 00:00:00'),"
					+ "(3,'CANCELLED','2020-01-01 00:00:00'),"
					+ "(4,'RUNNING','2020-01-01 00:00:00'),"
					+ "(5,'COMPLETED',CURRENT_TIMESTAMP)");
			DatabaseMaintenanceTransaction transaction = context.getBean(DatabaseMaintenanceTransaction.class);
			Instant now = Instant.parse("2026-07-28T08:00:00Z");
			long operationId = transaction.create("retention-token", "fingerprint", "[]",
					7, 100, "PURGE_EXPIRED_RUN_ITEMS", now);

			assertThat(transaction.purgeExpiredRunItems(operationId, 0, 100, now).affectedRows()).isEqualTo(1);
			assertThat(transaction.purgeExpiredRunEvents(operationId, 0, 100, now).affectedRows()).isEqualTo(2);
			assertThat(transaction.purgeExpiredTerminalRuns(operationId, 0, 100, now).affectedRows()).isEqualTo(1);
			assertThat(transaction.purgeExpiredTerminalJobs(operationId, 0, 100, now).affectedRows()).isEqualTo(3);

			assertThat(jdbc.queryForList("SELECT id FROM biz_collect_run ORDER BY id", Long.class))
					.containsExactly(2L, 3L, 4L, 5L);
			assertThat(jdbc.queryForList("SELECT id FROM biz_collect_run_item ORDER BY id", Long.class))
					.containsExactly(2L);
			assertThat(jdbc.queryForList("SELECT id FROM biz_collect_run_event ORDER BY id", Long.class))
					.containsExactly(3L);
			assertThat(jdbc.queryForList("SELECT id FROM biz_job_queue ORDER BY id", Long.class))
					.containsExactly(4L, 5L);
			assertThat(((Number) transaction.find(operationId).get("processedRows")).longValue()).isEqualTo(7L);
		}
	}

	@Test
	void missingRuntimeHistoryTablesAreExhaustedNoOpBatches() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-missing-retention.db"));
		try (AnnotationConfigApplicationContext context = context(dataSource)) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			DatabaseMaintenanceTransaction transaction = context.getBean(DatabaseMaintenanceTransaction.class);
			Instant now = Instant.parse("2026-07-28T08:00:00Z");
			long operationId = transaction.create("missing-token", "fingerprint", "[]",
					0, 100, "PURGE_EXPIRED_RUN_ITEMS", now);

			assertNoOp(transaction.purgeExpiredRunItems(operationId, 0, 100, now));
			assertNoOp(transaction.purgeExpiredRunEvents(operationId, 0, 100, now));
			assertNoOp(transaction.purgeExpiredTerminalRuns(operationId, 0, 100, now));
			assertNoOp(transaction.purgeExpiredTerminalJobs(operationId, 0, 100, now));
		}
	}

	private void assertNoOp(DatabaseMaintenanceTransaction.BatchResult result) {
		assertThat(result.affectedRows()).isZero();
		assertThat(result.lastProcessedId()).isZero();
		assertThat(result.exhausted()).isTrue();
	}

	private void createSchema(JdbcTemplate jdbc) {
		jdbc.execute("CREATE TABLE biz_video (id INTEGER PRIMARY KEY, jsonData TEXT, videoinfo TEXT)");
		jdbc.execute("CREATE TABLE biz_database_maintenance_operation (id INTEGER PRIMARY KEY AUTOINCREMENT, "
				+ "preview_token_hash TEXT NOT NULL UNIQUE, db_fingerprint TEXT NOT NULL, operations TEXT NOT NULL, "
				+ "status TEXT NOT NULL, current_operation TEXT, last_processed_id INTEGER, processed_rows INTEGER NOT NULL, "
				+ "estimated_rows INTEGER NOT NULL, batch_size INTEGER NOT NULL, error_message TEXT, "
				+ "created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL)");
	}

	private void createRuntimeSchema(JdbcTemplate jdbc) {
		jdbc.execute("CREATE TABLE biz_collect_run (id INTEGER PRIMARY KEY, state TEXT, created_at DATETIME)");
		jdbc.execute("CREATE TABLE biz_collect_run_item "
				+ "(id INTEGER PRIMARY KEY, run_id INTEGER, process_state TEXT, created_at DATETIME)");
		jdbc.execute("CREATE TABLE biz_collect_run_event "
				+ "(id INTEGER PRIMARY KEY, run_id INTEGER, created_at DATETIME)");
		jdbc.execute("CREATE TABLE biz_job_queue (id INTEGER PRIMARY KEY, state TEXT, created_at DATETIME)");
	}

	private AnnotationConfigApplicationContext context(DataSource dataSource) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.register(TransactionConfiguration.class);
		context.registerBean(DataSource.class, () -> dataSource);
		context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
		context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
		context.registerBean(DatabaseMaintenanceTransaction.class);
		context.refresh();
		return context;
	}

	@Configuration
	@EnableTransactionManagement
	static class TransactionConfiguration {
	}
}
