package com.flower.spirit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import com.flower.spirit.database.postgresql.DirectDatabaseWriteExecutor;
import com.flower.spirit.service.transaction.DatabaseInitializationTransaction;

class DatabaseIndexInitializerTest {

	@Test
	void defaultIndexStatementsAreIdempotentCreateIndexStatements() {
		List<String> statements = DatabaseIndexInitializer.defaultIndexSqlStatements();

		assertThat(statements).doesNotHaveDuplicates();
		assertThat(statements)
				.allMatch(sql -> sql.startsWith("CREATE INDEX IF NOT EXISTS ")
						|| sql.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS "))
				.anyMatch(sql -> sql.contains("idx_biz_video_publishtime_id"))
				.anyMatch(sql -> sql.contains("idx_biz_video_favorite_id"))
				.anyMatch(sql -> sql.contains("idx_collect_detail_dataid_videoid"))
				.anyMatch(sql -> sql.contains("idx_author_profile_platform_authoruid"))
				.anyMatch(sql -> sql.contains("idx_graphic_content_platform_videoid"))
				.anyMatch(sql -> sql.contains("idx_graphic_content_publishtime_id"))
				.anyMatch(sql -> sql.contains("idx_graphic_content_createtime_id"))
				.anyMatch(sql -> sql.contains("idx_graphic_content_author"))
				.anyMatch(sql -> sql.contains("idx_biz_video_platformkey_videoid"))
				.anyMatch(sql -> sql.contains("idx_biz_video_author_identity"))
				.anyMatch(sql -> sql.contains("idx_biz_video_author_feed"))
				.anyMatch(sql -> sql.contains("idx_graphic_content_platformkey_videoid"))
				.anyMatch(sql -> sql.contains("idx_graphic_content_author_identity"))
				.anyMatch(sql -> sql.contains("idx_graphic_content_author_feed"))
				.anyMatch(sql -> sql.contains("idx_author_profile_platformkey_authoruid"))
				.anyMatch(sql -> sql.contains("uq_author_enrichment_active"))
				.anyMatch(sql -> sql.contains("idx_author_enrichment_due"))
				.anyMatch(sql -> sql.contains("uq_collect_run_active_task"))
				.anyMatch(sql -> sql.contains("idx_collect_run_task_created"))
				.contains(
						"CREATE INDEX IF NOT EXISTS idx_collect_run_item_download_claim ON biz_collect_run_item(queue_generation, process_state, available_at, ordinal, created_at, id)",
						"CREATE INDEX IF NOT EXISTS idx_collect_run_item_active_work ON biz_collect_run_item(platform_key, work_id, process_state)",
						"CREATE INDEX IF NOT EXISTS idx_collect_run_item_run_state ON biz_collect_run_item(run_id, process_state)")
				.anyMatch(sql -> sql.contains("uq_collect_run_event_sequence"))
				.anyMatch(sql -> sql.contains("uq_job_queue_active_dedupe"))
				.anyMatch(sql -> sql.contains("idx_job_queue_claim"));
	}

	@Test
	void initializeContinuesWhenOneIndexFails() {
		JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		List<String> statements = List.of(
				"CREATE INDEX IF NOT EXISTS idx_first ON table_one(column_one)",
				"CREATE INDEX IF NOT EXISTS idx_second ON table_two(column_two)");
		doThrow(new RuntimeException("boom")).when(jdbcTemplate).execute(statements.get(0));

		new DatabaseIndexInitializer(jdbcTemplate, statements, new DatabaseInitializationTransaction(jdbcTemplate),
				new DirectDatabaseWriteExecutor()).initialize();

		verify(jdbcTemplate, times(1)).execute(statements.get(0));
		verify(jdbcTemplate, times(1)).execute(statements.get(1));
	}

	@Test
	void initializeCreatesPipelineIndexesWithOrderedColumns() throws Exception {
		JdbcTemplate jdbcTemplate = jdbcTemplate("pipeline-indexes.db");
		createMinimalIndexedTables(jdbcTemplate);

		new DatabaseIndexInitializer(jdbcTemplate, new DatabaseInitializationTransaction(jdbcTemplate),
				new DirectDatabaseWriteExecutor()).initialize();

		assertThat(indexNames(jdbcTemplate, "biz_collect_run_item")).contains(
				"idx_collect_run_item_download_claim",
				"idx_collect_run_item_active_work",
				"idx_collect_run_item_run_state");
		assertThat(indexColumns(jdbcTemplate, "idx_collect_run_item_download_claim")).containsExactly(
				"queue_generation", "process_state", "available_at", "ordinal", "created_at", "id");
		assertThat(indexColumns(jdbcTemplate, "idx_collect_run_item_active_work")).containsExactly(
				"platform_key", "work_id", "process_state");
		assertThat(indexColumns(jdbcTemplate, "idx_collect_run_item_run_state")).containsExactly(
				"run_id", "process_state");
	}

	private JdbcTemplate jdbcTemplate(String filename) throws Exception {
		Path databaseDirectory = Path.of("target", "test-databases");
		Files.createDirectories(databaseDirectory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + databaseDirectory.resolve(UUID.randomUUID() + "-" + filename));
		return new JdbcTemplate(dataSource);
	}

	private void createMinimalIndexedTables(JdbcTemplate jdbcTemplate) {
		jdbcTemplate.execute("CREATE TABLE biz_video (id INTEGER, publishtime TEXT, createtime TEXT, "
				+ "videoauthor TEXT, videoplatform TEXT, videoid TEXT, platformkey TEXT, authoruid TEXT, "
				+ "secuid TEXT, favorite TEXT)");
		jdbcTemplate.execute("CREATE TABLE biz_collect_data_detail (dataid INTEGER, videoid TEXT, status TEXT, "
				+ "mediatype TEXT)");
		jdbcTemplate.execute("CREATE TABLE biz_author_profile (platform TEXT, platformkey TEXT, authoruid TEXT)");
		jdbcTemplate.execute("CREATE TABLE biz_author_enrichment_job (id INTEGER, platform_key TEXT, "
				+ "author_uid TEXT, state TEXT, next_attempt_at TEXT, priority INTEGER)");
		jdbcTemplate.execute("CREATE TABLE biz_collect_run (id INTEGER, collect_task_id INTEGER, state TEXT, "
				+ "created_at TEXT, heartbeat_at TEXT)");
		jdbcTemplate.execute("CREATE TABLE biz_collect_run_item (id INTEGER, run_id INTEGER, platform_key TEXT, "
				+ "work_id TEXT, ordinal INTEGER, queue_generation TEXT, process_state TEXT, available_at TEXT, "
				+ "created_at TEXT)");
		jdbcTemplate.execute("CREATE TABLE biz_collect_run_event (run_id INTEGER, sequence INTEGER)");
		jdbcTemplate.execute("CREATE TABLE biz_job_queue (id INTEGER, job_type TEXT, dedupe_key TEXT, state TEXT, "
				+ "available_at TEXT, priority INTEGER)");
		jdbcTemplate.execute("CREATE TABLE biz_graphic_content (id INTEGER, platform TEXT, platformkey TEXT, "
				+ "videoid TEXT, authoruid TEXT, secuid TEXT, publishtime TEXT, createtime TEXT, author TEXT)");
	}

	private List<String> indexNames(JdbcTemplate jdbcTemplate, String table) {
		return jdbcTemplate.queryForList("PRAGMA index_list(" + table + ")").stream()
				.map(row -> String.valueOf(row.get("name")))
				.toList();
	}

	private List<String> indexColumns(JdbcTemplate jdbcTemplate, String index) {
		return jdbcTemplate.queryForList("PRAGMA index_info(" + index + ")").stream()
				.sorted((left, right) -> Integer.compare(((Number) left.get("seqno")).intValue(),
						((Number) right.get("seqno")).intValue()))
				.map(row -> String.valueOf(row.get("name")))
				.toList();
	}
}
