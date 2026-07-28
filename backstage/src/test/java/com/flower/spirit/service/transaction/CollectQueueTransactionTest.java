package com.flower.spirit.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.sqlite.SQLiteDataSource;

import com.flower.spirit.service.CollectEnqueueResult;
import com.flower.spirit.service.CollectJobClaim;
import com.flower.spirit.service.CollectRunFetchedItem;
import com.flower.spirit.service.CollectRunState;
import com.flower.spirit.service.CollectTriggerType;
import com.flower.spirit.service.RuntimeJobQueryService;

class CollectQueueTransactionTest {

	@Test
	void fetchPlanCompletionLeavesDownloadsQueuedAndPreservesCarriedOut() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-07-25T06:00:00Z");
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus, count, carriedout) VALUES(7, '处理完成', '9', '8')");

			CollectEnqueueResult first = transaction.enqueue(7, CollectTriggerType.SCHEDULED, 20, now, 100, 3);
			CollectEnqueueResult duplicate = transaction.enqueue(7, CollectTriggerType.MANUAL, 20,
					now.plusSeconds(1), 0, 3);

			assertThat(first.inserted()).isTrue();
			assertThat(duplicate.inserted()).isFalse();
			assertThat(duplicate.runId()).isEqualTo(first.runId());
			assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_collect_run", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_job_queue", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT count FROM biz_collect_data WHERE id = 7", String.class))
					.isEqualTo("9");

			CollectJobClaim claim = transaction.claimNext("test-worker", now.plusSeconds(2));
			Map<String, Object> dashboard = new RuntimeJobQueryService(jdbc).dashboard(20);
			assertThat((List<?>) dashboard.get("running")).hasSize(1);
			transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING,
					now.plusSeconds(3));
			transaction.storeFetchPlan(claim.runId(), 7, List.of(
					new CollectRunFetchedItem(1, "douyin", "work-1", "MS4-author", "作者", "作品一",
							"200", "video", "NEW", "QUEUED"),
					new CollectRunFetchedItem(2, "douyin", "work-2", "MS4-author", "作者", "作品二",
							"100", "image", "EXISTING", "SKIPPED_EXISTING")), 40, "NO_MORE",
					new CollectRunFetchedItem.FetchWatermark("200", "work-1", 2, 0, "cursor-2"),
					now.plusSeconds(4));
			transaction.complete(claim.runId(), claim.jobId(), now.plusSeconds(7));

			assertThat(jdbc.queryForObject("SELECT state FROM biz_collect_run WHERE id = ?", String.class,
					claim.runId())).isEqualTo("COMPLETED");
			assertThat(jdbc.queryForObject("SELECT planned_count FROM biz_collect_run WHERE id = ?", Integer.class,
					claim.runId())).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT inserted_count FROM biz_collect_run WHERE id = ?", Integer.class,
					claim.runId())).isZero();
			assertThat(jdbc.queryForObject("SELECT skipped_existing_count FROM biz_collect_run WHERE id = ?",
					Integer.class, claim.runId())).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT fetched_count FROM biz_collect_run WHERE id = ?", Integer.class,
					claim.runId())).isEqualTo(40);
			assertThat(jdbc.queryForObject("SELECT count FROM biz_collect_data WHERE id = 7", String.class))
					.isEqualTo("40");
			assertThat(jdbc.queryForObject("SELECT carriedout FROM biz_collect_data WHERE id = 7", String.class))
					.isEqualTo("8");
			assertThat(jdbc.queryForObject("SELECT process_state FROM biz_collect_run_item WHERE work_id = 'work-1'",
					String.class)).isEqualTo("QUEUED");
			assertThat(jdbc.queryForObject("SELECT queue_generation FROM biz_collect_run_item WHERE work_id = 'work-1'",
					String.class)).isEqualTo("FETCH_DOWNLOAD_V1");
			assertThat(jdbc.queryForObject("SELECT queue_generation FROM biz_collect_run_item WHERE work_id = 'work-2'",
					String.class)).isNull();
			assertThat(jdbc.queryForObject("SELECT last_seen_work_id FROM biz_collect_data WHERE id = 7", String.class))
					.isEqualTo("work-1");
			assertThat(jdbc.queryForObject("SELECT fetch_stop_reason FROM biz_collect_run WHERE id = ?", String.class,
					claim.runId())).isEqualTo("NO_MORE");
			assertThat(jdbc.queryForObject("SELECT state FROM biz_job_queue WHERE id = ?", String.class,
					claim.jobId())).isEqualTo("COMPLETED");
		}
	}

	@Test
	void activeDownloadCandidateIsStoredAsSkippedWithoutGeneration() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-07-25T06:00:00Z");
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus, count, carriedout) VALUES(7, 'done', '9', '8')");
			CollectEnqueueResult queued = transaction.enqueue(7, CollectTriggerType.SCHEDULED, 20, now, 100, 3);
			CollectJobClaim claim = transaction.claimNext("worker", now.plusSeconds(1));
			transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING, now.plusSeconds(2));
			jdbc.update("INSERT INTO biz_collect_run(id, collect_task_id, trigger_type, state, created_at) "
					+ "VALUES(99, 7, 'SCHEDULED', 'COMPLETED', ?)", java.sql.Timestamp.from(now));
			jdbc.update("INSERT INTO biz_collect_run_item(run_id, ordinal, platform_key, work_id, decision, process_state, "
					+ "queue_generation, attempt_count, max_attempts, created_at, updated_at) "
					+ "VALUES(99, 1, 'douyin', 'same-work', 'NEW', 'RUNNING', 'FETCH_DOWNLOAD_V1', 0, 4, ?, ?)",
					java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

			transaction.storeFetchPlan(queued.runId(), 7, List.of(new CollectRunFetchedItem(1, "douyin", "same-work",
					"author", "name", "title", "100", "video", "NEW", "QUEUED")), "NO_MORE",
					new CollectRunFetchedItem.FetchWatermark("100", "same-work", 1, 0, "0"), now.plusSeconds(3));

			assertThat(jdbc.queryForObject("SELECT process_state FROM biz_collect_run_item WHERE run_id = ?",
					String.class, queued.runId())).isEqualTo("SKIPPED_EXISTING_ACTIVE_DOWNLOAD");
			assertThat(jdbc.queryForObject("SELECT queue_generation FROM biz_collect_run_item WHERE run_id = ?",
					String.class, queued.runId())).isNull();
		}
	}

	@Test
	void duplicateObservationsAreBothStoredButOnlyTheFirstIsDownloadable() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-07-25T06:00:00Z");
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus, count, carriedout) VALUES(7, 'done', '0', '0')");
			CollectEnqueueResult queued = transaction.enqueue(7, CollectTriggerType.SCHEDULED, 20, now, 100, 3);
			CollectJobClaim claim = transaction.claimNext("worker", now.plusSeconds(1));
			transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING, now.plusSeconds(2));

			transaction.storeFetchPlan(queued.runId(), 7, List.of(
					new CollectRunFetchedItem(1, "douyin", "same-work", "author", "name", "first", "200",
							"video", "NEW", "QUEUED"),
					new CollectRunFetchedItem(2, "douyin", "same-work", "author", "name", "duplicate", "200",
							"video", "DUPLICATE_OBSERVATION", "SKIPPED_EXISTING")), "NO_MORE",
					new CollectRunFetchedItem.FetchWatermark("200", "same-work", 1, 0, "0"), now.plusSeconds(3));

			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_collect_run_item WHERE run_id = ?", Integer.class,
					queued.runId())).isEqualTo(2);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_collect_run_item WHERE run_id = ? "
					+ "AND queue_generation = 'FETCH_DOWNLOAD_V1'", Integer.class, queued.runId())).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT decision FROM biz_collect_run_item WHERE run_id = ? AND ordinal = 2",
					String.class, queued.runId())).isEqualTo("DUPLICATE_OBSERVATION");
		}
	}

	@Test
	void failedPlanInsertRollsBackItemsStateAndWatermark() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-07-25T06:00:00Z");
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus, count, carriedout) VALUES(7, 'done', '9', '8')");
			CollectEnqueueResult queued = transaction.enqueue(7, CollectTriggerType.SCHEDULED, 20, now, 100, 3);
			CollectJobClaim claim = transaction.claimNext("worker", now.plusSeconds(1));
			transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING, now.plusSeconds(2));
			jdbc.execute("CREATE TRIGGER fail_invalid_plan BEFORE INSERT ON biz_collect_run_item "
					+ "WHEN NEW.work_id = 'invalid' BEGIN SELECT RAISE(ABORT, 'forced plan failure'); END");

			List<CollectRunFetchedItem> invalidPlan = List.of(
					new CollectRunFetchedItem(1, "douyin", "valid", null, null, null, "100", "video", "NEW", "QUEUED"),
					new CollectRunFetchedItem(2, "douyin", "invalid", null, null, null, "90", "video", "NEW", "QUEUED"));
			assertThatThrownBy(() -> transaction.storeFetchPlan(queued.runId(), 7, invalidPlan, "NO_MORE",
					new CollectRunFetchedItem.FetchWatermark("100", "valid", 1, 0, "0"), now.plusSeconds(3)))
					.isInstanceOf(RuntimeException.class);

			assertThat(jdbc.queryForObject("SELECT state FROM biz_collect_run WHERE id = ?", String.class,
					queued.runId())).isEqualTo("FETCHING");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_collect_run_item WHERE run_id = ?", Integer.class,
					queued.runId())).isZero();
			assertThat(jdbc.queryForObject("SELECT last_seen_work_id FROM biz_collect_data WHERE id = 7", String.class))
					.isNull();
		}
	}

	@Test
	void olderObservedWorkDoesNotRegressWatermarkAndWarningIsRecorded() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-07-25T06:00:00Z");
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus, count, carriedout, last_seen_publish_time, "
					+ "last_seen_work_id) VALUES(7, 'done', '9', '8', '300', 'newer-work')");
			CollectEnqueueResult queued = transaction.enqueue(7, CollectTriggerType.AUDIT, 20, now, 10, 3);
			CollectJobClaim claim = transaction.claimNext("worker", now.plusSeconds(1));
			transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING, now.plusSeconds(2));

			transaction.storeFetchPlan(queued.runId(), 7, List.of(), "MAX_PAGE_GUARD",
					new CollectRunFetchedItem.FetchWatermark("200", "older-work", 500, 0, "cursor-500"),
					now.plusSeconds(3));

			assertThat(jdbc.queryForObject("SELECT last_seen_publish_time FROM biz_collect_data WHERE id = 7",
					String.class)).isEqualTo("300");
			assertThat(jdbc.queryForObject("SELECT last_seen_work_id FROM biz_collect_data WHERE id = 7",
					String.class)).isEqualTo("newer-work");
			assertThat(jdbc.queryForObject("SELECT fetch_warning FROM biz_collect_run WHERE id = ?", String.class,
					queued.runId())).isEqualTo("MAX_PAGE_GUARD");
			assertThat(jdbc.queryForObject("SELECT message FROM biz_collect_run_event WHERE run_id = ? "
					+ "AND event_code = 'FETCH_STOP'", String.class, queued.runId()))
					.contains("pages=500", "cursor=cursor-500");
		}
	}

	@Test
	void failedRunPreservesSuccessfulSummaryAndRetryUsesANewRun() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-07-25T07:00:00Z");
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus, count, carriedout) VALUES(8, '处理完成', '40', '20')");

			CollectEnqueueResult queued = transaction.enqueue(8, CollectTriggerType.MANUAL, 20, now, 0, 3);
			CollectJobClaim claim = transaction.claimNext("test-worker", now.plusSeconds(1));
			transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING,
					now.plusSeconds(2));
			transaction.failRun(claim.runId(), CollectRunState.FETCHING, CollectRunState.FETCH_FAILED,
					"COOKIE_EXPIRED", "Cookie 已过期", "probe=guest", now.plusSeconds(3));
			CollectEnqueueResult retry = transaction.retryOrFailJob(claim, "COOKIE_EXPIRED", "Cookie 已过期",
					now.plus(1, ChronoUnit.HOURS), now.plusSeconds(4));

			assertThat(retry.runId()).isNotEqualTo(queued.runId());
			assertThat(jdbc.queryForObject("SELECT state FROM biz_collect_run WHERE id = ?", String.class,
					queued.runId())).isEqualTo("FETCH_FAILED");
			assertThat(jdbc.queryForObject("SELECT state FROM biz_collect_run WHERE id = ?", String.class,
					retry.runId())).isEqualTo("QUEUED");
			assertThat(jdbc.queryForObject("SELECT count FROM biz_collect_data WHERE id = 8", String.class))
					.isEqualTo("40");
			assertThat(jdbc.queryForObject("SELECT carriedout FROM biz_collect_data WHERE id = 8", String.class))
					.isEqualTo("20");
			assertThat(transaction.claimNext("test-worker", now.plus(30, ChronoUnit.MINUTES))).isNull();
			assertThat(transaction.claimNext("test-worker", now.plus(2, ChronoUnit.HOURS)).runId())
					.isEqualTo(retry.runId());
		}
	}

	@Test
	void auditRetryKeepsAuditTriggerType() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-07-25T08:00:00Z");
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus) VALUES(12, 'queued')");
			CollectEnqueueResult queued = transaction.enqueue(12, CollectTriggerType.AUDIT, 20, now, 10, 3);
			CollectJobClaim claim = transaction.claimNext("audit-worker", now.plusSeconds(1));
			transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING,
					now.plusSeconds(2));
			transaction.failRun(claim.runId(), CollectRunState.FETCHING, CollectRunState.FETCH_FAILED,
					"UPSTREAM_ERROR", "temporary", "diagnostic", now.plusSeconds(3));

			CollectEnqueueResult retry = transaction.retryOrFailJob(claim, "UPSTREAM_ERROR", "temporary",
					now.plus(1, ChronoUnit.MINUTES), now.plusSeconds(4));

			assertThat(retry.runId()).isNotEqualTo(queued.runId());
			assertThat(jdbc.queryForObject("SELECT trigger_type FROM biz_collect_run WHERE id = ?", String.class,
					retry.runId())).isEqualTo("AUDIT");
			String payload = jdbc.queryForObject("SELECT payload FROM biz_job_queue WHERE id = ?", String.class,
					queued.jobId());
			assertThat(payload).contains("\"triggerType\":\"AUDIT\"");
		}
	}

	@Test
	void recoveryInterruptsAClaimedQueuedRunAndRequeuesIt() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-07-25T08:00:00Z");
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus, count, carriedout) VALUES(9, '处理完成', '3', '3')");
			CollectEnqueueResult queued = transaction.enqueue(9, CollectTriggerType.SCHEDULED, 20, now, 100, 3);
			CollectJobClaim claim = transaction.claimNext("crashed-worker", now.plusSeconds(1));

			assertThat(transaction.recoverStale(now.plusSeconds(2), now.plusSeconds(3))).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT state FROM biz_collect_run WHERE id = ?", String.class,
					queued.runId())).isEqualTo("INTERRUPTED");
			assertThat(jdbc.queryForObject("SELECT state FROM biz_job_queue WHERE id = ?", String.class,
					claim.jobId())).isEqualTo("QUEUED");
			assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_collect_run", Integer.class)).isEqualTo(2);
			assertThat(jdbc.queryForObject("SELECT state FROM biz_collect_run WHERE id <> ?", String.class,
					queued.runId())).isEqualTo("QUEUED");
		}
	}

	private AnnotationConfigApplicationContext context() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-collect-queue.db")
				+ "?journal_mode=WAL&busy_timeout=1000");
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.register(TransactionConfiguration.class);
		context.registerBean(DataSource.class, () -> dataSource);
		context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
		context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
		context.registerBean(CollectQueueTransaction.class);
		context.refresh();
		return context;
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
				+ "process_state TEXT NOT NULL, error_code TEXT, error_message TEXT, attempt_count INTEGER NOT NULL DEFAULT 0, "
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
	}

	@Configuration
	@EnableTransactionManagement
	static class TransactionConfiguration {
	}
}
