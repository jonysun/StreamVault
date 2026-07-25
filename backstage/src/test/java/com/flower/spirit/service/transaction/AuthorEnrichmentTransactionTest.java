package com.flower.spirit.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import com.flower.spirit.service.AuthorEnrichmentClaim;
import com.flower.spirit.service.AuthorEnrichmentEnqueueResult;

class AuthorEnrichmentTransactionTest {

	@Test
	void enqueueIsIdempotentManualRefreshPromotesAndClaimIsExclusive() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-author-queue.db")
				+ "?journal_mode=WAL&busy_timeout=1000");
		try (AnnotationConfigApplicationContext context = context(dataSource)) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			createSchema(jdbc);
			AuthorEnrichmentTransaction transaction = context.getBean(AuthorEnrichmentTransaction.class);
			Instant now = Instant.parse("2026-07-25T05:00:00Z");

			AuthorEnrichmentEnqueueResult first = transaction.enqueue("douyin", "MS4-author", 100, false, now);
			AuthorEnrichmentEnqueueResult duplicate = transaction.enqueue("douyin", "MS4-author", 100, false,
					now.plusSeconds(1));
			AuthorEnrichmentEnqueueResult promoted = transaction.enqueue("douyin", "MS4-author", 0, true,
					now.plusSeconds(2));

			assertThat(first.created()).isTrue();
			assertThat(duplicate.jobId()).isEqualTo(first.jobId());
			assertThat(duplicate.created()).isFalse();
			assertThat(promoted.jobId()).isEqualTo(first.jobId());
			assertThat(promoted.promoted()).isTrue();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_author_enrichment_job", Integer.class))
					.isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT priority FROM biz_author_enrichment_job", Integer.class)).isZero();

			AuthorEnrichmentClaim claim = transaction.claimNext(now.plusSeconds(3), 15);
			assertThat(claim.id()).isEqualTo(first.jobId());
			assertThat(claim.attemptCount()).isEqualTo(1);
			assertThat(transaction.claimNext(now.plusSeconds(3), 15)).isNull();

			transaction.retryLater(claim.id(), "RISK_CONTROL", "cookie expired",
					now.plus(1, ChronoUnit.HOURS), now.plusSeconds(4));
			assertThat(jdbc.queryForObject("SELECT state FROM biz_author_enrichment_job", String.class))
					.isEqualTo("RETRY_WAIT");
			assertThat(transaction.claimNext(now.plus(30, ChronoUnit.MINUTES), 15)).isNull();
			assertThat(transaction.claimNext(now.plus(2, ChronoUnit.HOURS), 15)).isNotNull();

			jdbc.update("INSERT INTO biz_video(id, platformkey, videoplatform, authoruid, secuid) "
					+ "VALUES(1, 'douyin', '抖音', 'MS4-missing', 'MS4-missing')");
			assertThat(transaction.enqueueMissingWorkAuthors(now.plus(3, ChronoUnit.HOURS), 100)).isEqualTo(1);
			assertThat(transaction.enqueueMissingWorkAuthors(now.plus(3, ChronoUnit.HOURS), 100)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_author_enrichment_job "
					+ "WHERE author_uid = 'MS4-missing'", Integer.class)).isEqualTo(1);
		}
	}

	private AnnotationConfigApplicationContext context(DataSource dataSource) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.register(TransactionConfiguration.class);
		context.registerBean(DataSource.class, () -> dataSource);
		context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
		context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
		context.registerBean(AuthorEnrichmentTransaction.class);
		context.refresh();
		return context;
	}

	private void createSchema(JdbcTemplate jdbc) {
		jdbc.execute("CREATE TABLE biz_author_enrichment_job (id INTEGER PRIMARY KEY AUTOINCREMENT, "
				+ "platform_key TEXT NOT NULL, author_uid TEXT NOT NULL, state TEXT NOT NULL, "
				+ "priority INTEGER NOT NULL, attempt_count INTEGER NOT NULL, next_attempt_at DATETIME NOT NULL, "
				+ "locked_at DATETIME, last_error_code TEXT, last_error_message TEXT, "
				+ "created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL)");
		jdbc.execute("CREATE UNIQUE INDEX uq_author_enrichment_active ON biz_author_enrichment_job(platform_key, "
				+ "author_uid) WHERE state IN ('QUEUED','RUNNING','RETRY_WAIT')");
		jdbc.execute("CREATE TABLE biz_video (id INTEGER PRIMARY KEY, platformkey TEXT, videoplatform TEXT, "
				+ "authoruid TEXT, secuid TEXT)");
		jdbc.execute("CREATE TABLE biz_graphic_content (id INTEGER PRIMARY KEY, platformkey TEXT, platform TEXT, "
				+ "authoruid TEXT, secuid TEXT)");
		jdbc.execute("CREATE TABLE biz_author_profile (id INTEGER PRIMARY KEY, platformkey TEXT, platform TEXT, "
				+ "authoruid TEXT, username TEXT, displayname TEXT, avatar TEXT, signature TEXT, homepage TEXT)");
	}

	@Configuration
	@EnableTransactionManagement
	static class TransactionConfiguration {
	}
}
