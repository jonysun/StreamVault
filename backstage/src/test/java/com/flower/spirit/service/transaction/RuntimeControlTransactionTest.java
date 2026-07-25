package com.flower.spirit.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

import com.flower.spirit.service.RuntimeControlSnapshot.RuntimeControlValue;

class RuntimeControlTransactionTest {

	@Test
	void controlsAreInitializedAndRemainPersistedAcrossReloads() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-runtime-controls.db")
				+ "?journal_mode=WAL&busy_timeout=1000");
		try (AnnotationConfigApplicationContext context = context(dataSource)) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			jdbc.execute("CREATE TABLE biz_runtime_control (control_key TEXT PRIMARY KEY, enabled INTEGER NOT NULL, "
					+ "updated_at DATETIME NOT NULL, updated_by TEXT, reason TEXT)");
			RuntimeControlTransaction transaction = context.getBean(RuntimeControlTransaction.class);
			Instant now = Instant.parse("2026-07-25T09:00:00Z");

			Map<String, RuntimeControlValue> initial = transaction.initializeAndLoad(now);
			assertThat(initial).hasSize(4);
			assertThat(initial.values()).allMatch(value -> !value.enabled());

			Map<String, RuntimeControlValue> changed = transaction.set(RuntimeControlTransaction.PAUSE_COLLECT,
					true, "admin", "维护数据库", now.plusSeconds(1));
			assertThat(changed.get(RuntimeControlTransaction.PAUSE_COLLECT).enabled()).isTrue();
			assertThat(changed.get(RuntimeControlTransaction.PAUSE_COLLECT).reason()).isEqualTo("维护数据库");

			Map<String, RuntimeControlValue> reloaded = transaction.load();
			assertThat(reloaded.get(RuntimeControlTransaction.PAUSE_COLLECT).enabled()).isTrue();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_runtime_control", Integer.class)).isEqualTo(4);
		}
	}

	private AnnotationConfigApplicationContext context(DataSource dataSource) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.register(TransactionConfiguration.class);
		context.registerBean(DataSource.class, () -> dataSource);
		context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
		context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
		context.registerBean(RuntimeControlTransaction.class);
		context.refresh();
		return context;
	}

	@Configuration
	@EnableTransactionManagement
	static class TransactionConfiguration {
	}
}
