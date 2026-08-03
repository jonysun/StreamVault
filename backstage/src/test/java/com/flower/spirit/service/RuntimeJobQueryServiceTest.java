package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
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

	@Test
	@SuppressWarnings("unchecked")
	void dashboardReturnsCanonicalJobFieldsAndTaskDownloadAggregates() throws Exception {
		JdbcTemplate jdbc = jdbcTemplate();
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_collect_data(id, taskname) VALUES (4, 'Author A')");
		jdbc.update("INSERT INTO biz_job_queue(id, job_type, dedupe_key, payload, state, priority, available_at, "
				+ "attempt_count, max_attempts, created_at, updated_at) VALUES "
				+ "(7, 'COLLECT_FETCH', 'collect:4', '{\"taskId\":4,\"runId\":9}', 'RUNNING', 1, CURRENT_TIMESTAMP, 1, 3, "
				+ "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
		jdbc.update("INSERT INTO biz_collect_run(id, collect_task_id, state, planned_count, fetched_count, "
				+ "inserted_count, failed_item_count) VALUES (9, 4, 'COMPLETED', 3, 3, 0, 0)");
		jdbc.update("INSERT INTO biz_collect_run_item(id, run_id, process_state, queue_generation) VALUES "
				+ "(1, 9, 'COMPLETED', 'FETCH_DOWNLOAD_V1'), (2, 9, 'RUNNING', 'FETCH_DOWNLOAD_V1'), "
				+ "(3, 9, 'RETRY_WAIT', 'FETCH_DOWNLOAD_V1')");

		Map<String, Object> dashboard = new RuntimeJobQueryService(jdbc).dashboard(20);

		Map<String, Object> job = ((List<Map<String, Object>>) dashboard.get("running")).get(0);
		assertThat(job).containsEntry("jobId", 7L).containsEntry("taskId", 4)
				.containsEntry("taskName", "Author A").containsEntry("runId", 9L);
		Map<String, Object> progress = ((List<Map<String, Object>>) dashboard.get("downloadTasks")).get(0);
		assertThat(progress).containsEntry("taskName", "Author A").containsEntry("runId", 9L)
				.containsEntry("plannedCount", 3L).containsEntry("completedCount", 1L)
				.containsEntry("runningCount", 1L).containsEntry("retryWaitCount", 1L);
	}

	@Test
	@SuppressWarnings("unchecked")
	void dashboardLimitsDownloadAggregationToNewestActiveRuns() throws Exception {
		JdbcTemplate jdbc = jdbcTemplate();
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_collect_data(id, taskname) VALUES (4, 'Author A')");
		jdbc.update("INSERT INTO biz_collect_run(id, collect_task_id, state, planned_count) VALUES "
				+ "(8, 4, 'COMPLETED', 1), (9, 4, 'COMPLETED', 2), (10, 4, 'COMPLETED', 2)");
		jdbc.update("INSERT INTO biz_collect_run_item(id, run_id, process_state, queue_generation) VALUES "
				+ "(1, 8, 'COMPLETED', 'FETCH_DOWNLOAD_V1'), "
				+ "(2, 9, 'QUEUED', 'FETCH_DOWNLOAD_V1'), (3, 9, 'COMPLETED', 'FETCH_DOWNLOAD_V1'), "
				+ "(4, 10, 'RUNNING', 'FETCH_DOWNLOAD_V1'), (5, 10, 'COMPLETED', 'FETCH_DOWNLOAD_V1')");

		List<Map<String, Object>> progress = (List<Map<String, Object>>) new RuntimeJobQueryService(jdbc)
				.dashboard(1).get("downloadTasks");

		assertThat(progress).hasSize(1);
		assertThat(progress.get(0)).containsEntry("runId", 10L).containsEntry("runningCount", 1L)
				.containsEntry("completedCount", 1L);
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
		jdbc.execute("CREATE TABLE biz_collect_run_item (id INTEGER PRIMARY KEY, run_id INTEGER, process_state TEXT, "
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
