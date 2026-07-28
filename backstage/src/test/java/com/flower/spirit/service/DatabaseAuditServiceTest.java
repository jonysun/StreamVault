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

class DatabaseAuditServiceTest {

	@Test
	void auditReportsEveryRawPayloadStateWithoutReturningPayload() throws Exception {
		JdbcTemplate jdbc = database("raw-payload-audit.db");
		dropOptionalTables(jdbc);
		jdbc.update("INSERT INTO biz_video(id, platformkey, videoid, jsonData, videoinfo) "
				+ "VALUES (1,'douyin','work-1',?,?), (2,'douyin','work-2',?,?), "
				+ "(3,'douyin','work-3',?,NULL), (4,'douyin','work-4',NULL,?), "
				+ "(5,'douyin','work-5',NULL,NULL)",
				"{\"id\":1}", "{\"id\":1}", "{\"id\":2}", "{\"id\":different}",
				"{\"id\":3}", "{\"id\":4}");
		jdbc.update("INSERT INTO biz_graphic_content(id,platformkey,videoid,jsonData) "
				+ "VALUES (1,'douyin','graphic-1',?)", "graphic");
		jdbc.update("INSERT INTO biz_collect_data(id,lastfetchsnapshot,lastplanitems) VALUES (1,?,?)", "fetch", "plan");

		Map<String, Object> report = new DatabaseAuditService(jdbc).audit();
		Map<String, Object> video = map(report.get("video"));

		assertThat(number(video.get("rowsTotal"))).isEqualTo(5L);
		assertThat(number(video.get("exactEqualRows"))).isEqualTo(1L);
		assertThat(number(video.get("differentRows"))).isEqualTo(1L);
		assertThat(number(video.get("jsonOnlyRows"))).isEqualTo(1L);
		assertThat(number(video.get("videoInfoOnlyRows"))).isEqualTo(1L);
		assertThat(number(video.get("emptyRawRows"))).isEqualTo(1L);
		assertThat(number(video.get("exactDuplicateVideoInfoChars"))).isEqualTo(8L);
		assertThat(report.get("differentSamples").toString()).doesNotContain("different");
		assertThat(report.toString()).doesNotContain("{\"id\":1}", "{\"id\":2}", "{\"id\":3}");
		assertThat(map(report.get("orphans")).values()).containsOnly(0L);
		assertThat(map(report.get("retentionCandidates")))
				.containsEntry("runItems", 0L)
				.containsEntry("terminalRuns", 0L)
				.containsEntry("runEvents", 0L)
				.containsEntry("terminalJobs", 0L);
		assertThat(report.get("fingerprint")).asString().startsWith("sha256:");
	}

	@Test
	void auditReportsDuplicateKeysConflictsOrphansAndRetentionWithoutWriting() throws Exception {
		JdbcTemplate jdbc = database("data-quality-audit.db");
		jdbc.update("INSERT INTO biz_video(id,platformkey,videoplatform,videoid,contenttype,videoaddr) VALUES "
				+ "(1,'douyin','抖音','video-duplicate','video','/media/first.mp4'),"
				+ "(2,'douyin','抖音','video-duplicate','video','/media/second.mp4'),"
				+ "(3,NULL,'抖音','video-duplicate','video','/media/third.mp4')");
		jdbc.update("INSERT INTO biz_graphic_content(id,platformkey,platform,videoid,contenttype,images) VALUES "
				+ "(1,'douyin','抖音','graphic-duplicate','graphic','[\"/media/a.jpg\"]'),"
				+ "(2,'douyin','抖音','graphic-duplicate','graphic','[\"/media/a.jpg\"]'),"
				+ "(3,NULL,'抖音','graphic-unknown','graphic','[\"/media/b.jpg\"]')");
		jdbc.update("INSERT INTO biz_author_profile(id,platformkey) VALUES (1,'douyin'), (2,NULL)");
		jdbc.update("INSERT INTO biz_author_name_history(id,authorprofileid) VALUES (1,1), (2,999)");
		jdbc.update("INSERT INTO biz_collect_data(id,lastfetchsnapshot,lastplanitems) VALUES (1,NULL,NULL)");
		jdbc.update("INSERT INTO biz_collect_data_detail(id,dataid) VALUES (1,1), (2,999)");
		jdbc.update("INSERT INTO biz_collect_run(id,state,created_at) VALUES "
				+ "(1,'COMPLETED','2020-01-01 00:00:00'), (2,'PROCESSING',CURRENT_TIMESTAMP)");
		jdbc.update("INSERT INTO biz_collect_run_item(id,run_id,process_state,created_at) VALUES "
				+ "(1,1,'COMPLETED','2020-01-01 00:00:00'), (2,999,'FAILED','2020-01-01 00:00:00')");
		jdbc.update("INSERT INTO biz_collect_run_event(id,run_id,created_at) VALUES "
				+ "(1,1,'2020-01-01 00:00:00'), (2,999,CURRENT_TIMESTAMP)");
		jdbc.update("INSERT INTO biz_job_queue(id,state,created_at) VALUES "
				+ "(1,'COMPLETED','2020-01-01 00:00:00'), (2,'RUNNING','2020-01-01 00:00:00')");
		Map<String, Long> before = tableCounts(jdbc);

		Map<String, Object> report = new DatabaseAuditService(jdbc).audit();

		Map<String, Object> duplicates = map(report.get("workDuplicates"));
		Map<String, Object> videos = map(duplicates.get("video"));
		Map<String, Object> graphics = map(duplicates.get("graphic"));
		assertThat(number(videos.get("candidateGroups"))).isEqualTo(1L);
		assertThat(number(videos.get("candidateRows"))).isEqualTo(2L);
		assertThat(number(videos.get("mediaReferenceConflictGroups"))).isEqualTo(1L);
		assertThat(number(graphics.get("candidateGroups"))).isEqualTo(1L);
		assertThat(number(graphics.get("candidateRows"))).isEqualTo(2L);
		assertThat(number(graphics.get("mediaReferenceConflictGroups"))).isZero();
		assertThat(list(videos.get("samples"))).singleElement().satisfies(sample -> {
			assertThat(sample).containsEntry("platformKey", "douyin")
					.containsEntry("workId", "video-duplicate");
			assertThat(sample.keySet()).containsExactly(
					"platformKey", "workId", "rowCount", "rowIds", "distinctMediaReferences");
			assertThat(sample.toString()).doesNotContain("/media/");
		});

		assertThat(map(report.get("normalization")))
				.containsEntry("videoMissingPlatformKeyRows", 1L)
				.containsEntry("graphicMissingPlatformKeyRows", 1L)
				.containsEntry("authorMissingPlatformKeyRows", 1L);
		assertThat(map(report.get("orphans")))
				.containsEntry("runItemsWithoutRun", 1L)
				.containsEntry("runEventsWithoutRun", 1L)
				.containsEntry("authorNameHistoryWithoutProfile", 1L)
				.containsEntry("collectDetailsWithoutTask", 1L);
		assertThat(map(report.get("retentionCandidates")))
				.containsEntry("runItems", 2L)
				.containsEntry("terminalRuns", 1L)
				.containsEntry("runEvents", 1L)
				.containsEntry("terminalJobs", 1L)
				.containsEntry("nonFailedRunItemDays", 90L)
				.containsEntry("failedRunItemDays", 365L)
				.containsEntry("terminalHistoryDays", 90L);
		assertThat(tableCounts(jdbc)).isEqualTo(before);
		assertThat(report.toString()).doesNotContain("/media/first.mp4", "/media/second.mp4", "a.jpg");

		jdbc.update("INSERT INTO biz_video(id,platformkey,videoid) VALUES "
				+ "(4,'douyin','another-duplicate'), (5,'douyin','another-duplicate')");
		assertThat(new DatabaseAuditService(jdbc).audit().get("fingerprint"))
				.isNotEqualTo(report.get("fingerprint"));
	}

	private void dropOptionalTables(JdbcTemplate jdbc) {
		jdbc.execute("DROP TABLE biz_collect_data_detail");
		jdbc.execute("DROP TABLE biz_author_name_history");
		jdbc.execute("DROP TABLE biz_author_profile");
		jdbc.execute("DROP TABLE biz_collect_run_item");
		jdbc.execute("DROP TABLE biz_collect_run_event");
		jdbc.execute("DROP TABLE biz_collect_run");
		jdbc.execute("DROP TABLE biz_job_queue");
	}

	private JdbcTemplate database(String filename) throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-" + filename));
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("CREATE TABLE biz_video (id INTEGER PRIMARY KEY, jsonData TEXT, videoinfo TEXT, "
				+ "platformkey TEXT, videoplatform TEXT, videoid TEXT, contenttype TEXT, videoaddr TEXT)");
		jdbc.execute("CREATE TABLE biz_graphic_content (id INTEGER PRIMARY KEY, jsonData TEXT, "
				+ "platformkey TEXT, platform TEXT, videoid TEXT, contenttype TEXT, images TEXT)");
		jdbc.execute("CREATE TABLE biz_collect_data "
				+ "(id INTEGER PRIMARY KEY, lastfetchsnapshot TEXT, lastplanitems TEXT)");
		jdbc.execute("CREATE TABLE biz_collect_data_detail (id INTEGER PRIMARY KEY, dataid INTEGER)");
		jdbc.execute("CREATE TABLE biz_author_profile (id INTEGER PRIMARY KEY, platformkey TEXT)");
		jdbc.execute("CREATE TABLE biz_author_name_history (id INTEGER PRIMARY KEY, authorprofileid INTEGER)");
		jdbc.execute("CREATE TABLE biz_collect_run (id INTEGER PRIMARY KEY, state TEXT, created_at TEXT)");
		jdbc.execute("CREATE TABLE biz_collect_run_item "
				+ "(id INTEGER PRIMARY KEY, run_id INTEGER, process_state TEXT, created_at TEXT)");
		jdbc.execute("CREATE TABLE biz_collect_run_event "
				+ "(id INTEGER PRIMARY KEY, run_id INTEGER, created_at TEXT)");
		jdbc.execute("CREATE TABLE biz_job_queue (id INTEGER PRIMARY KEY, state TEXT, created_at TEXT)");
		return jdbc;
	}

	private Map<String, Long> tableCounts(JdbcTemplate jdbc) {
		return Map.of(
				"video", count(jdbc, "biz_video"),
				"graphic", count(jdbc, "biz_graphic_content"),
				"run", count(jdbc, "biz_collect_run"),
				"item", count(jdbc, "biz_collect_run_item"),
				"event", count(jdbc, "biz_collect_run_event"),
				"job", count(jdbc, "biz_job_queue"));
	}

	private long count(JdbcTemplate jdbc, String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}

	private long number(Object value) {
		return ((Number) value).longValue();
	}
}
