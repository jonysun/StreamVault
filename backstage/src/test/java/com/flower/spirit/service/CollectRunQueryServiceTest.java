package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
				+ "(90, 9, 1, 'new-work', 'video', 'NEW'), (91, 9, 2, 'skip-work', 'graphic', 'SKIP')");
		CollectRunQueryService service = new CollectRunQueryService(jdbc, new SnapshotCodec(4096, 2));

		Map<String, Object> result = service.findLatestItems(4, "plan", 20, 0);

		assertThat(result).containsEntry("source", "run-item").containsEntry("runId", 9L);
		assertThat((List<Map<String, Object>>) result.get("items"))
				.extracting(item -> item.get("workId"))
				.containsExactly("new-work");
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

	private void createSchema(JdbcTemplate jdbc) {
		jdbc.execute("CREATE TABLE biz_collect_run (id INTEGER PRIMARY KEY, collect_task_id INTEGER)");
		jdbc.execute("CREATE TABLE biz_collect_run_item (id INTEGER PRIMARY KEY, run_id INTEGER, ordinal INTEGER, "
				+ "platform_key TEXT, work_id TEXT, author_uid TEXT, nickname_snapshot TEXT, title_snapshot TEXT, "
				+ "publish_time TEXT, media_type TEXT, decision TEXT, process_state TEXT, error_code TEXT, "
				+ "error_message TEXT, created_at DATETIME, updated_at DATETIME)");
		jdbc.execute("CREATE TABLE biz_collect_data (id INTEGER PRIMARY KEY, lastfetchsnapshot TEXT, lastplanitems TEXT)");
	}

	private JdbcTemplate jdbcTemplate(String filename) throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-" + filename));
		return new JdbcTemplate(dataSource);
	}
}
