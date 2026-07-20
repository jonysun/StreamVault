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

		assertThat(statements).hasSize(18);
		assertThat(statements)
				.allMatch(sql -> sql.startsWith("CREATE INDEX IF NOT EXISTS "))
				.anyMatch(sql -> sql.contains("idx_biz_video_publishtime_id"))
				.anyMatch(sql -> sql.contains("idx_biz_video_favorite_id"))
				.anyMatch(sql -> sql.contains("idx_collect_detail_dataid_videoid"))
				.anyMatch(sql -> sql.contains("idx_author_profile_platform_authoruid"))
				.anyMatch(sql -> sql.contains("idx_graphic_content_platform_videoid"))
				.anyMatch(sql -> sql.contains("idx_graphic_content_publishtime_id"))
				.anyMatch(sql -> sql.contains("idx_graphic_content_createtime_id"))
				.anyMatch(sql -> sql.contains("idx_graphic_content_author"))
				.anyMatch(sql -> sql.contains("idx_biz_video_platformkey_videoid"))
				.anyMatch(sql -> sql.contains("idx_graphic_content_platformkey_videoid"))
				.anyMatch(sql -> sql.contains("idx_author_profile_platformkey_authoruid"));
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
