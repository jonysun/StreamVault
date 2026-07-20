package com.flower.spirit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class PlatformSchemaInitializerTest {

	@TempDir
	Path tempDir;

	@Test
	void upgradesOldSchemaAndBackfillsCanonicalKeysWithoutChangingLegacyValues() {
		JdbcTemplate jdbcTemplate = jdbcTemplate("upgrade.db");
		createLegacyTables(jdbcTemplate);
		jdbcTemplate.update("INSERT INTO biz_video(id, videoplatform, videoid, sourceurl) VALUES(1, '抖音', 'v1', 'work-1')");
		jdbcTemplate.update("INSERT INTO biz_video(id, videoplatform, videoid, sourceurl) VALUES(2, 'UnknownSite', 'v2', 'work-2')");
		jdbcTemplate.update("INSERT INTO biz_graphic_content(id, platform, videoid, sourceurl) VALUES(1, 'rednote', 'g1', 'graphic-1')");
		jdbcTemplate.update("INSERT INTO biz_author_profile(id, platform, authoruid, displayname) VALUES(1, 'YouTube', 'a1', 'Author')");

		PlatformSchemaInitializer initializer = new PlatformSchemaInitializer(jdbcTemplate);
		initializer.initialize();
		initializer.initialize();

		assertThat(columnNames(jdbcTemplate, "biz_video")).contains("platformkey", "contenttype",
				"authorhomepage", "metadataoverrides", "metadataeditedat", "metadataeditedby");
		assertThat(columnNames(jdbcTemplate, "biz_graphic_content")).contains("platformkey", "contenttype",
				"authorhomepage", "metadataoverrides", "metadataeditedat", "metadataeditedby", "privacy", "favorite");
		assertThat(columnNames(jdbcTemplate, "biz_author_profile")).contains("platformkey", "signature");

		Map<String, Object> video = jdbcTemplate.queryForMap(
				"SELECT videoplatform, videoid, sourceurl, platformkey FROM biz_video WHERE id = 1");
		assertThat(video).containsEntry("videoplatform", "抖音")
				.containsEntry("videoid", "v1")
				.containsEntry("sourceurl", "work-1")
				.containsEntry("platformkey", "douyin");
		assertThat(jdbcTemplate.queryForObject("SELECT platformkey FROM biz_video WHERE id = 2", String.class)).isNull();
		assertThat(jdbcTemplate.queryForObject("SELECT platformkey FROM biz_graphic_content WHERE id = 1", String.class))
				.isEqualTo("xiaohongshu");
		assertThat(jdbcTemplate.queryForObject("SELECT platformkey FROM biz_author_profile WHERE id = 1", String.class))
				.isEqualTo("youtube");
	}

	@Test
	void missingOptionalTablesDoNotAbortInitialization() {
		JdbcTemplate jdbcTemplate = jdbcTemplate("missing.db");

		assertThatCode(() -> new PlatformSchemaInitializer(jdbcTemplate).initialize()).doesNotThrowAnyException();
	}

	private JdbcTemplate jdbcTemplate(String filename) {
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve(filename));
		return new JdbcTemplate(dataSource);
	}

	private void createLegacyTables(JdbcTemplate jdbcTemplate) {
		jdbcTemplate.execute("CREATE TABLE biz_video (id INTEGER PRIMARY KEY, videoplatform varchar(255), videoid varchar(255), sourceurl varchar(255))");
		jdbcTemplate.execute("CREATE TABLE biz_graphic_content (id INTEGER PRIMARY KEY, platform varchar(255), videoid varchar(255), sourceurl varchar(255))");
		jdbcTemplate.execute("CREATE TABLE biz_author_profile (id INTEGER PRIMARY KEY, platform varchar(255), authoruid varchar(255), displayname varchar(255))");
	}

	private List<String> columnNames(JdbcTemplate jdbcTemplate, String table) {
		return jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")").stream()
				.map(row -> String.valueOf(row.get("name")))
				.toList();
	}
}
