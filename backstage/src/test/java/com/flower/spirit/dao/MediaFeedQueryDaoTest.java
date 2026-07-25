package com.flower.spirit.dao;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.sqlite.SQLiteDataSource;

import com.flower.spirit.dto.FeedCursor;
import com.flower.spirit.dto.MediaFeedRequest;
import com.flower.spirit.dto.MediaFeedRow;

class MediaFeedQueryDaoTest {

	@Test
	void mixedKeysetUsesStableTieBreakersWithoutReadingRawPayloadColumns() throws Exception {
		CapturingJdbcTemplate jdbc = database();
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_video(id, videoid, platformkey, videoplatform, publishtime, createtime, "
				+ "videounrealaddr, videoaddr, jsonData, videoinfo) VALUES "
				+ "(3, 'video-3', 'douyin', 'douyin', '2026-07-25 10:00:00', 1, '/v3.mp4', 'v3.mp4', ?, ?), "
				+ "(2, 'video-2', 'douyin', 'douyin', '2026-07-25 09:00:00', 1, '/v2.mp4', 'v2.mp4', ?, ?)",
				"x".repeat(100000), "x".repeat(100000), "y".repeat(100000), "y".repeat(100000));
		jdbc.update("INSERT INTO biz_graphic_content(id, videoid, platformkey, platform, publishtime, createtime, images, jsonData) "
				+ "VALUES (4, 'graphic-4', 'douyin', 'douyin', '2026-07-25 10:00:00', 1, '[\"/g4.jpg\"]', ?)",
				"z".repeat(100000));
		MediaFeedQueryDao dao = new MediaFeedQueryDao(jdbc);
		MediaFeedRequest request = request("mixed", "desc");

		List<MediaFeedRow> first = dao.find(request, null, 2);
		MediaFeedRow last = first.get(first.size() - 1);
		FeedCursor cursor = new FeedCursor(Instant.ofEpochMilli(last.sortTimeMillis()), last.mediaType(),
				last.internalId(), "desc", "hash");
		List<MediaFeedRow> second = dao.find(request, cursor, 2);

		assertThat(first).extracting(MediaFeedRow::mediaKey).containsExactly("graphic:4", "video:3");
		assertThat(second).extracting(MediaFeedRow::mediaKey).containsExactly("video:2");
		assertThat(jdbc.queries).hasSize(4).allSatisfy(sql -> assertThat(sql)
				.doesNotContain("jsonData", "videoinfo", "metadataoverrides", "UNION ALL", "strftime"));
	}

	@Test
	void ascendingCursorContinuesAfterGraphicBeforeVideoAtSamePublishTime() throws Exception {
		CapturingJdbcTemplate jdbc = database();
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_video(id, videoid, publishtime) VALUES "
				+ "(2, 'video-2', '2026-07-25 09:00:00'), (3, 'video-3', '2026-07-25 10:00:00')");
		jdbc.update("INSERT INTO biz_graphic_content(id, videoid, publishtime, images) "
				+ "VALUES (4, 'graphic-4', '2026-07-25 10:00:00', '[\"/g4.jpg\"]')");
		MediaFeedQueryDao dao = new MediaFeedQueryDao(jdbc);
		MediaFeedRequest request = request("mixed", "asc");

		List<MediaFeedRow> first = dao.find(request, null, 2);
		MediaFeedRow last = first.get(first.size() - 1);
		FeedCursor cursor = new FeedCursor(Instant.ofEpochMilli(last.sortTimeMillis()), last.mediaType(),
				last.internalId(), "asc", "hash");
		List<MediaFeedRow> second = dao.find(request, cursor, 2);

		assertThat(first).extracting(MediaFeedRow::mediaKey).containsExactly("video:2", "graphic:4");
		assertThat(second).extracting(MediaFeedRow::mediaKey).containsExactly("video:3");
	}

	@Test
	void authorScopeRequiresBothCanonicalPlatformAndUidInSql() throws Exception {
		CapturingJdbcTemplate jdbc = database();
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_video(id, videoid, platformkey, videoplatform, secuid, authoruid, publishtime) "
				+ "VALUES (1, 'wanted', 'douyin', 'douyin', 'MS4wanted', 'MS4wanted', '2026-07-25 10:00:00'), "
				+ "(2, 'other', 'douyin', 'douyin', 'MS4other', 'MS4other', '2026-07-25 11:00:00')");
		MediaFeedRequest request = request("video", "desc");
		request.setPlatformKey("douyin");
		request.setAuthorUid("MS4wanted");

		List<MediaFeedRow> rows = new MediaFeedQueryDao(jdbc).find(request, null, 10);

		assertThat(rows).extracting(MediaFeedRow::workId).containsExactly("wanted");
		assertThat(jdbc.queries).singleElement().satisfies(sql -> assertThat(sql)
				.contains("platformkey = ?", "COALESCE(NULLIF(secuid,''), authoruid) = ?"));
		assertThat(explain(jdbc, jdbc.calls.get(0)))
				.contains("idx_biz_video_author_feed")
				.doesNotContain("USE TEMP B-TREE", "SCAN biz_video");
	}

	@Test
	void unfilteredBranchesUsePublishTimeIndexesWithoutTemporarySort() throws Exception {
		CapturingJdbcTemplate jdbc = database();
		createSchema(jdbc);
		new MediaFeedQueryDao(jdbc).find(request("mixed", "desc"), null, 51);

		assertThat(jdbc.calls).hasSize(2);
		assertThat(explain(jdbc, jdbc.calls.get(0)))
				.contains("idx_biz_video_publishtime_id")
				.doesNotContain("USE TEMP B-TREE");
		assertThat(explain(jdbc, jdbc.calls.get(1)))
				.contains("idx_graphic_content_publishtime_id")
				.doesNotContain("USE TEMP B-TREE");
	}

	private String explain(JdbcTemplate jdbc, QueryCall call) {
		List<Map<String, Object>> rows = jdbc.queryForList("EXPLAIN QUERY PLAN " + call.sql(), call.args());
		return rows.toString();
	}

	private MediaFeedRequest request(String type, String order) {
		MediaFeedRequest request = new MediaFeedRequest();
		request.setType(type);
		request.setOrder(order);
		return request;
	}

	private CapturingJdbcTemplate database() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-media-feed.db"));
		return new CapturingJdbcTemplate(dataSource);
	}

	private void createSchema(JdbcTemplate jdbc) {
		jdbc.execute("CREATE TABLE biz_video (id INTEGER PRIMARY KEY, videoid TEXT, platformkey TEXT, videoplatform TEXT, "
				+ "secuid TEXT, authoruid TEXT, authorusername TEXT, uniqueid TEXT, videoauthor TEXT, authoravatar TEXT, "
				+ "videoname TEXT, videodesc TEXT, publishtime TEXT, createtime, videocover TEXT, videoaddr TEXT, "
				+ "videounrealaddr TEXT, sourceurl TEXT, originaladdress TEXT, favorite TEXT, videoprivacy TEXT, "
				+ "contenttype TEXT, authorhomepage TEXT, jsonData TEXT, videoinfo TEXT, metadataoverrides TEXT)");
		jdbc.execute("CREATE TABLE biz_graphic_content (id INTEGER PRIMARY KEY, videoid TEXT, platformkey TEXT, platform TEXT, "
				+ "secuid TEXT, authoruid TEXT, authorusername TEXT, uniqueid TEXT, author TEXT, authoravatar TEXT, "
				+ "title TEXT, content TEXT, publishtime TEXT, createtime, images TEXT, sourceurl TEXT, originaladdress TEXT, "
				+ "favorite TEXT, privacy TEXT, contenttype TEXT, authorhomepage TEXT, jsonData TEXT, metadataoverrides TEXT)");
		jdbc.execute("CREATE INDEX idx_biz_video_publishtime_id ON biz_video(publishtime, id)");
		jdbc.execute("CREATE INDEX idx_graphic_content_publishtime_id ON biz_graphic_content(publishtime, id)");
		jdbc.execute("CREATE INDEX idx_biz_video_author_feed "
				+ "ON biz_video(platformkey, COALESCE(NULLIF(secuid,''), authoruid), publishtime, id)");
		jdbc.execute("CREATE INDEX idx_graphic_content_author_feed "
				+ "ON biz_graphic_content(platformkey, COALESCE(NULLIF(secuid,''), authoruid), publishtime, id)");
	}

	private static final class CapturingJdbcTemplate extends JdbcTemplate {
		private final List<String> queries = new ArrayList<>();
		private final List<QueryCall> calls = new ArrayList<>();

		private CapturingJdbcTemplate(SQLiteDataSource dataSource) {
			super(dataSource);
		}

		@Override
		public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
			queries.add(sql);
			calls.add(new QueryCall(sql, args == null ? new Object[0] : Arrays.copyOf(args, args.length)));
			return super.query(sql, rowMapper, args);
		}
	}

	private record QueryCall(String sql, Object[] args) {
	}
}
