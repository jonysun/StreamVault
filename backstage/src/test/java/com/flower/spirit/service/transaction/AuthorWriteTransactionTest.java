package com.flower.spirit.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.sqlite.SQLiteDataSource;

import com.flower.spirit.service.SqliteWriteRetrier;

class AuthorWriteTransactionTest {

	@Test
	void busyAttemptRollsBackAndRetryCommitsOnceInFreshTransaction() {
		Path databaseDirectory = Path.of("target", "test-databases");
		try {
			Files.createDirectories(databaseDirectory);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Failed to create SQLite test directory", e);
		}
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + databaseDirectory.resolve(UUID.randomUUID() + "-retry.db")
				+ "?journal_mode=WAL&busy_timeout=1000");
		try (AnnotationConfigApplicationContext context = context(dataSource)) {
			JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
			jdbcTemplate.execute("CREATE TABLE author_write_probe (id INTEGER PRIMARY KEY, value TEXT UNIQUE)");
			AuthorWriteTransaction transaction = context.getBean(AuthorWriteTransaction.class);
			SqliteWriteRetrier retrier = new SqliteWriteRetrier(3, 0, 0);
			AtomicInteger attempts = new AtomicInteger();

			Integer savedId = retrier.execute(() -> transaction.execute(() -> {
				assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
				int attempt = attempts.incrementAndGet();
				jdbcTemplate.update("INSERT INTO author_write_probe(id, value) VALUES(1, 'canonical-author')");
				if (attempt == 1) {
					throw new RuntimeException("SQLITE_BUSY_SNAPSHOT simulated after write");
				}
				return 1;
			}));

			assertThat(savedId).isEqualTo(1);
			assertThat(attempts).hasValue(2);
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM author_write_probe", Integer.class))
					.isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject("PRAGMA journal_mode", String.class)).isEqualToIgnoringCase("wal");
		}
	}

	private AnnotationConfigApplicationContext context(DataSource dataSource) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.register(TransactionConfiguration.class);
		context.registerBean(DataSource.class, () -> dataSource);
		context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
		context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
		context.registerBean(AuthorWriteTransaction.class);
		context.refresh();
		return context;
	}

	@Configuration
	@EnableTransactionManagement
	static class TransactionConfiguration {
	}
}
