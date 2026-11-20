package com.flower.spirit.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.alibaba.fastjson.JSON;
import com.flower.spirit.dto.FeedCursor;
import com.flower.spirit.dto.MediaFeedRequest;
import com.flower.spirit.dto.MediaFeedRow;
import com.flower.spirit.dto.MediaSlideRow;

@Repository
public class MediaFeedQueryDao {

	private static final List<String> IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp", ".gif");
	private static final List<String> VIDEO_EXTENSIONS = List.of(".mp4", ".webm", ".mov", ".m4v");
	private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final JdbcTemplate jdbcTemplate;

	public MediaFeedQueryDao(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<MediaFeedRow> find(MediaFeedRequest request, FeedCursor cursor, int limitPlusOne) {
		List<MediaFeedRow> rows = new ArrayList<>(limitPlusOne * 2);
		boolean byDownloadTime = isDownloadTime(request.getSortBy());
		if (!"graphic".equals(request.getType())) {
			rows.addAll(findVideoRows(request, cursor, limitPlusOne, byDownloadTime));
		}
		if (!"video".equals(request.getType())) {
			rows.addAll(findGraphicRows(request, cursor, limitPlusOne, byDownloadTime));
		}
		rows.sort(feedComparator(request.getOrder(), byDownloadTime));
		return rows.size() <= limitPlusOne ? rows : new ArrayList<>(rows.subList(0, limitPlusOne));
	}

	/** 排序键是不是「下载时间」。{@code publishtime}（作者发布时间）是默认值。 */
	public static boolean isDownloadTime(String sortBy) {
		return "createtime".equalsIgnoreCase(sortBy);
	}

	private List<MediaFeedRow> findVideoRows(MediaFeedRequest request, FeedCursor cursor, int limit,
			boolean byDownloadTime) {
		StringBuilder sql = new StringBuilder("SELECT publishtime AS sort_time_text, ")
				.append("'video' AS media_type, id AS internal_id, platformkey AS platform_key, ")
				.append("videoplatform AS platform_display_name, videoid AS work_id, ")
				.append("COALESCE(NULLIF(secuid,''), authoruid) AS author_uid, ")
				.append("COALESCE(NULLIF(authorusername,''), uniqueid) AS author_username, ")
				.append("videoauthor AS author_display_name, authoravatar AS author_avatar, videoname AS title, ")
				.append("videodesc AS summary, publishtime AS publish_time, createtime AS downloaded_at, ")
				.append("videocover AS cover_url, videounrealaddr AS fallback_url, sourceurl AS source_url, ")
				.append("originaladdress AS original_address, favorite, videoprivacy AS privacy, ")
				.append("contenttype AS content_type, authorhomepage AS author_homepage, NULL AS slides_json, ")
				.append("videoaddr AS local_path, NULL AS graphic_media_root FROM biz_video");
		return queryBranch(sql, request, cursor, "video", limit, byDownloadTime);
	}

	private List<MediaFeedRow> findGraphicRows(MediaFeedRequest request, FeedCursor cursor, int limit,
			boolean byDownloadTime) {
		StringBuilder sql = new StringBuilder("SELECT publishtime AS sort_time_text, ")
				.append("'graphic' AS media_type, id AS internal_id, platformkey AS platform_key, ")
				.append("platform AS platform_display_name, videoid AS work_id, ")
				.append("COALESCE(NULLIF(secuid,''), authoruid) AS author_uid, ")
				.append("COALESCE(NULLIF(authorusername,''), uniqueid) AS author_username, ")
				.append("author AS author_display_name, authoravatar AS author_avatar, title, content AS summary, ")
				.append("publishtime AS publish_time, createtime AS downloaded_at, NULL AS cover_url, ")
				.append("NULL AS fallback_url, sourceurl AS source_url, originaladdress AS original_address, ")
				.append("favorite, privacy, contenttype AS content_type, authorhomepage AS author_homepage, ")
				.append("images AS slides_json, NULL AS local_path, markroute AS graphic_media_root FROM biz_graphic_content");
		return queryBranch(sql, request, cursor, "graphic", limit, byDownloadTime);
	}

	private List<MediaFeedRow> queryBranch(StringBuilder sql, MediaFeedRequest request, FeedCursor cursor,
			String mediaType, int limit, boolean byDownloadTime) {
		List<Object> parameters = new ArrayList<>();
		appendAuthorWhere(sql, parameters, request);
		appendCursor(sql, parameters, cursor, request.getOrder(), mediaType, byDownloadTime);
		boolean ascending = "asc".equals(request.getOrder());
		if (byDownloadTime) {
			sql.append(" ORDER BY createtime ").append(ascending ? "ASC NULLS LAST" : "DESC NULLS LAST")
					.append(", id ").append(ascending ? "ASC" : "DESC").append(" LIMIT ?");
		} else {
			sql.append(" ORDER BY publishtime ").append(ascending ? "ASC NULLS LAST" : "DESC NULLS LAST")
					.append(", id ").append(ascending ? "ASC" : "DESC").append(" LIMIT ?");
		}
		parameters.add(limit);
		return jdbcTemplate.query(sql.toString(), (row, index) -> mapRow(row, index, byDownloadTime),
				parameters.toArray());
	}

	private void appendAuthorWhere(StringBuilder sql, List<Object> parameters, MediaFeedRequest request) {
		List<String> filters = new ArrayList<>();
		if (hasText(request.getPlatformKey())) {
			filters.add("platformkey = ?");
			parameters.add(request.getPlatformKey());
		}
		if (hasText(request.getAuthorUid())) {
			filters.add("COALESCE(NULLIF(secuid,''), authoruid) = ?");
			parameters.add(request.getAuthorUid());
		}
		if (!filters.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", filters));
	}

	private void appendCursor(StringBuilder sql, List<Object> parameters, FeedCursor cursor, String order,
			String mediaType, boolean byDownloadTime) {
		if (cursor == null) return;
		boolean ascending = "asc".equals(order);
		String timeOperator = ascending ? ">" : "<";
		String idOperator = ascending ? ">" : "<";
		String prefix = sql.indexOf(" WHERE ") >= 0 ? " AND " : " WHERE ";
		int mediaComparison = mediaType.compareTo(cursor.mediaType());
		if (byDownloadTime) {
			appendDownloadTimeCursor(sql, parameters, cursor, mediaType, mediaComparison, timeOperator,
					idOperator, prefix);
			return;
		}
		String cursorTime = LOCAL_TIME.format(LocalDateTime.ofInstant(cursor.sortTime(), ZoneId.systemDefault()));
		if (cursor.nullTime()) {
			// Missing publish times form the final bucket in either direction.
			if (mediaComparison > 0) {
				sql.append(prefix).append("(publishtime IS NULL OR publishtime = '')");
			} else if (mediaComparison == 0) {
				sql.append(prefix).append("((publishtime IS NULL OR publishtime = '') AND id ")
						.append(idOperator).append(" ?)");
				parameters.add(cursor.internalId());
			} else {
				sql.append(prefix).append("1 = 0");
			}
		} else if (mediaComparison > 0) {
			// A later media type wins the tie at the cursor timestamp.
			sql.append(prefix).append("(NULLIF(publishtime, '') ").append(timeOperator)
					.append(" ? OR NULLIF(publishtime, '') = ? OR publishtime IS NULL OR publishtime = '')");
			parameters.add(cursorTime);
			parameters.add(cursorTime);
		} else if (mediaComparison < 0) {
			sql.append(prefix).append("(NULLIF(publishtime, '') ").append(timeOperator)
					.append(" ? OR publishtime IS NULL OR publishtime = '')");
			parameters.add(cursorTime);
		} else {
			sql.append(prefix).append("(NULLIF(publishtime, '') ").append(timeOperator)
					.append(" ? OR publishtime IS NULL OR publishtime = '' OR (publishtime = ? AND id ").append(idOperator).append(" ?))");
			parameters.add(cursorTime);
			parameters.add(cursorTime);
			parameters.add(cursor.internalId());
		}
	}

	/**
	 * 按下载时间（createtime）续页。
	 *
	 * 跟发布时间那套一模一样的结构，只是比较的列换成了 createtime：
	 * - `createtime` 是**时间戳列**（SQLite 存 epoch 毫秒整数，Postgres 存 timestamp），
	 *   所以游标参数绑 {@link java.sql.Timestamp} 就行 —— sqlite-jdbc 的 setTimestamp
	 *   本来就存成整数毫秒，两边都能直接比（见 {@code SqliteCreatetimeStorageTest}）。
	 * - 缺失的 createtime 跟缺失的发布时间一样，排最后。
	 */
	private void appendDownloadTimeCursor(StringBuilder sql, List<Object> parameters, FeedCursor cursor,
			String mediaType, int mediaComparison, String timeOperator, String idOperator, String prefix) {
		java.sql.Timestamp cursorTime = java.sql.Timestamp.from(cursor.sortTime());
		if (cursor.nullTime()) {
			if (mediaComparison > 0) {
				sql.append(prefix).append("(createtime IS NULL)");
			} else if (mediaComparison == 0) {
				sql.append(prefix).append("(createtime IS NULL AND id ").append(idOperator).append(" ?)");
				parameters.add(cursor.internalId());
			} else {
				sql.append(prefix).append("1 = 0");
			}
		} else if (mediaComparison > 0) {
			sql.append(prefix).append("(createtime ").append(timeOperator)
					.append(" ? OR createtime = ? OR createtime IS NULL)");
			parameters.add(cursorTime);
			parameters.add(cursorTime);
		} else if (mediaComparison < 0) {
			sql.append(prefix).append("(createtime ").append(timeOperator)
					.append(" ? OR createtime IS NULL)");
			parameters.add(cursorTime);
		} else {
			sql.append(prefix).append("(createtime ").append(timeOperator)
					.append(" ? OR createtime IS NULL OR (createtime = ? AND id ").append(idOperator).append(" ?))");
			parameters.add(cursorTime);
			parameters.add(cursorTime);
			parameters.add(cursor.internalId());
		}
	}

	private Comparator<MediaFeedRow> feedComparator(String order, boolean byDownloadTime) {
		boolean ascending = "asc".equals(order);
		Comparator<MediaFeedRow> time = (left, right) -> {
			Instant leftTime = byDownloadTime ? left.downloadedAt() : left.publishTime();
			Instant rightTime = byDownloadTime ? right.downloadedAt() : right.publishTime();
			if (leftTime == null && rightTime == null) return 0;
			if (leftTime == null) return 1;
			if (rightTime == null) return -1;
			int result = leftTime.compareTo(rightTime);
			return ascending ? result : -result;
		};
		Comparator<Integer> id = Comparator.naturalOrder();
		if (!ascending) {
			id = id.reversed();
		}
		return time.thenComparing(MediaFeedRow::mediaType).thenComparing(MediaFeedRow::internalId, id);
	}

	private MediaFeedRow mapRow(ResultSet row, int index, boolean byDownloadTime) throws SQLException {
		String mediaType = row.getString("media_type");
		int internalId = row.getInt("internal_id");
		List<MediaSlideRow> slides = parseSlides(row.getString("slides_json"));
		String cover = row.getString("cover_url");
		if ((cover == null || cover.isBlank()) && !slides.isEmpty()) cover = slides.get(0).url();
		Instant publishTime = parseInstant(row.getObject("publish_time"));
		Instant downloadedAt = parseInstant(row.getObject("downloaded_at"));
		Instant sortInstant = byDownloadTime ? downloadedAt : publishTime;
		long sortTime = sortInstant == null ? 0 : sortInstant.toEpochMilli();
		return new MediaFeedRow(mediaType + ":" + internalId, mediaType, internalId,
				row.getString("platform_key"), row.getString("platform_display_name"), row.getString("work_id"),
				row.getString("author_uid"), row.getString("author_username"),
				row.getString("author_display_name"), row.getString("author_avatar"), row.getString("title"),
				row.getString("summary"), publishTime, downloadedAt, cover,
				row.getString("fallback_url"), row.getString("source_url"), row.getString("original_address"),
				row.getString("favorite"), row.getString("privacy"), row.getString("content_type"),
				row.getString("author_homepage"), slides, row.getString("local_path"),
				row.getString("graphic_media_root"), sortTime);
	}

	private List<MediaSlideRow> parseSlides(String raw) {
		if (!hasText(raw)) return List.of();
		try {
			List<String> urls = JSON.parseArray(raw, String.class);
			if (urls == null) return List.of();
			List<MediaSlideRow> slides = new ArrayList<>();
			for (String url : urls) {
				String type = mediaType(url);
				if (type != null) slides.add(new MediaSlideRow(type, url));
			}
			return List.copyOf(slides);
		} catch (RuntimeException error) {
			return List.of();
		}
	}

	private String mediaType(String url) {
		if (!hasText(url)) return null;
		String value = url.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
		if (VIDEO_EXTENSIONS.stream().anyMatch(value::endsWith)) return "video";
		if (IMAGE_EXTENSIONS.stream().anyMatch(value::endsWith)) return "image";
		return null;
	}

	private Instant parseInstant(Object value) {
		if (value == null) return null;
		if (value instanceof Number number) {
			long raw = number.longValue();
			return Instant.ofEpochMilli(raw < 10_000_000_000L ? raw * 1000 : raw);
		}
		String text = String.valueOf(value).trim();
		if (text.isEmpty()) return null;
		try {
			long raw = Long.parseLong(text);
			return Instant.ofEpochMilli(raw < 10_000_000_000L ? raw * 1000 : raw);
		} catch (NumberFormatException ignored) {
		}
		try {
			return Instant.parse(text);
		} catch (RuntimeException ignored) {
		}
		try {
			return LocalDateTime.parse(text.replace('T', ' ').substring(0, 19), LOCAL_TIME)
					.atZone(ZoneId.systemDefault()).toInstant();
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
}
