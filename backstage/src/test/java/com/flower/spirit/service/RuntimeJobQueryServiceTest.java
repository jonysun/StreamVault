package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class RuntimeJobQueryServiceTest {

	@Test
	void dashboardSeparatesFetchAndDownloadQueueCounts() throws Exception {
		JdbcTemplate jdbc = jdbcTemplate();
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_job_queue(id, job_type, dedupe_key, payload, state, priority, available_at, "
				+ "attempt_count, max_attempts, created_at, updated_at) VALUES "
				+ "(1, 'COLLECT_FETCH', 'collect:4', '{}', 'QUEUED', 1, CURRENT_TIMESTAMP, 0, 3, "
				+ "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP), "
				+ "(2, 'COLLECT_FETCH', 'collect:5', '{}', 'COMPLETED', 1, CURRENT_TIMESTAMP, 1, 3, "
				+ "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
		jdbc.update("INSERT INTO biz_collect_run_item(id, process_state, queue_generation) VALUES "
				+ "(1, 'RUNNING', 'FETCH_DOWNLOAD_V1'), (2, 'RETRY_WAIT', 'FETCH_DOWNLOAD_V1'), "
				+ "(3, 'FAILED', 'FETCH_DOWNLOAD_V1')");

		Map<String, Object> dashboard = new RuntimeJobQueryService(jdbc).dashboard(20);

		assertThat((Map<String, Long>) dashboard.get("fetchQueue"))
				.containsEntry("QUEUED", 1L).doesNotContainKey("COMPLETED");
		assertThat((Map<String, Long>) dashboard.get("downloadQueue"))
				.containsEntry("RUNNING", 1L).containsEntry("RETRY_WAIT", 1L)
				.doesNotContainKey("FAILED");
	}

	private void createSchema(JdbcTemplate jdbc) {
		jdbc.execute("CREATE TABLE biz_job_queue (id INTEGER PRIMARY KEY, job_type TEXT, dedupe_key TEXT, payload TEXT, "
				+ "state TEXT, priority INTEGER, available_at DATETIME, attempt_count INTEGER, max_attempts INTEGER, "
				+ "locked_by TEXT, locked_at DATETIME, last_error_code TEXT, last_error_message TEXT, "
				+ "created_at DATETIME, updated_at DATETIME)");
		jdbc.execute("CREATE TABLE biz_collect_data (id INTEGER PRIMARY KEY, taskname TEXT)");
		jdbc.execute("CREATE TABLE biz_collect_run (id INTEGER PRIMARY KEY, collect_task_id INTEGER, state TEXT, "
				+ "fetched_count INTEGER, planned_count INTEGER, inserted_count INTEGER, failed_item_count INTEGER, "
				+ "heartbeat_at DATETIME)");
		jdbc.execute("CREATE TABLE biz_collect_run_item (id INTEGER PRIMARY KEY, process_state TEXT, "
				+ "queue_generation TEXT)");
	}

	private JdbcTemplate jdbcTemplate() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-runtime-job.db"));
		return new JdbcTemplate(dataSource);
	}
}
