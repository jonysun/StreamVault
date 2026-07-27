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

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.service.transaction.DatabaseInitializationTransaction;

@Service
public class DatabaseIndexInitializer {

	private static final Logger logger = LoggerFactory.getLogger(DatabaseIndexInitializer.class);

	private final JdbcTemplate jdbcTemplate;
	private final List<String> indexSqlStatements;
	private final DatabaseInitializationTransaction transaction;
	private final DatabaseWriteExecutor databaseWriteExecutor;

	@Autowired
	public DatabaseIndexInitializer(JdbcTemplate jdbcTemplate, DatabaseInitializationTransaction transaction,
			DatabaseWriteExecutor databaseWriteExecutor) {
		this(jdbcTemplate, defaultIndexSqlStatements(), transaction, databaseWriteExecutor);
	}

	DatabaseIndexInitializer(JdbcTemplate jdbcTemplate, List<String> indexSqlStatements,
			DatabaseInitializationTransaction transaction, DatabaseWriteExecutor databaseWriteExecutor) {
		this.jdbcTemplate = jdbcTemplate;
		this.indexSqlStatements = indexSqlStatements;
		this.transaction = transaction;
		this.databaseWriteExecutor = databaseWriteExecutor;
	}

	@Order(200)
	@EventListener(ApplicationReadyEvent.class)
	public void initialize() {
		for (String sql : indexSqlStatements) {
			try {
				databaseWriteExecutor.execute("schema-create-index", () -> {
					transaction.execute(sql);
					return null;
				});
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
				"CREATE INDEX IF NOT EXISTS idx_biz_video_author_identity ON biz_video(platformkey, authoruid, secuid)",
				"CREATE INDEX IF NOT EXISTS idx_biz_video_author_feed "
						+ "ON biz_video(platformkey, COALESCE(NULLIF(secuid,''), authoruid), publishtime, id)",
				"CREATE INDEX IF NOT EXISTS idx_biz_video_favorite_id ON biz_video(favorite, id)",
				"CREATE INDEX IF NOT EXISTS idx_collect_detail_dataid_videoid ON biz_collect_data_detail(dataid, videoid)",
				"CREATE INDEX IF NOT EXISTS idx_collect_detail_dataid_status ON biz_collect_data_detail(dataid, status)",
				"CREATE INDEX IF NOT EXISTS idx_collect_detail_dataid_mediatype_status ON biz_collect_data_detail(dataid, mediatype, status)",
				"CREATE INDEX IF NOT EXISTS idx_author_profile_platform_authoruid ON biz_author_profile(platform, authoruid)",
				"CREATE INDEX IF NOT EXISTS idx_author_profile_platformkey_authoruid ON biz_author_profile(platformkey, authoruid)",
				"CREATE UNIQUE INDEX IF NOT EXISTS uq_author_enrichment_active "
						+ "ON biz_author_enrichment_job(platform_key, author_uid) "
						+ "WHERE state IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')",
				"CREATE INDEX IF NOT EXISTS idx_author_enrichment_due "
						+ "ON biz_author_enrichment_job(state, next_attempt_at, priority, id)",
				"CREATE UNIQUE INDEX IF NOT EXISTS uq_collect_run_active_task "
						+ "ON biz_collect_run(collect_task_id) "
						+ "WHERE state IN ('QUEUED', 'FETCHING', 'PROCESSING')",
				"CREATE INDEX IF NOT EXISTS idx_collect_run_task_created "
						+ "ON biz_collect_run(collect_task_id, created_at DESC, id DESC)",
				"CREATE INDEX IF NOT EXISTS idx_collect_run_state_heartbeat "
						+ "ON biz_collect_run(state, heartbeat_at)",
				"CREATE INDEX IF NOT EXISTS idx_collect_run_item_run_ordinal "
						+ "ON biz_collect_run_item(run_id, ordinal)",
				"CREATE INDEX IF NOT EXISTS idx_collect_run_item_download_claim "
						+ "ON biz_collect_run_item(queue_generation, process_state, available_at, ordinal, created_at, id)",
				"CREATE INDEX IF NOT EXISTS idx_collect_run_item_active_work "
						+ "ON biz_collect_run_item(platform_key, work_id, process_state)",
				"CREATE INDEX IF NOT EXISTS idx_collect_run_item_run_state "
						+ "ON biz_collect_run_item(run_id, process_state)",
				"CREATE UNIQUE INDEX IF NOT EXISTS uq_collect_run_event_sequence "
						+ "ON biz_collect_run_event(run_id, sequence)",
				"CREATE INDEX IF NOT EXISTS idx_collect_run_event_run_sequence "
						+ "ON biz_collect_run_event(run_id, sequence)",
				"CREATE UNIQUE INDEX IF NOT EXISTS uq_job_queue_active_dedupe "
						+ "ON biz_job_queue(job_type, dedupe_key) "
						+ "WHERE state IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')",
				"CREATE INDEX IF NOT EXISTS idx_job_queue_claim "
						+ "ON biz_job_queue(state, available_at, priority, id)",
				"CREATE INDEX IF NOT EXISTS idx_graphic_content_platform_videoid ON biz_graphic_content(platform, videoid)",
				"CREATE INDEX IF NOT EXISTS idx_graphic_content_platformkey_videoid ON biz_graphic_content(platformkey, videoid)",
				"CREATE INDEX IF NOT EXISTS idx_graphic_content_author_identity ON biz_graphic_content(platformkey, authoruid, secuid)",
				"CREATE INDEX IF NOT EXISTS idx_graphic_content_author_feed "
						+ "ON biz_graphic_content(platformkey, COALESCE(NULLIF(secuid,''), authoruid), publishtime, id)",
				"CREATE INDEX IF NOT EXISTS idx_graphic_content_publishtime_id ON biz_graphic_content(publishtime, id)",
				"CREATE INDEX IF NOT EXISTS idx_graphic_content_createtime_id ON biz_graphic_content(createtime, id)",
				"CREATE INDEX IF NOT EXISTS idx_graphic_content_author ON biz_graphic_content(author)");
	}
}
