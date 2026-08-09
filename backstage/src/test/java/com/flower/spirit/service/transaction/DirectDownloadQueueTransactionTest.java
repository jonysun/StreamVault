package com.flower.spirit.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import com.flower.spirit.service.DirectDownloadClaim;
import com.flower.spirit.service.DirectDownloadEnqueueResult;
import com.flower.spirit.service.DirectDownloadSource;

class DirectDownloadQueueTransactionTest {

	@Test
	void enqueueDeduplicatesActiveWorkAndPersistsRetryLifecycle() throws Exception {
		JdbcTemplate jdbc = jdbcTemplate();
		createSchema(jdbc);
		DirectDownloadQueueTransaction transaction = new DirectDownloadQueueTransaction(jdbc);
		Instant now = Instant.parse("2026-08-09T01:00:00Z");

		DirectDownloadEnqueueResult queued = transaction.enqueue("https://youtu.be/work-1", "youtube", "YouTube",
				"Work one", "Author", DirectDownloadSource.YOUTUBE_COLLECTION, "batch-1", 7, now, 2);
		DirectDownloadEnqueueResult duplicate = transaction.enqueue("https://youtu.be/work-1", "youtube", "YouTube",
				"Work one", "Author", DirectDownloadSource.YOUTUBE_COLLECTION, "batch-1", 8, now, 2);

		assertThat(queued.created()).isTrue();
		assertThat(duplicate.created()).isFalse();
		assertThat(duplicate.jobId()).isEqualTo(queued.jobId());
		DirectDownloadClaim first = transaction.claimNext("worker", now.plusSeconds(1));
		assertThat(first.sourceType()).isEqualTo(DirectDownloadSource.YOUTUBE_COLLECTION);
		assertThat(first.title()).isEqualTo("Work one");
		assertThat(transaction.fail(first, "REMOTE", "temporary", now.plusSeconds(2), now.plusSeconds(30))).isTrue();
		assertThat(transaction.claimNext("worker", now.plusSeconds(29))).isNull();

		DirectDownloadClaim second = transaction.claimNext("worker", now.plusSeconds(30));
		transaction.complete(second, now.plusSeconds(31));
		assertThat(jdbc.queryForMap("SELECT state, attempt_count, locked_by FROM biz_job_queue WHERE id=?",
				queued.jobId())).containsEntry("state", "COMPLETED").containsEntry("attempt_count", 2)
				.containsEntry("locked_by", null);
	}

	private void createSchema(JdbcTemplate jdbc) {
		jdbc.execute("CREATE TABLE biz_job_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, job_type TEXT NOT NULL, "
				+ "dedupe_key TEXT NOT NULL, payload TEXT NOT NULL, state TEXT NOT NULL, priority INTEGER NOT NULL, "
				+ "available_at DATETIME NOT NULL, attempt_count INTEGER NOT NULL, max_attempts INTEGER NOT NULL, "
				+ "locked_by TEXT, locked_at DATETIME, last_error_code TEXT, last_error_message TEXT, "
				+ "created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL)");
	}

	private JdbcTemplate jdbcTemplate() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-direct-download.db"));
		return new JdbcTemplate(dataSource);
	}
}
