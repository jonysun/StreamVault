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
import com.flower.spirit.service.CollectBackfillProgress;
import com.flower.spirit.service.CollectJobClaim;
import com.flower.spirit.service.CollectRunFetchedItem;
import com.flower.spirit.service.CollectRunState;
import com.flower.spirit.service.CollectTriggerType;
import com.flower.spirit.service.RuntimeJobQueryService;

class CollectQueueTransactionTest {

	@Test
	void cooldownDeferralReturnsClaimAttemptAndKeepsTheSameRunQueued() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-07-29T08:00:00Z");
			Instant availableAt = now.plusSeconds(605);
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus) VALUES(7, 'queued')");
			CollectEnqueueResult queued = transaction.enqueue(7, CollectTriggerType.SCHEDULED, 20, now, 100, 3);
			jdbc.update("UPDATE biz_job_queue SET attempt_count = 2 WHERE id = ?", queued.jobId());
			CollectJobClaim claim = transaction.claimNext("worker", now.plusSeconds(1));
			assertThat(claim.attemptCount()).isEqualTo(3);
			transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING,
					now.plusSeconds(2));

			transaction.deferForCooldown(claim, availableAt, "global cooldown", now.plusSeconds(3));

			assertThat(jdbc.queryForMap("SELECT state, attempt_count, available_at, locked_by, locked_at "
					+ "FROM biz_job_queue WHERE id = ?", claim.jobId()))
					.containsEntry("state", "RETRY_WAIT")
					.containsEntry("attempt_count", 2)
					.containsEntry("locked_by", null)
					.containsEntry("locked_at", null);
			assertThat(jdbc.queryForObject("SELECT state FROM biz_collect_run WHERE id = ?", String.class,
					claim.runId())).isEqualTo("QUEUED");
			assertThat(jdbc.queryForObject("SELECT level FROM biz_collect_run_event WHERE run_id = ? "
					+ "AND event_code = 'F2_COOKIE_COOLDOWN'", String.class, claim.runId())).isEqualTo("WARN");
			assertThat(transaction.claimNext("worker", availableAt.minusSeconds(1))).isNull();
			CollectJobClaim retried = transaction.claimNext("worker", availableAt);
			assertThat(retried.runId()).isEqualTo(claim.runId());
			assertThat(retried.attemptCount()).isEqualTo(3);
		}
	}

	@Test
	void terminalFetchFailureMarksJobFailedWithoutCreatingRetryRun() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-08-07T08:00:00Z");
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus) VALUES(98, 'queued')");
			CollectEnqueueResult queued = transaction.enqueue(98, CollectTriggerType.SCHEDULED, 20, now, 100, 3);
			CollectJobClaim claim = transaction.claimNext("worker", now.plusSeconds(1));
			transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING,
					now.plusSeconds(2));

			transaction.failJob(claim, "INVALID_AUTHOR_ID", "invalid author", now.plusSeconds(3));

			assertThat(jdbc.queryForMap("SELECT state, locked_by, locked_at, last_error_code "
					+ "FROM biz_job_queue WHERE id = ?", queued.jobId()))
					.containsEntry("state", "FAILED")
					.containsEntry("locked_by", null)
					.containsEntry("locked_at", null)
					.containsEntry("last_error_code", "INVALID_AUTHOR_ID");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_collect_run", Integer.class)).isEqualTo(1);
		}
	}

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
							"200", "video", "NEW", "QUEUED", "{\"aweme_detail\":{\"aweme_id\":\"work-1\"}}"),
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
			assertThat(jdbc.queryForObject("SELECT metadata_snapshot FROM biz_collect_run_item WHERE work_id = 'work-1'",
					String.class)).contains("\"aweme_id\":\"work-1\"");
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
	void activeLegacyDownloadIsHydratedAndCurrentObservationIsStoredAsSkipped() throws Exception {
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
					+ "queue_generation, attempt_count, max_attempts, error_code, created_at, updated_at) "
					+ "VALUES(99, 1, 'douyin', 'same-work', 'NEW', 'RETRY_WAIT', 'FETCH_DOWNLOAD_V1', 3, 4, "
					+ "'LIST_SNAPSHOT_PENDING', ?, ?)",
					java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

			transaction.storeFetchPlan(queued.runId(), 7, List.of(new CollectRunFetchedItem(1, "douyin", "same-work",
					"author", "name", "title", "100", "video", "EXISTING", "SKIPPED_EXISTING",
					"{\"aweme_detail\":{\"aweme_id\":\"same-work\"}}")), "NO_MORE",
					new CollectRunFetchedItem.FetchWatermark("100", "same-work", 1, 0, "0"), now.plusSeconds(3));

			assertThat(jdbc.queryForMap("SELECT process_state, attempt_count, error_code, metadata_snapshot "
					+ "FROM biz_collect_run_item WHERE run_id=99"))
					.containsEntry("process_state", "QUEUED")
					.containsEntry("attempt_count", 0)
					.containsEntry("error_code", null);
			assertThat(jdbc.queryForObject("SELECT metadata_snapshot FROM biz_collect_run_item WHERE run_id=99",
					String.class)).contains("\"aweme_id\":\"same-work\"");
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
	void runningLegacyDownloadHydrationPreservesLeaseAndProcessingState() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-08-16T08:00:00Z");
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus, count, carriedout) VALUES(7, 'done', '9', '8')");
			CollectEnqueueResult queued = transaction.enqueue(7, CollectTriggerType.SCHEDULED, 20, now, 100, 3);
			CollectJobClaim claim = transaction.claimNext("fetch-worker", now.plusSeconds(1));
			transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING,
					now.plusSeconds(2));
			jdbc.update("INSERT INTO biz_collect_run(id, collect_task_id, trigger_type, state, created_at) "
					+ "VALUES(99, 7, 'SCHEDULED', 'COMPLETED', ?)", java.sql.Timestamp.from(now));
			jdbc.update("INSERT INTO biz_collect_run_item(run_id, ordinal, platform_key, work_id, decision, "
					+ "process_state, queue_generation, error_code, attempt_count, max_attempts, locked_by, locked_at, "
					+ "created_at, updated_at) VALUES(99, 1, 'douyin', 'running-work', 'NEW', 'RUNNING', "
					+ "'FETCH_DOWNLOAD_V1', 'OLD_ERROR', 2, 4, 'download-lease', ?, ?, ?)",
					java.sql.Timestamp.from(now.plusSeconds(1)), java.sql.Timestamp.from(now),
					java.sql.Timestamp.from(now));

			transaction.storeFetchPlan(queued.runId(), 7, List.of(new CollectRunFetchedItem(1, "douyin",
					"running-work", "author", "name", "title", "100", "video", "EXISTING", "SKIPPED_EXISTING",
					"{\"aweme_detail\":{\"aweme_id\":\"running-work\"}}")), 1, "NO_MORE",
					new CollectRunFetchedItem.FetchWatermark("100", "running-work", 1, 0, "0"), null,
					now.plusSeconds(3));

			assertThat(jdbc.queryForMap("SELECT process_state, attempt_count, error_code, locked_by, "
					+ "metadata_snapshot FROM biz_collect_run_item WHERE run_id=99"))
					.containsEntry("process_state", "RUNNING")
					.containsEntry("attempt_count", 2)
					.containsEntry("error_code", "OLD_ERROR")
					.containsEntry("locked_by", "download-lease");
			assertThat(jdbc.queryForObject("SELECT metadata_snapshot FROM biz_collect_run_item WHERE run_id=99",
					String.class)).contains("\"aweme_id\":\"running-work\"");
		}
	}

	@Test
	void completeVerificationFinalizesOnlyPendingSnapshotItems() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-08-16T06:00:00Z");
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus, count, carriedout) VALUES(7, 'done', '9', '8')");
			CollectEnqueueResult queued = transaction.enqueue(7, CollectTriggerType.SCHEDULED, 20, now, 100, 3);
			CollectJobClaim claim = transaction.claimNext("worker", now.plusSeconds(1));
			transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING,
					now.plusSeconds(2));
			jdbc.update("INSERT INTO biz_collect_run(id, collect_task_id, trigger_type, state, created_at) "
					+ "VALUES(99, 7, 'SCHEDULED', 'COMPLETED', ?)", java.sql.Timestamp.from(now));
			jdbc.update("INSERT INTO biz_collect_run_item(run_id, ordinal, platform_key, work_id, decision, "
					+ "process_state, queue_generation, error_code, attempt_count, max_attempts, created_at, updated_at) "
					+ "VALUES(99, 1, 'douyin', 'missing-work', 'NEW', 'RETRY_WAIT', 'FETCH_DOWNLOAD_V1', "
					+ "'LIST_SNAPSHOT_PENDING', 0, 4, ?, ?)", java.sql.Timestamp.from(now),
					java.sql.Timestamp.from(now));

			transaction.storeFetchPlan(queued.runId(), 7, List.of(), 0, "NO_MORE",
					new CollectRunFetchedItem.FetchWatermark(null, null, 2, 0, "0"),
					new CollectBackfillProgress("author", "0", true, false, 2, now.plusSeconds(3)),
					now.plusSeconds(3));

			assertThat(jdbc.queryForMap("SELECT process_state, error_code, finished_at FROM "
					+ "biz_collect_run_item WHERE run_id=99"))
					.containsEntry("process_state", "SKIPPED_REMOTE_MISSING")
					.containsEntry("error_code", "REMOTE_LIST_MISSING");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_collect_run_event WHERE run_id=? "
					+ "AND event_code='SKIPPED_REMOTE_MISSING'", Integer.class, queued.runId())).isEqualTo(1);
		}
	}

	@Test
	void listedWorkWithoutDownloadSnapshotFinalizesMatchingSnapshotPendingItemAsBlocked() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-08-16T07:00:00Z");
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus, count, carriedout) VALUES(7, 'done', '9', '8')");
			CollectEnqueueResult queued = transaction.enqueue(7, CollectTriggerType.SCHEDULED, 20, now, 100, 3);
			CollectJobClaim claim = transaction.claimNext("worker", now.plusSeconds(1));
			transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING,
					now.plusSeconds(2));
			jdbc.update("INSERT INTO biz_collect_run(id, collect_task_id, trigger_type, state, created_at) "
					+ "VALUES(99, 7, 'SCHEDULED', 'COMPLETED', ?)", java.sql.Timestamp.from(now));
			jdbc.update("INSERT INTO biz_collect_run_item(run_id, ordinal, platform_key, work_id, decision, "
					+ "process_state, queue_generation, error_code, attempt_count, max_attempts, created_at, updated_at) "
					+ "VALUES(99, 1, 'douyin', 'blocked-work', 'NEW', 'RETRY_WAIT', 'FETCH_DOWNLOAD_V1', "
					+ "'LIST_SNAPSHOT_PENDING', 0, 4, ?, ?)", java.sql.Timestamp.from(now),
					java.sql.Timestamp.from(now));

			transaction.storeFetchPlan(queued.runId(), 7, List.of(new CollectRunFetchedItem(1, "douyin",
					"blocked-work", "author", "name", "title", "100", "video", "EXISTING", "SKIPPED_EXISTING")),
					1, "NO_MORE", new CollectRunFetchedItem.FetchWatermark("100", "blocked-work", 1, 0, "0"),
					null, now.plusSeconds(3));

			assertThat(jdbc.queryForMap("SELECT process_state, error_code, metadata_snapshot FROM "
					+ "biz_collect_run_item WHERE run_id=99"))
					.containsEntry("process_state", "SKIPPED_BLOCKED")
					.containsEntry("error_code", "WORK_BLOCKED")
					.containsEntry("metadata_snapshot", null);
		}
	}

	@Test
	void backfillProgressIsStoredWithTheFetchPlanAndRollsBackOnPlanFailure() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-08-07T09:00:00Z");
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus) VALUES(7, 'queued')");
			CollectEnqueueResult queued = transaction.enqueue(7, CollectTriggerType.SCHEDULED, 20, now, 100, 3);
			CollectJobClaim claim = transaction.claimNext("worker", now.plusSeconds(1));
			transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING,
					now.plusSeconds(2));

			CollectBackfillProgress progress = new CollectBackfillProgress(
					"MS4-author", "480", false, true, 1, null);
			transaction.storeFetchPlan(queued.runId(), 7, List.of(), 20, "NO_MORE",
					new CollectRunFetchedItem.FetchWatermark("200", "work-1", 2, 0, "500"),
					progress, now.plusSeconds(3));

			assertThat(jdbc.queryForMap("SELECT backfill_cursor, backfill_complete, backfill_source_id, "
					+ "backfill_verifying, backfill_clean_passes FROM biz_collect_data WHERE id = 7"))
					.containsEntry("backfill_cursor", "480")
					.containsEntry("backfill_complete", 0)
					.containsEntry("backfill_source_id", "MS4-author")
					.containsEntry("backfill_verifying", 1)
					.containsEntry("backfill_clean_passes", 1);

			jdbc.update("UPDATE biz_collect_run SET state='FETCHING' WHERE id = ?", queued.runId());
			jdbc.execute("CREATE TRIGGER fail_backfill_plan BEFORE INSERT ON biz_collect_run_item "
					+ "WHEN NEW.work_id = 'invalid' BEGIN SELECT RAISE(ABORT, 'forced plan failure'); END");
			CollectBackfillProgress advanced = new CollectBackfillProgress(
					"MS4-author", "900", true, false, 2, now.plusSeconds(4));
			assertThatThrownBy(() -> transaction.storeFetchPlan(queued.runId(), 7, List.of(
					new CollectRunFetchedItem(1, "douyin", "invalid", null, null, null, "100", "video",
							"NEW", "QUEUED")), 1, "NO_MORE",
					new CollectRunFetchedItem.FetchWatermark("100", "invalid", 1, 0, "900"),
					advanced, now.plusSeconds(5))).isInstanceOf(RuntimeException.class);

			assertThat(jdbc.queryForMap("SELECT backfill_cursor, backfill_complete, backfill_verifying, "
					+ "backfill_clean_passes FROM biz_collect_data WHERE id = 7"))
					.containsEntry("backfill_cursor", "480")
					.containsEntry("backfill_complete", 0)
					.containsEntry("backfill_verifying", 1)
					.containsEntry("backfill_clean_passes", 1);
		}
	}

	@Test
	void terminalRemoteAccountStateDisablesTaskAndSurvivesRunCompletion() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-08-07T10:00:00Z");
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus, taskenabled) VALUES(7, '排队中', 'Y')");
			CollectEnqueueResult queued = transaction.enqueue(7, CollectTriggerType.SCHEDULED, 20, now, 100, 3);
			CollectJobClaim claim = transaction.claimNext("worker", now.plusSeconds(1));
			transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING,
					now.plusSeconds(2));
			jdbc.update("INSERT INTO biz_collect_run(id, collect_task_id, trigger_type, state, created_at) "
					+ "VALUES(99, 7, 'SCHEDULED', 'COMPLETED', ?)", java.sql.Timestamp.from(now));
			jdbc.update("INSERT INTO biz_collect_run_item(run_id, ordinal, platform_key, work_id, decision, "
					+ "process_state, queue_generation, error_code, attempt_count, max_attempts, created_at, updated_at) "
					+ "VALUES(99, 1, 'douyin', 'account-missing-work', 'NEW', 'RETRY_WAIT', "
					+ "'FETCH_DOWNLOAD_V1', 'LIST_SNAPSHOT_PENDING', 0, 4, ?, ?)",
					java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

			transaction.storeFetchPlan(queued.runId(), 7, List.of(), 0, "ACCOUNT_DEACTIVATED",
					new CollectRunFetchedItem.FetchWatermark(null, null, 0, 0, "0"), null,
					now.plusSeconds(3));
			transaction.complete(queued.runId(), queued.jobId(), now.plusSeconds(4));

			assertThat(jdbc.queryForMap("SELECT taskenabled, taskstatus, remote_account_state, "
					+ "remote_account_reason, remote_account_detected_at FROM biz_collect_data WHERE id = 7"))
					.containsEntry("taskenabled", "N")
					.containsEntry("taskstatus", "已删号")
					.containsEntry("remote_account_state", "DEACTIVATED")
					.containsEntry("remote_account_reason", "ACCOUNT_DEACTIVATED");
			assertThat(jdbc.queryForMap("SELECT process_state, error_code FROM biz_collect_run_item WHERE run_id=99"))
					.containsEntry("process_state", "SKIPPED_REMOTE_MISSING")
					.containsEntry("error_code", "REMOTE_LIST_MISSING");
		}
	}

	@Test
	void blankStoredWatermarkAdvancesToNewestObservedWork() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-08-02T06:00:00Z");
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus, count, carriedout, last_seen_publish_time, "
					+ "last_seen_work_id) VALUES(7, 'done', '9', '8', '', 'old-work')");
			CollectEnqueueResult queued = transaction.enqueue(7, CollectTriggerType.SCHEDULED, 20, now, 10, 3);
			CollectJobClaim claim = transaction.claimNext("worker", now.plusSeconds(1));
			transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING, now.plusSeconds(2));

			transaction.storeFetchPlan(queued.runId(), 7, List.of(), "NO_MORE",
					new CollectRunFetchedItem.FetchWatermark("500", "new-work", 1, 0, "0"),
					now.plusSeconds(3));

			assertThat(jdbc.queryForObject("SELECT last_seen_publish_time FROM biz_collect_data WHERE id = 7",
					String.class)).isEqualTo("500");
			assertThat(jdbc.queryForObject("SELECT last_seen_work_id FROM biz_collect_data WHERE id = 7",
					String.class)).isEqualTo("new-work");
		}
	}

	@Test
	void emptyIncomingWatermarkDoesNotOverwriteExistingWatermark() throws Exception {
		try (AnnotationConfigApplicationContext context = context()) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			CollectQueueTransaction transaction = context.getBean(CollectQueueTransaction.class);
			Instant now = Instant.parse("2026-08-02T07:00:00Z");
			jdbc.update("INSERT INTO biz_collect_data(id, taskstatus, count, carriedout, last_seen_publish_time, "
					+ "last_seen_work_id) VALUES(7, 'done', '9', '8', '300', 'known-work')");
			CollectEnqueueResult queued = transaction.enqueue(7, CollectTriggerType.SCHEDULED, 20, now, 10, 3);
			CollectJobClaim claim = transaction.claimNext("worker", now.plusSeconds(1));
			transaction.transition(claim.runId(), CollectRunState.QUEUED, CollectRunState.FETCHING, now.plusSeconds(2));

			transaction.storeFetchPlan(queued.runId(), 7, List.of(), "EMPTY_PAGINATION",
					new CollectRunFetchedItem.FetchWatermark("", "ignored-work", 1, 1, "0"),
					now.plusSeconds(3));

			assertThat(jdbc.queryForObject("SELECT last_seen_publish_time FROM biz_collect_data WHERE id = 7",
					String.class)).isEqualTo("300");
			assertThat(jdbc.queryForObject("SELECT last_seen_work_id FROM biz_collect_data WHERE id = 7",
					String.class)).isEqualTo("known-work");
			assertThat(jdbc.queryForObject("SELECT last_successful_fetch_at FROM biz_collect_data WHERE id = 7",
					String.class)).isNotBlank();
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
		jdbc.execute("CREATE TABLE biz_collect_data (id INTEGER PRIMARY KEY, taskname TEXT, taskstatus TEXT, taskenabled TEXT, count TEXT, "
				+ "carriedout TEXT, endtime TEXT, last_successful_fetch_at TIMESTAMP, last_seen_publish_time TEXT, "
				+ "last_seen_work_id TEXT, backfill_cursor TEXT, backfill_complete INTEGER NOT NULL DEFAULT 0, "
				+ "backfill_source_id TEXT, backfill_verifying INTEGER NOT NULL DEFAULT 0, "
				+ "backfill_clean_passes INTEGER NOT NULL DEFAULT 0, backfill_verified_at TIMESTAMP, "
				+ "remote_account_state TEXT, remote_account_reason TEXT, remote_account_detected_at TIMESTAMP)");
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
	}

	@Configuration
	@EnableTransactionManagement
	static class TransactionConfiguration {
	}
}
