package com.flower.spirit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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
				.anyMatch(sql -> sql.contains("uq_collect_run_item_work"))
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

		new DatabaseIndexInitializer(jdbcTemplate, statements).initialize();

		verify(jdbcTemplate, times(1)).execute(statements.get(0));
		verify(jdbcTemplate, times(1)).execute(statements.get(1));
	}
}
