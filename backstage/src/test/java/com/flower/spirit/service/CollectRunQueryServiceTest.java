package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class CollectRunQueryServiceTest {

	@Test
	void latestItemsUsesNewestRunThatActuallyHasPersistedItems() throws Exception {
		JdbcTemplate jdbc = jdbcTemplate("latest-items.db");
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_collect_run(id, collect_task_id) VALUES (8, 4), (9, 4), (10, 4)");
		jdbc.update("INSERT INTO biz_collect_run_item(id, run_id, ordinal, work_id, media_type, decision) "
				+ "VALUES (80, 8, 1, 'old-work', 'video', 'NEW'), "
				+ "(90, 9, 1, 'new-work', 'video', 'NEW'), "
				+ "(91, 9, 2, 'skip-work', 'graphic', 'EXISTING'), "
				+ "(92, 9, 3, 'audit-work', 'video', 'AUDIT_REPAIR'), "
				+ "(93, 9, 4, 'retry-work', 'video', 'MANUAL_RETRY')");
		CollectRunQueryService service = new CollectRunQueryService(jdbc, new SnapshotCodec(4096, 2));

		Map<String, Object> result = service.findLatestItems(4, "plan", 20, 0);

		assertThat(result).containsEntry("source", "run-item").containsEntry("runId", 9L);
		assertThat((List<Map<String, Object>>) result.get("items"))
				.extracting(item -> item.get("workId"))
				.containsExactly("new-work", "audit-work", "retry-work");
	}

	@Test
	void latestItemsFallsBackToLegacySnapshotWhenNoRunItemsExist() throws Exception {
		JdbcTemplate jdbc = jdbcTemplate("legacy-items.db");
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_collect_data(id, lastfetchsnapshot, lastplanitems) VALUES (7, ?, ?)",
				"[{\"aweme_id\":\"legacy-work\",\"has_video_play_addr\":true}]", "[]");
		CollectRunQueryService service = new CollectRunQueryService(jdbc, new SnapshotCodec(4096, 2));

		Map<String, Object> result = service.findLatestItems(7, "all", 20, 0);

		assertThat(result).containsEntry("source", "legacy-snapshot");
		assertThat((List<SnapshotItem>) result.get("items"))
				.extracting(SnapshotItem::workId)
				.containsExactly("legacy-work");
	}

	@Test
	void runItemsExposeRetryTimingAndErrorDiagnostics() throws Exception {
		JdbcTemplate jdbc = jdbcTemplate("item-diagnostics.db");
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_collect_run(id, collect_task_id) VALUES (9, 4)");
		jdbc.update("INSERT INTO biz_collect_run_item(id, run_id, ordinal, work_id, decision, process_state, "
				+ "attempt_count, max_attempts, available_at, started_at, finished_at, error_detail, queue_generation) "
				+ "VALUES (90, 9, 1, 'work', 'NEW', 'RETRY_WAIT', 2, 4, '2026-07-27 02:00:00', "
				+ "'2026-07-27 01:00:00', NULL, 'root stack', 'FETCH_DOWNLOAD_V1')");
		CollectRunQueryService service = new CollectRunQueryService(jdbc, new SnapshotCodec(4096, 2));

		Map<String, Object> item = service.findItems(9, "all", 20, 0).get(0);

		assertThat(item).containsEntry("attemptCount", 2).containsEntry("maxAttempts", 4)
				.containsEntry("errorDetail", "root stack")
				.containsEntry("queueGeneration", "FETCH_DOWNLOAD_V1");
		assertThat(item.get("availableAt")).isNotNull();
		assertThat(item.get("startedAt")).isNotNull();
	}

	@Test
	void downloadQueueReturnsCountsAndClaimOrderedItems() throws Exception {
		JdbcTemplate jdbc = jdbcTemplate("download-queue.db");
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_collect_data(id, taskname) VALUES (4, 'Author')");
		jdbc.update("INSERT INTO biz_collect_run(id, collect_task_id) VALUES (9, 4)");
		jdbc.update("INSERT INTO biz_collect_run_item(id, run_id, ordinal, work_id, decision, process_state, "
				+ "attempt_count, max_attempts, available_at, queue_generation, created_at) VALUES "
				+ "(90, 9, 2, 'normal', 'NEW', 'QUEUED', 0, 4, '2026-07-27 01:00:00', "
				+ "'FETCH_DOWNLOAD_V1', '2026-07-27 01:00:00'), "
				+ "(91, 9, 9, 'manual', 'MANUAL_RETRY', 'RETRY_WAIT', 1, 4, '2026-07-27 00:30:00', "
				+ "'FETCH_DOWNLOAD_V1', '2026-07-27 00:30:00'), "
				+ "(92, 9, 1, 'running', 'NEW', 'RUNNING', 1, 4, NULL, "
				+ "'FETCH_DOWNLOAD_V1', '2026-07-27 00:10:00'), "
				+ "(93, 9, 1, 'future', 'NEW', 'RETRY_WAIT', 1, 4, '2999-01-01 00:00:00', "
				+ "'FETCH_DOWNLOAD_V1', '2026-07-27 00:10:00'), "
				+ "(94, 9, 1, 'exhausted', 'NEW', 'RETRY_WAIT', 4, 4, '2026-07-27 00:00:00', "
				+ "'FETCH_DOWNLOAD_V1', '2026-07-27 00:10:00'), "
				+ "(95, 9, 1, 'completed', 'NEW', 'COMPLETED', 1, 4, NULL, "
				+ "'FETCH_DOWNLOAD_V1', '2026-07-27 00:10:00')");
		jdbc.update("UPDATE biz_collect_run_item SET available_at = ? WHERE id IN (90, 91, 94)",
				Timestamp.from(Instant.now().minusSeconds(60)));
		jdbc.update("UPDATE biz_collect_run_item SET available_at = ? WHERE id = 93",
				Timestamp.from(Instant.now().plusSeconds(3600)));
		CollectRunQueryService service = new CollectRunQueryService(jdbc, new SnapshotCodec(4096, 2));

		Map<String, Object> queue = service.downloadQueue(4, 20);

		assertThat((Map<String, Long>) queue.get("counts")).containsEntry("QUEUED", 1L)
				.containsEntry("RUNNING", 1L).containsEntry("RETRY_WAIT", 3L)
				.doesNotContainKey("COMPLETED");
		assertThat((List<Map<String, Object>>) queue.get("runningItems"))
				.extracting(item -> item.get("workId")).containsExactly("running");
		assertThat((List<Map<String, Object>>) queue.get("waitingItems"))
				.extracting(item -> item.get("workId")).containsExactly("manual", "normal", "future");
		assertThat((List<Map<String, Object>>) queue.get("items"))
				.extracting(item -> item.get("workId")).containsExactly("manual", "normal");
		assertThat(queue.get("oldestQueuedAt")).isNotNull();
		assertThat(queue.get("nextRetryAt")).isNotNull();
	}

	private void createSchema(JdbcTemplate jdbc) {
		jdbc.execute("CREATE TABLE biz_collect_run (id INTEGER PRIMARY KEY, collect_task_id INTEGER)");
		jdbc.execute("CREATE TABLE biz_collect_run_item (id INTEGER PRIMARY KEY, run_id INTEGER, ordinal INTEGER, "
				+ "platform_key TEXT, work_id TEXT, author_uid TEXT, nickname_snapshot TEXT, title_snapshot TEXT, "
				+ "publish_time TEXT, media_type TEXT, decision TEXT, process_state TEXT, error_code TEXT, "
				+ "error_message TEXT, error_detail TEXT, attempt_count INTEGER, max_attempts INTEGER, "
				+ "available_at DATETIME, locked_by TEXT, locked_at DATETIME, started_at DATETIME, finished_at DATETIME, "
				+ "queue_generation TEXT, created_at DATETIME, updated_at DATETIME)");
		jdbc.execute("CREATE TABLE biz_collect_data (id INTEGER PRIMARY KEY, taskname TEXT, "
				+ "lastfetchsnapshot TEXT, lastplanitems TEXT)");
	}

	private JdbcTemplate jdbcTemplate(String filename) throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-" + filename));
		return new JdbcTemplate(dataSource);
	}
}
