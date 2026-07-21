package com.flower.spirit.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseIndexInitializer {

	private static final Logger logger = LoggerFactory.getLogger(DatabaseIndexInitializer.class);

	private final JdbcTemplate jdbcTemplate;
	private final List<String> indexSqlStatements;

	@Autowired
	public DatabaseIndexInitializer(JdbcTemplate jdbcTemplate) {
		this(jdbcTemplate, defaultIndexSqlStatements());
	}

	DatabaseIndexInitializer(JdbcTemplate jdbcTemplate, List<String> indexSqlStatements) {
		this.jdbcTemplate = jdbcTemplate;
		this.indexSqlStatements = indexSqlStatements;
	}

	@Order(200)
	@EventListener(ApplicationReadyEvent.class)
	public void initialize() {
		for (String sql : indexSqlStatements) {
			try {
				jdbcTemplate.execute(sql);
			} catch (Exception e) {
				logger.warn("Database index initialization failed for SQL: {}", sql, e);
			}
		}
	}

	static List<String> defaultIndexSqlStatements() {
		return List.of(
				"CREATE INDEX IF NOT EXISTS idx_biz_video_publishtime_id ON biz_video(publishtime, id)",
				"CREATE INDEX IF NOT EXISTS idx_biz_video_createtime_id ON biz_video(createtime, id)",
				"CREATE INDEX IF NOT EXISTS idx_biz_video_videoauthor ON biz_video(videoauthor)",
				"CREATE INDEX IF NOT EXISTS idx_biz_video_videoplatform ON biz_video(videoplatform)",
				"CREATE INDEX IF NOT EXISTS idx_biz_video_videoid ON biz_video(videoid)",
				"CREATE INDEX IF NOT EXISTS idx_biz_video_platform_videoid ON biz_video(videoplatform, videoid)",
				"CREATE INDEX IF NOT EXISTS idx_biz_video_platformkey_videoid ON biz_video(platformkey, videoid)",
				"CREATE INDEX IF NOT EXISTS idx_biz_video_favorite_id ON biz_video(favorite, id)",
				"CREATE INDEX IF NOT EXISTS idx_collect_detail_dataid_videoid ON biz_collect_data_detail(dataid, videoid)",
				"CREATE INDEX IF NOT EXISTS idx_collect_detail_dataid_status ON biz_collect_data_detail(dataid, status)",
				"CREATE INDEX IF NOT EXISTS idx_collect_detail_dataid_mediatype_status ON biz_collect_data_detail(dataid, mediatype, status)",
				"CREATE INDEX IF NOT EXISTS idx_author_profile_platform_authoruid ON biz_author_profile(platform, authoruid)",
				"CREATE INDEX IF NOT EXISTS idx_author_profile_platformkey_authoruid ON biz_author_profile(platformkey, authoruid)",
				"CREATE INDEX IF NOT EXISTS idx_graphic_content_platform_videoid ON biz_graphic_content(platform, videoid)",
				"CREATE INDEX IF NOT EXISTS idx_graphic_content_platformkey_videoid ON biz_graphic_content(platformkey, videoid)",
				"CREATE INDEX IF NOT EXISTS idx_graphic_content_publishtime_id ON biz_graphic_content(publishtime, id)",
				"CREATE INDEX IF NOT EXISTS idx_graphic_content_createtime_id ON biz_graphic_content(createtime, id)",
				"CREATE INDEX IF NOT EXISTS idx_graphic_content_author ON biz_graphic_content(author)");
	}
}
