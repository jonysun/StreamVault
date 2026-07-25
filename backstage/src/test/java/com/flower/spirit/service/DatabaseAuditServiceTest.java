package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class DatabaseAuditServiceTest {

	@Test
	void auditReportsDuplicateAndDifferentRawColumnsWithoutReturningPayload() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-audit.db"));
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("CREATE TABLE biz_video (id INTEGER PRIMARY KEY, jsonData TEXT, videoinfo TEXT)");
		jdbc.execute("CREATE TABLE biz_graphic_content (id INTEGER PRIMARY KEY, jsonData TEXT)");
		jdbc.execute("CREATE TABLE biz_collect_data (id INTEGER PRIMARY KEY, lastfetchsnapshot TEXT, lastplanitems TEXT)");
		jdbc.update("INSERT INTO biz_video(id,jsonData,videoinfo) VALUES (1,?,?), (2,?,?), (3,?,NULL)",
				"{\"id\":1}", "{\"id\":1}", "{\"id\":2}", "{\"id\":different}", "{\"id\":3}");
		jdbc.update("INSERT INTO biz_graphic_content(id,jsonData) VALUES (1,?)", "graphic");
		jdbc.update("INSERT INTO biz_collect_data(id,lastfetchsnapshot,lastplanitems) VALUES (1,?,?)", "fetch", "plan");

		Map<String, Object> report = new DatabaseAuditService(jdbc).audit();
		Map<String, Object> video = (Map<String, Object>) report.get("video");

		assertThat(((Number) video.get("rowsTotal")).longValue()).isEqualTo(3L);
		assertThat(((Number) video.get("exactEqualRows")).longValue()).isEqualTo(1L);
		assertThat(((Number) video.get("differentRows")).longValue()).isEqualTo(1L);
		assertThat(((Number) video.get("exactDuplicateVideoInfoChars")).longValue()).isEqualTo(8L);
		assertThat(report.get("differentSamples").toString()).doesNotContain("different");
		assertThat(report.get("fingerprint")).asString().startsWith("sha256:");
	}
}
