package com.flower.spirit.database.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

import com.flower.spirit.database.DatabaseRuntimeSnapshot;
import com.flower.spirit.service.SqliteWriteRetrier;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

class SqliteRuntimeVerifierTest {

	@Test
	void readsEffectiveConnectionPragmasAndWriterMetrics() throws Exception {
		Path directory = Path.of("target", "test-databases");
		java.nio.file.Files.createDirectories(directory);
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-runtime.db")
				+ "?journal_mode=WAL&busy_timeout=7000&foreign_keys=on&synchronous=NORMAL");
		config.setDriverClassName("org.sqlite.JDBC");
		config.setConnectionInitSql("PRAGMA busy_timeout=7000");
		config.setMaximumPoolSize(2);
		try (HikariDataSource dataSource = new HikariDataSource(config)) {
			SqliteWriteCoordinator coordinator = new SqliteWriteCoordinator(1000, 5000);
			SqliteRuntimeVerifier verifier = new SqliteRuntimeVerifier(new JdbcTemplate(dataSource), coordinator,
					new SqliteWriteRetrier(1, 0, 0));
			verifier.setEnvironment(new StandardEnvironment());

			verifier.verify();
			DatabaseRuntimeSnapshot snapshot = verifier.snapshot();

			assertThat(snapshot.databaseKind()).isEqualTo("sqlite");
			assertThat(snapshot.journalMode()).isEqualToIgnoringCase("wal");
			assertThat(snapshot.busyTimeoutMs()).isEqualTo(7000);
			assertThat(snapshot.foreignKeys()).isTrue();
			assertThat(snapshot.synchronous()).isEqualTo("normal");
			assertThat(snapshot.writerLocked()).isFalse();
		}
	}
}
