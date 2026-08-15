package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.sqlite.SQLiteDataSource;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.service.WorkIngestService.IngestResult;
import com.flower.spirit.service.WorkPersistenceService.PersistenceResult;
import com.flower.spirit.service.transaction.CollectDownloadTransaction;
import com.flower.spirit.service.transaction.CollectQueueTransaction;

class CollectPipelineIntegrationTest {

	private static final Instant BASE = Instant.parse("2026-07-27T09:00:00Z");

	@Test
	void fetchesAuthorsIndependentlyAndRecoversPerWorkDownloadsWithoutReplayingOldRows() throws Exception {
		Path database = databasePath();
		SQLiteDataSource dataSource = dataSource(database);
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		createSchema(jdbc);
		insertTask(jdbc, 10, "author-a");
		insertTask(jdbc, 20, "author-b");
		insertTask(jdbc, 30, "author-recovery");

		Map<String, Integer> ingestAttempts = new HashMap<>();
		WorkIngestService ingestService = fakeIngestService(jdbc, ingestAttempts);
		DatabaseWriteExecutor writes = new PassThroughDatabaseWriteExecutor();
		long authorARun;
		long authorBRun;

		try (AnnotationConfigApplicationContext context = context(dataSource, jdbc)) {
			CollectQueueTransaction fetch = context.getBean(CollectQueueTransaction.class);
			CollectDownloadTransaction downloads = context.getBean(CollectDownloadTransaction.class);
			CollectDownloadService downloadService = new CollectDownloadService(ingestService, downloads, writes);

			authorARun = completeFetch(fetch, 10, BASE, List.of(
					fetched(1, "A1", "author-a"), fetched(2, "A2", "author-a")));
			assertThat(downloadStates(jdbc, authorARun)).containsExactly("QUEUED", "QUEUED");

			// Author B can finish fetching while all author A media remains queued.
			authorBRun = completeFetch(fetch, 20, BASE.plusSeconds(10),
					List.of(fetched(1, "B1", "author-b")));
			assertThat(fetchRunState(jdbc, authorARun)).isEqualTo("COMPLETED");
			assertThat(fetchRunState(jdbc, authorBRun)).isEqualTo("COMPLETED");

			CollectDownloadClaim a1 = downloads.claimNext("download-worker", BASE.plusSeconds(20));
			assertThat(a1.workId()).isEqualTo("A1");
			downloadService.process(a1, BASE.plusSeconds(20));
			assertThat(itemState(jdbc, "A1")).isEqualTo("RETRY_WAIT");
			assertThat(itemErrorCode(jdbc, "A1")).isEqualTo("NETWORK_IO");
			assertThat(itemAvailableAt(jdbc, "A1")).isEqualTo(BASE.plusSeconds(80));

			CollectDownloadClaim b1 = downloads.claimNext("download-worker", BASE.plusSeconds(21));
			assertThat(b1.workId()).isEqualTo("B1");
			downloadService.process(b1, BASE.plusSeconds(21));
			CollectDownloadClaim a2 = downloads.claimNext("download-worker", BASE.plusSeconds(22));
			assertThat(a2.workId()).isEqualTo("A2");
			downloadService.process(a2, BASE.plusSeconds(22));
			assertThat(downloads.claimNext("download-worker", BASE.plusSeconds(79))).isNull();

			CollectDownloadClaim a1Retry = downloads.claimNext("download-worker", BASE.plusSeconds(80));
			assertThat(a1Retry.workId()).isEqualTo("A1");
			assertThat(a1Retry.attemptCount()).isEqualTo(2);
			downloadService.process(a1Retry, BASE.plusSeconds(80));

			long recoveryRun = completeFetch(fetch, 30, BASE.plusSeconds(90),
					List.of(fetched(1, "RECOVER-1", "author-recovery")));
			CollectDownloadClaim abandoned = downloads.claimNext("crashed-worker", BASE.plusSeconds(100));
			assertThat(abandoned.workId()).isEqualTo("RECOVER-1");
			insertOldPendingItem(jdbc, recoveryRun);
		}

		// Recreate the transaction objects to model a process restart before stale-lock recovery.
		try (AnnotationConfigApplicationContext restarted = context(dataSource, jdbc)) {
			CollectDownloadTransaction downloads = restarted.getBean(CollectDownloadTransaction.class);
			CollectDownloadService downloadService = new CollectDownloadService(ingestService, downloads, writes);
			assertThat(downloads.recoverStale(BASE.plusSeconds(150), BASE.plusSeconds(200))).isEqualTo(1);
			CollectDownloadClaim recovered = downloads.claimNext("restarted-worker", BASE.plusSeconds(201));
			assertThat(recovered.workId()).isEqualTo("RECOVER-1");
			downloadService.process(recovered, BASE.plusSeconds(201));
			assertThat(downloads.claimNext("restarted-worker", BASE.plusSeconds(202))).isNull();
		}

		assertThat(fetchRunState(jdbc, authorARun)).isEqualTo("COMPLETED");
		assertThat(fetchRunState(jdbc, authorBRun)).isEqualTo("COMPLETED");
		assertThat(downloadStates(jdbc, authorARun)).containsExactlyInAnyOrder("COMPLETED", "COMPLETED");
		assertThat(downloadStates(jdbc, authorBRun)).containsExactly("COMPLETED");
		assertThat(mediaRowCount(jdbc, "A1")).isEqualTo(1);
		assertThat(detailRowCount(jdbc, 10, "A1")).isEqualTo(1);
		assertThat(detailRowCount(jdbc, 20, "B1")).isEqualTo(1);
		assertThat(mediaRowCount(jdbc, "RECOVER-1")).isEqualTo(1);
		assertThat(detailRowCount(jdbc, 30, "RECOVER-1")).isEqualTo(1);
		assertThat(ingestAttempts).containsEntry("A1", 2).containsEntry("RECOVER-1", 1);
		assertThat(oldPendingItemState(jdbc)).isEqualTo("PENDING");
	}

	private long completeFetch(CollectQueueTransaction transaction, int taskId, Instant now,
			List<CollectRunFetchedItem> items) {
		CollectEnqueueResult queued = transaction.enqueue(taskId, CollectTriggerType.MANUAL, 20, now, 0, 3);
		CollectJobClaim claim = transaction.claimNext("fetch-worker", now.plusSeconds(1));
		assertThat(claim).isNotNull();
		assertThat(claim.taskId()).isEqualTo(taskId);
		transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING,
				now.plusSeconds(2));
		CollectRunFetchedItem newest = items.get(0);
		transaction.storeFetchPlan(claim.runId(), taskId, items, "NO_MORE",
				new CollectRunFetchedItem.FetchWatermark(newest.publishTime(), newest.workId(), 1, 0, "0"),
				now.plusSeconds(3));
		transaction.complete(claim.runId(), claim.jobId(), now.plusSeconds(4));
		assertThat(queued.runId()).isEqualTo(claim.runId());
		return claim.runId();
	}

	private CollectRunFetchedItem fetched(int ordinal, String workId, String authorUid) {
		return new CollectRunFetchedItem(ordinal, "douyin", workId, authorUid, authorUid,
				"work " + workId, String.valueOf(1_000 - ordinal), "video", "NEW", "QUEUED");
	}

	@SuppressWarnings("unchecked")
	private WorkIngestService fakeIngestService(JdbcTemplate jdbc, Map<String, Integer> attempts) {
		WorkIngestService service = mock(WorkIngestService.class);
		when(service.ingest(anyString(), any(Function.class), eq(false), isNull())).thenAnswer(invocation -> {
			String source = invocation.getArgument(0);
			String workId = source.substring(source.lastIndexOf('/') + 1);
			int attempt = attempts.merge(workId, 1, Integer::sum);
			if ("A1".equals(workId) && attempt == 1) {
				throw new WorkMetadataValidationException("Douyin download failed",
						new IOException("unexpected end of stream"));
			}
			boolean created = jdbc.update("INSERT OR IGNORE INTO biz_video(videoid) VALUES(?)", workId) == 1;
			Integer videoId = jdbc.queryForObject("SELECT id FROM biz_video WHERE videoid=?", Integer.class, workId);
			VideoDataEntity video = new VideoDataEntity();
			video.setId(videoId);
			video.setVideoid(workId);
			WorkMetadata metadata = WorkMetadata.builder()
					.platform(PlatformCatalog.requireByKey("douyin"))
					.workId(workId)
					.contentType(WorkContentType.VIDEO)
					.title("work " + workId)
					.sourceUrl(source)
					.originalAddress(source)
					.mediaResources(List.of())
					.build();
			return new IngestResult(DownloadResult.Status.COMPLETED, null, metadata,
					PersistenceResult.video(created, video), created ? null : "work already exists", null);
		});
		return service;
	}

	private AnnotationConfigApplicationContext context(DataSource dataSource, JdbcTemplate jdbc) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.register(TransactionConfiguration.class);
		context.registerBean(DataSource.class, () -> dataSource);
		context.registerBean(PlatformTransactionManager.class,
				() -> new DataSourceTransactionManager(dataSource));
		context.registerBean(JdbcTemplate.class, () -> jdbc);
		context.registerBean(CollectQueueTransaction.class);
		context.registerBean(CollectDownloadTransaction.class);
		context.refresh();
		return context;
	}

	private SQLiteDataSource dataSource(Path database) {
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + database + "?journal_mode=WAL&busy_timeout=3000");
		return dataSource;
	}

	private Path databasePath() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		return directory.resolve(UUID.randomUUID() + "-collect-pipeline.db");
	}

	private void createSchema(JdbcTemplate jdbc) {
		jdbc.execute("CREATE TABLE biz_collect_data (id INTEGER PRIMARY KEY, taskname TEXT, taskstatus TEXT, count TEXT, "
				+ "carriedout TEXT, endtime TEXT, last_successful_fetch_at TIMESTAMP, last_seen_publish_time TEXT, "
				+ "last_seen_work_id TEXT)");
		jdbc.execute("CREATE TABLE biz_collect_run (id INTEGER PRIMARY KEY AUTOINCREMENT, collect_task_id INTEGER NOT NULL, "
				+ "trigger_type TEXT NOT NULL, state TEXT NOT NULL, requested_limit INTEGER, fetched_count INTEGER, "
				+ "planned_count INTEGER, inserted_count INTEGER, skipped_existing_count INTEGER, failed_item_count INTEGER, "
				+ "started_at DATETIME, heartbeat_at DATETIME, finished_at DATETIME, error_code TEXT, error_message TEXT, "
				+ "error_detail TEXT, fetch_stop_reason TEXT, fetch_warning TEXT, created_at DATETIME NOT NULL)");
		jdbc.execute("CREATE UNIQUE INDEX uq_collect_run_active_task ON biz_collect_run(collect_task_id) "
				+ "WHERE state IN ('QUEUED','FETCHING','PROCESSING')");
		jdbc.execute("CREATE TABLE biz_collect_run_item (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id INTEGER NOT NULL, "
				+ "ordinal INTEGER NOT NULL, platform_key TEXT NOT NULL, work_id TEXT NOT NULL, author_uid TEXT, "
				+ "nickname_snapshot TEXT, title_snapshot TEXT, publish_time TEXT, media_type TEXT, decision TEXT NOT NULL, "
				+ "process_state TEXT NOT NULL, metadata_snapshot TEXT, error_code TEXT, error_message TEXT, "
				+ "attempt_count INTEGER NOT NULL DEFAULT 0, "
				+ "max_attempts INTEGER NOT NULL DEFAULT 4, available_at DATETIME, locked_by TEXT, locked_at DATETIME, "
				+ "started_at DATETIME, finished_at DATETIME, error_detail TEXT, queue_generation TEXT, "
				+ "created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL)");
		jdbc.execute("CREATE INDEX idx_collect_run_item_work ON biz_collect_run_item(run_id, platform_key, work_id)");
		jdbc.execute("CREATE TABLE biz_collect_run_event (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id INTEGER NOT NULL, "
				+ "sequence INTEGER NOT NULL, level TEXT NOT NULL, stage TEXT NOT NULL, event_code TEXT NOT NULL, "
				+ "message TEXT NOT NULL, work_id TEXT, created_at DATETIME NOT NULL)");
		jdbc.execute("CREATE UNIQUE INDEX uq_collect_run_event_sequence ON biz_collect_run_event(run_id, sequence)");
		jdbc.execute("CREATE TABLE biz_job_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, job_type TEXT NOT NULL, "
				+ "dedupe_key TEXT NOT NULL, payload TEXT NOT NULL, state TEXT NOT NULL, priority INTEGER NOT NULL, "
				+ "available_at DATETIME NOT NULL, attempt_count INTEGER NOT NULL, max_attempts INTEGER NOT NULL, "
				+ "locked_by TEXT, locked_at DATETIME, last_error_code TEXT, last_error_message TEXT, "
				+ "created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL)");
		jdbc.execute("CREATE UNIQUE INDEX uq_job_queue_active_dedupe ON biz_job_queue(job_type, dedupe_key) "
				+ "WHERE state IN ('QUEUED','RUNNING','RETRY_WAIT')");
		jdbc.execute("CREATE TABLE biz_collect_data_detail (id INTEGER PRIMARY KEY AUTOINCREMENT, dataid INTEGER, "
				+ "videoid TEXT, videoname TEXT, originaladdress TEXT, status TEXT, mediatype TEXT, detailjson TEXT, "
				+ "processlog TEXT, errorcode TEXT, errormsg TEXT, createtime TEXT)");
		jdbc.execute("CREATE TABLE biz_video (id INTEGER PRIMARY KEY AUTOINCREMENT, videoid TEXT NOT NULL UNIQUE)");
		assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualToIgnoringCase("wal");
	}

	private void insertTask(JdbcTemplate jdbc, int id, String taskName) {
		jdbc.update("INSERT INTO biz_collect_data(id, taskname, taskstatus, count, carriedout) "
				+ "VALUES(?, ?, 'ready', '0', '0')", id, taskName);
	}

	private void insertOldPendingItem(JdbcTemplate jdbc, long runId) {
		jdbc.update("INSERT INTO biz_collect_run_item(id, run_id, ordinal, platform_key, work_id, decision, "
				+ "process_state, attempt_count, max_attempts, available_at, queue_generation, created_at, updated_at) "
				+ "VALUES(9999, ?, 99, 'douyin', 'OLD-PENDING', 'NEW', 'PENDING', 0, 4, ?, NULL, ?, ?)",
				runId, Timestamp.from(BASE), Timestamp.from(BASE), Timestamp.from(BASE));
	}

	private String fetchRunState(JdbcTemplate jdbc, long runId) {
		return jdbc.queryForObject("SELECT state FROM biz_collect_run WHERE id=?", String.class, runId);
	}

	private List<String> downloadStates(JdbcTemplate jdbc, long runId) {
		return jdbc.queryForList("SELECT process_state FROM biz_collect_run_item "
				+ "WHERE run_id=? AND queue_generation='FETCH_DOWNLOAD_V1' ORDER BY ordinal", String.class, runId);
	}

	private String itemState(JdbcTemplate jdbc, String workId) {
		return jdbc.queryForObject("SELECT process_state FROM biz_collect_run_item WHERE work_id=?", String.class,
				workId);
	}

	private String itemErrorCode(JdbcTemplate jdbc, String workId) {
		return jdbc.queryForObject("SELECT error_code FROM biz_collect_run_item WHERE work_id=?", String.class,
				workId);
	}

	private Instant itemAvailableAt(JdbcTemplate jdbc, String workId) {
		Object value = jdbc.queryForObject("SELECT available_at FROM biz_collect_run_item WHERE work_id=?",
				Object.class, workId);
		if (value instanceof Timestamp timestamp) return timestamp.toInstant();
		if (value instanceof Number number) return Instant.ofEpochMilli(number.longValue());
		return Instant.parse(String.valueOf(value));
	}

	private int mediaRowCount(JdbcTemplate jdbc, String workId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM biz_video WHERE videoid=?", Integer.class, workId);
	}

	private int detailRowCount(JdbcTemplate jdbc, int taskId, String workId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM biz_collect_data_detail WHERE dataid=? AND videoid=?",
				Integer.class, taskId, workId);
	}

	private String oldPendingItemState(JdbcTemplate jdbc) {
		return jdbc.queryForObject("SELECT process_state FROM biz_collect_run_item WHERE id=9999", String.class);
	}

	private static class PassThroughDatabaseWriteExecutor implements DatabaseWriteExecutor {
		@Override
		public <T> T execute(String operation, Supplier<T> action) {
			return action.get();
		}
	}

	@Configuration
	@EnableTransactionManagement
	static class TransactionConfiguration {
	}
}
