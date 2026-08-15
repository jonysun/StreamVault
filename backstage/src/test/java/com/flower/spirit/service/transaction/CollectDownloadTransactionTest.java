package com.flower.spirit.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.sqlite.SQLiteDataSource;

import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.service.CollectDownloadClaim;
import com.flower.spirit.service.WorkIngestService.IngestResult;
import com.flower.spirit.service.WorkPersistenceService.PersistenceResult;

class CollectDownloadTransactionTest {

	private static final Instant NOW = Instant.parse("2026-07-27T06:00:00Z");

	@Test
	void claimsOnlyNewGenerationAndOrdersManualThenOrdinal() throws Exception {
		try (AnnotationConfigApplicationContext context = context(databasePath())) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			insertTaskAndRun(jdbc, 10, 100, "normal-author");
			insertTaskAndRun(jdbc, 11, 101, "other-author");
			insertTaskAndRun(jdbc, 12, 102, "manual-author");
			insertItem(jdbc, 1, 100, 9, "PENDING", null, "NEW", 0, 4, NOW, "old");
			insertItem(jdbc, 2, 100, 2, "QUEUED", "FETCH_DOWNLOAD_V1", "NEW", 0, 4, NOW, "normal");
			insertItem(jdbc, 3, 101, 1, "QUEUED", "FETCH_DOWNLOAD_V1", "NEW", 0, 4, NOW, "other");
			insertItem(jdbc, 4, 102, 8, "QUEUED", "FETCH_DOWNLOAD_V1", "MANUAL_RETRY", 0, 4, NOW,
					"manual");
			jdbc.update("UPDATE biz_collect_run_item SET metadata_snapshot = ? WHERE id = 4",
					"{\"aweme_detail\":{\"aweme_id\":\"manual\"}}");
			CollectDownloadTransaction transaction = context.getBean(CollectDownloadTransaction.class);

			CollectDownloadClaim manual = transaction.claimNext("worker-a", NOW);
			assertThat(manual.id()).isEqualTo(4L);
			assertThat(manual.metadataSnapshot()).contains("\"aweme_id\":\"manual\"");
			assertThat(transaction.claimNext("worker-a", NOW).id()).isEqualTo(3L);
			assertThat(transaction.claimNext("worker-a", NOW).id()).isEqualTo(2L);
			assertThat(transaction.claimNext("worker-a", NOW)).isNull();
			assertThat(row(jdbc, 1).get("process_state")).isEqualTo("PENDING");
			assertThat(String.valueOf(row(jdbc, 4).get("locked_by"))).startsWith("worker-a:");
			assertThat(row(jdbc, 4)).containsEntry("attempt_count", 1);
		}
	}

	@Test
	void onlyOneOfTwoTransactionInstancesCanClaimTheSameItem() throws Exception {
		Path database = databasePath();
		try (AnnotationConfigApplicationContext setup = context(database)) {
			JdbcTemplate jdbc = setup.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			insertTaskAndRun(jdbc, 10, 100, "author");
			insertItem(jdbc, 1, 100, 1, "QUEUED", "FETCH_DOWNLOAD_V1", "NEW", 0, 4, NOW, "work");
		}
		try (AnnotationConfigApplicationContext first = context(database);
				AnnotationConfigApplicationContext second = context(database)) {
			CollectDownloadTransaction one = first.getBean(CollectDownloadTransaction.class);
			CollectDownloadTransaction two = second.getBean(CollectDownloadTransaction.class);
			CountDownLatch start = new CountDownLatch(1);
			List<CollectDownloadClaim> claims = java.util.Collections.synchronizedList(new ArrayList<>());
			List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
			ExecutorService pool = Executors.newFixedThreadPool(2);
			pool.submit(() -> claim(one, "worker-a", start, claims, failures));
			pool.submit(() -> claim(two, "worker-b", start, claims, failures));
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

			assertThat(claims).extracting(CollectDownloadClaim::id).containsExactly(1L);
			assertThat(failures).allMatch(DataAccessException.class::isInstance);
			JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
			assertThat(row(jdbc, 1).get("process_state")).isEqualTo("RUNNING");
			assertThat(row(jdbc, 1).get("attempt_count")).isEqualTo(1);
		}
	}

	@Test
	void retryScheduleIsOneFiveThirtyMinutesThenFailed() throws Exception {
		try (AnnotationConfigApplicationContext context = context(databasePath())) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			insertTaskAndRun(jdbc, 10, 100, "author");
			CollectDownloadTransaction transaction = context.getBean(CollectDownloadTransaction.class);
			for (int attempt = 1; attempt <= 4; attempt++) {
				long id = attempt;
				insertItem(jdbc, id, 100, attempt, "RUNNING", "FETCH_DOWNLOAD_V1", "NEW", attempt, 4,
						NOW, "work-" + attempt);
				CollectDownloadClaim claim = new CollectDownloadClaim(id, 100, 10, "author", "douyin",
						"work-" + attempt, "video", "NEW", attempt, attempt, 4, "worker");
				transaction.retryOrFail(claim, "NETWORK_IO", "unexpected end of stream", "stack", NOW);
				Map<String, Object> state = row(jdbc, id);
				if (attempt < 4) {
					long minutes = List.of(1L, 5L, 30L).get(attempt - 1);
					assertThat(state.get("process_state")).isEqualTo("RETRY_WAIT");
					assertThat(asInstant(state.get("available_at")))
							.isEqualTo(NOW.plus(minutes, ChronoUnit.MINUTES));
					assertThat(state.get("finished_at")).isNull();
				} else {
					assertThat(state.get("process_state")).isEqualTo("FAILED");
					assertThat(asInstant(state.get("finished_at"))).isEqualTo(NOW);
				}
				assertThat(state).containsEntry("error_code", "NETWORK_IO")
						.containsEntry("error_message", "unexpected end of stream")
						.containsEntry("error_detail", "stack");
				assertThat(state.get("locked_by")).isNull();
			}
		}
	}

	@Test
	void lateStaleOwnerCannotOverwriteAReclaimedItem() throws Exception {
		try (AnnotationConfigApplicationContext context = context(databasePath())) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			insertTaskAndRun(jdbc, 10, 100, "author");
			insertItem(jdbc, 1, 100, 1, "QUEUED", "FETCH_DOWNLOAD_V1", "NEW", 0, 4, NOW, "work");
			CollectDownloadTransaction transaction = context.getBean(CollectDownloadTransaction.class);
			CollectDownloadClaim stale = transaction.claimNext("old-worker", NOW);
			assertThat(transaction.recoverStale(NOW.plusSeconds(1), NOW.plusSeconds(2))).isEqualTo(1);
			CollectDownloadClaim current = transaction.claimNext("new-worker", NOW.plusSeconds(3));

			assertThatThrownBy(() -> transaction.retryOrFail(stale, "NETWORK_IO", "late", "late-stack",
					NOW.plusSeconds(4))).isInstanceOf(IllegalStateException.class);
			assertThat(row(jdbc, 1).get("process_state")).isEqualTo("RUNNING");
			assertThat(row(jdbc, 1).get("locked_by")).isEqualTo(current.lockToken());
			assertThat(row(jdbc, 1).get("error_message")).isEqualTo("Download worker stopped before completing this item");
		}
	}

	@Test
	void manualRetryOnlyResetsFailedGenerationItemsAndRetainsAuditError() throws Exception {
		try (AnnotationConfigApplicationContext context = context(databasePath())) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			insertTaskAndRun(jdbc, 10, 100, "author");
			insertItem(jdbc, 1, 100, 1, "FAILED", "FETCH_DOWNLOAD_V1", "NEW", 4, 4, NOW, "failed");
			insertItem(jdbc, 2, 100, 2, "COMPLETED", "FETCH_DOWNLOAD_V1", "NEW", 1, 4, NOW, "done");
			insertItem(jdbc, 6, 100, 3, "FAILED", "FETCH_DOWNLOAD_V1", "AUDIT_REPAIR", 4, 4, NOW,
					"audit-failed");
			jdbc.update("UPDATE biz_collect_run_item SET error_code='NETWORK_IO', error_message='old', "
					+ "error_detail='old-stack', finished_at=? WHERE id=1", Timestamp.from(NOW));
			CollectDownloadTransaction transaction = context.getBean(CollectDownloadTransaction.class);

			assertThat(transaction.manualRetry(1, NOW.plusSeconds(10))).isTrue();
			assertThat(transaction.manualRetry(2, NOW.plusSeconds(10))).isFalse();
			assertThat(transaction.manualRetry(6, NOW.plusSeconds(10))).isTrue();
			assertThat(row(jdbc, 1)).containsEntry("process_state", "QUEUED")
					.containsEntry("decision", "MANUAL_RETRY")
					.containsEntry("attempt_count", 0)
					.containsEntry("error_detail", "old-stack");
			assertThat(row(jdbc, 1).get("finished_at")).isNull();
			assertThat(row(jdbc, 2).get("process_state")).isEqualTo("COMPLETED");
			assertThat(row(jdbc, 6)).containsEntry("process_state", "QUEUED")
					.containsEntry("decision", "MANUAL_RETRY_AUDIT_REPAIR");
		}
	}

	@Test
	void retryFailedRunAndRecoverStaleTouchOnlyEligibleGenerationItems() throws Exception {
		try (AnnotationConfigApplicationContext context = context(databasePath())) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			insertTaskAndRun(jdbc, 10, 100, "author");
			insertItem(jdbc, 1, 100, 1, "FAILED", "FETCH_DOWNLOAD_V1", "NEW", 4, 4, NOW, "failed");
			insertItem(jdbc, 2, 100, 2, "FAILED", null, "NEW", 4, 4, NOW, "legacy");
			insertItem(jdbc, 3, 100, 3, "RUNNING", "FETCH_DOWNLOAD_V1", "NEW", 1, 4,
					NOW.minus(10, ChronoUnit.MINUTES), "stale");
			insertItem(jdbc, 4, 100, 4, "RUNNING", "FETCH_DOWNLOAD_V1", "NEW", 1, 4,
					NOW.minus(1, ChronoUnit.MINUTES), "fresh");
			insertItem(jdbc, 5, 100, 5, "RUNNING", "FETCH_DOWNLOAD_V1", "NEW", 4, 4,
					NOW.minus(10, ChronoUnit.MINUTES), "exhausted-stale");
			jdbc.update("UPDATE biz_collect_run_item SET locked_by='dead', locked_at=? WHERE id=3",
					Timestamp.from(NOW.minus(10, ChronoUnit.MINUTES)));
			jdbc.update("UPDATE biz_collect_run_item SET locked_by='live', locked_at=? WHERE id=4",
					Timestamp.from(NOW.minus(1, ChronoUnit.MINUTES)));
			jdbc.update("UPDATE biz_collect_run_item SET locked_by='dead-max', locked_at=? WHERE id=5",
					Timestamp.from(NOW.minus(10, ChronoUnit.MINUTES)));
			CollectDownloadTransaction transaction = context.getBean(CollectDownloadTransaction.class);

			assertThat(transaction.retryFailedRun(100, NOW)).isEqualTo(1);
			assertThat(transaction.recoverStale(NOW.minus(5, ChronoUnit.MINUTES), NOW)).isEqualTo(2);
			assertThat(row(jdbc, 1)).containsEntry("process_state", "QUEUED")
					.containsEntry("decision", "MANUAL_RETRY").containsEntry("attempt_count", 0);
			assertThat(row(jdbc, 2).get("process_state")).isEqualTo("FAILED");
			assertThat(row(jdbc, 3)).containsEntry("process_state", "RETRY_WAIT")
					.containsEntry("error_code", "WORKER_RESTART_RECOVERY");
			assertThat(row(jdbc, 3).get("locked_by")).isNull();
			assertThat(row(jdbc, 4).get("process_state")).isEqualTo("RUNNING");
			assertThat(row(jdbc, 5)).containsEntry("process_state", "FAILED")
					.containsEntry("error_code", "WORKER_RESTART_RECOVERY");
			assertThat(row(jdbc, 5).get("finished_at")).isNotNull();
		}
	}

	@Test
	void completionLinksDetailRecountsProgressAndMarksDuplicatesWithoutAddingRows() throws Exception {
		try (AnnotationConfigApplicationContext context = context(databasePath())) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			insertTaskAndRun(jdbc, 10, 100, "author");
			insertItem(jdbc, 1, 100, 1, "RUNNING", "FETCH_DOWNLOAD_V1", "NEW", 1, 4, NOW, "work");
			jdbc.update("UPDATE biz_collect_run_item SET metadata_snapshot = 'snapshot' WHERE id = 1");
			CollectDownloadTransaction transaction = context.getBean(CollectDownloadTransaction.class);
			CollectDownloadClaim first = new CollectDownloadClaim(1, 100, 10, "author", "douyin", "work",
					"video", "NEW", 1, 1, 4, "worker");

			transaction.complete(first, completedResult(true), NOW);

			assertThat(row(jdbc, 1).get("process_state")).isEqualTo("COMPLETED");
			assertThat(row(jdbc, 1).get("metadata_snapshot")).isNull();
			assertThat(jdbc.queryForMap("SELECT * FROM biz_collect_data_detail WHERE dataid=10 AND videoid='work'"))
					.containsEntry("status", "已完成").containsEntry("mediatype", "video")
					.containsEntry("videoname", "title");
			assertThat(jdbc.queryForObject("SELECT carriedout FROM biz_collect_data WHERE id=10", String.class))
					.isEqualTo("1");
			jdbc.update("INSERT INTO biz_collect_data_detail(dataid, videoid, status, errorcode, errormsg) "
					+ "VALUES(10, 'work', '下载失败', 'OLD_ERROR', 'old failure')");

			jdbc.update("INSERT INTO biz_collect_run(id, collect_task_id) VALUES(101, 10)");
			insertItem(jdbc, 2, 101, 1, "RUNNING", "FETCH_DOWNLOAD_V1", "NEW", 1, 4,
					NOW.plusSeconds(1), "work");
			CollectDownloadClaim duplicate = new CollectDownloadClaim(2, 101, 10, "author", "douyin", "work",
					"video", "NEW", 1, 1, 4, "worker");

			transaction.complete(duplicate, completedResult(false), NOW.plusSeconds(2));

			assertThat(row(jdbc, 2).get("process_state")).isEqualTo("SKIPPED_EXISTING");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_collect_data_detail WHERE dataid=10 AND videoid='work'",
					Integer.class)).isEqualTo(2);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_collect_data_detail "
					+ "WHERE dataid=10 AND videoid='work' AND (status<>'已完成' OR errorcode IS NOT NULL)",
					Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT carriedout FROM biz_collect_data WHERE id=10", String.class))
					.isEqualTo("1");
		}
	}

	@Test
	void completionRollsBackDetailWhenLeaseNoLongerOwnsTheItem() throws Exception {
		try (AnnotationConfigApplicationContext context = context(databasePath())) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			insertTaskAndRun(jdbc, 10, 100, "author");
			insertItem(jdbc, 1, 100, 1, "RUNNING", "FETCH_DOWNLOAD_V1", "NEW", 1, 4, NOW, "work");
			CollectDownloadClaim stale = new CollectDownloadClaim(1, 100, 10, "author", "douyin", "work",
					"video", "NEW", 1, 1, 4, "old-lease");

			assertThatThrownBy(() -> context.getBean(CollectDownloadTransaction.class)
					.complete(stale, completedResult(true), NOW)).isInstanceOf(IllegalStateException.class);

			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_collect_data_detail", Integer.class)).isZero();
			assertThat(row(jdbc, 1).get("process_state")).isEqualTo("RUNNING");
		}
	}

	@Test
	void pauseAfterClaimReturnsItemWithoutConsumingAttempt() throws Exception {
		try (AnnotationConfigApplicationContext context = context(databasePath())) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			insertTaskAndRun(jdbc, 10, 100, "author");
			insertItem(jdbc, 1, 100, 1, "RUNNING", "FETCH_DOWNLOAD_V1", "NEW", 1, 4, NOW, "work");
			CollectDownloadClaim claim = new CollectDownloadClaim(1, 100, 10, "author", "douyin", "work",
					"video", "NEW", 1, 1, 4, "worker");

			context.getBean(CollectDownloadTransaction.class).deferPaused(claim, "maintenance", NOW.plusSeconds(1));

			assertThat(row(jdbc, 1)).containsEntry("process_state", "RETRY_WAIT")
					.containsEntry("attempt_count", 0)
					.containsEntry("error_code", "PAUSED_AFTER_CLAIM")
					.containsEntry("error_message", "maintenance");
			assertThat(row(jdbc, 1).get("locked_by")).isNull();
		}
	}

	@Test
	void cooldownAfterClaimReturnsItemUntilDeadlineWithoutConsumingAttempt() throws Exception {
		try (AnnotationConfigApplicationContext context = context(databasePath())) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			insertTaskAndRun(jdbc, 10, 100, "author");
			insertItem(jdbc, 1, 100, 1, "RUNNING", "FETCH_DOWNLOAD_V1", "NEW", 2, 4, NOW, "work");
			CollectDownloadClaim claim = new CollectDownloadClaim(1, 100, 10, "author", "douyin", "work",
					"video", "NEW", 1, 2, 4, "worker");
			Instant availableAt = NOW.plusSeconds(605);

			context.getBean(CollectDownloadTransaction.class).deferForCooldown(claim, availableAt,
					"global cooldown", NOW.plusSeconds(1));

			assertThat(row(jdbc, 1)).containsEntry("process_state", "RETRY_WAIT")
					.containsEntry("attempt_count", 1)
					.containsEntry("error_code", "DOUYIN_GLOBAL_COOLDOWN")
					.containsEntry("error_message", "global cooldown");
			assertThat(((Number) row(jdbc, 1).get("available_at")).longValue())
					.isEqualTo(availableAt.toEpochMilli());
			assertThat(row(jdbc, 1).get("locked_by")).isNull();
		}
	}

	private void claim(CollectDownloadTransaction transaction, String worker, CountDownLatch start,
			List<CollectDownloadClaim> claims, List<Throwable> failures) {
		try {
			start.await();
			CollectDownloadClaim claim = transaction.claimNext(worker, NOW);
			if (claim != null) claims.add(claim);
		} catch (Throwable error) {
			failures.add(error);
		}
	}

	private Path databasePath() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		return directory.resolve(UUID.randomUUID() + "-collect-download.db");
	}

	private AnnotationConfigApplicationContext context(Path database) {
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + database + "?journal_mode=WAL&busy_timeout=3000");
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.register(TransactionConfiguration.class);
		context.registerBean(DataSource.class, () -> dataSource);
		context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
		context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
		context.registerBean(CollectDownloadTransaction.class);
		context.refresh();
		return context;
	}

	private void createSchema(JdbcTemplate jdbc) {
		jdbc.execute("CREATE TABLE biz_collect_data (id INTEGER PRIMARY KEY, taskname TEXT, carriedout TEXT)");
		jdbc.execute("CREATE TABLE biz_collect_run (id INTEGER PRIMARY KEY, collect_task_id INTEGER NOT NULL)");
		jdbc.execute("CREATE TABLE biz_collect_run_item (id INTEGER PRIMARY KEY, run_id INTEGER NOT NULL, "
				+ "ordinal INTEGER NOT NULL, platform_key TEXT NOT NULL, work_id TEXT NOT NULL, media_type TEXT, "
				+ "decision TEXT NOT NULL, process_state TEXT NOT NULL, error_code TEXT, error_message TEXT, "
				+ "metadata_snapshot TEXT, "
				+ "attempt_count INTEGER NOT NULL DEFAULT 0, max_attempts INTEGER NOT NULL DEFAULT 4, "
				+ "available_at TIMESTAMP, locked_by TEXT, locked_at TIMESTAMP, started_at TIMESTAMP, "
				+ "finished_at TIMESTAMP, error_detail TEXT, queue_generation TEXT, created_at TIMESTAMP NOT NULL, "
				+ "updated_at TIMESTAMP NOT NULL)");
		jdbc.execute("CREATE TABLE biz_collect_data_detail (id INTEGER PRIMARY KEY AUTOINCREMENT, dataid INTEGER, "
				+ "videoid TEXT, videoname TEXT, originaladdress TEXT, status TEXT, mediatype TEXT, detailjson TEXT, "
				+ "processlog TEXT, errorcode TEXT, errormsg TEXT, createtime TEXT)");
	}

	private void insertTaskAndRun(JdbcTemplate jdbc, long taskId, long runId, String taskName) {
		jdbc.update("INSERT INTO biz_collect_data(id, taskname) VALUES(?, ?)", taskId, taskName);
		jdbc.update("INSERT INTO biz_collect_run(id, collect_task_id) VALUES(?, ?)", runId, taskId);
	}

	private void insertItem(JdbcTemplate jdbc, long id, long runId, int ordinal, String state, String generation,
			String decision, int attempts, int maxAttempts, Instant time, String workId) {
		jdbc.update("INSERT INTO biz_collect_run_item(id, run_id, ordinal, platform_key, work_id, media_type, "
				+ "decision, process_state, attempt_count, max_attempts, available_at, locked_by, locked_at, "
				+ "queue_generation, created_at, updated_at) VALUES(?, ?, ?, 'douyin', ?, 'video', ?, ?, ?, ?, ?, "
				+ "CASE WHEN ?='RUNNING' THEN 'worker' ELSE NULL END, CASE WHEN ?='RUNNING' THEN ? ELSE NULL END, "
				+ "?, ?, ?)", id, runId, ordinal, workId, decision, state, attempts, maxAttempts, Timestamp.from(time),
				state, state, Timestamp.from(time), generation, Timestamp.from(time), Timestamp.from(time));
	}

	private Map<String, Object> row(JdbcTemplate jdbc, long id) {
		return jdbc.queryForMap("SELECT * FROM biz_collect_run_item WHERE id=?", id);
	}

	private IngestResult completedResult(boolean created) {
		VideoDataEntity video = new VideoDataEntity();
		video.setId(30);
		WorkMetadata metadata = WorkMetadata.builder().platform(PlatformCatalog.requireByKey("douyin"))
				.workId("work").contentType(WorkContentType.VIDEO).title("title")
				.sourceUrl("https://www.douyin.com/video/work").mediaResources(List.of()).build();
		return new IngestResult(DownloadResult.Status.COMPLETED, null, metadata,
				PersistenceResult.video(created, video), created ? null : "work already exists", null);
	}

	private Instant asInstant(Object value) {
		if (value instanceof Timestamp timestamp) return timestamp.toInstant();
		if (value instanceof java.util.Date date) return date.toInstant();
		if (value instanceof Number number) return Instant.ofEpochMilli(number.longValue());
		return Instant.parse(String.valueOf(value));
	}

	@Configuration
	@EnableTransactionManagement
	static class TransactionConfiguration {
	}
}
