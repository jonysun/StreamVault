package com.flower.spirit.database.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.sqlite.SQLiteDataSource;

import jakarta.persistence.EntityManagerFactory;

class SqliteSerializingJpaTransactionManagerTest {

	@Test
	void writeTransactionsSerializeWhileReadOnlyTransactionContinues() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-serialized.db")
				+ "?journal_mode=WAL&busy_timeout=5000&foreign_keys=on&synchronous=NORMAL");

		try (AnnotationConfigApplicationContext context = context(dataSource)) {
			JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
			jdbcTemplate.execute("CREATE TABLE write_probe (id INTEGER PRIMARY KEY, value TEXT)");
			ProbeService service = context.getBean(ProbeService.class);
			SqliteWriteCoordinator coordinator = context.getBean(SqliteWriteCoordinator.class);
			assertThat(context.getBean(PlatformTransactionManager.class))
					.isInstanceOf(SqliteSerializingJpaTransactionManager.class);

			CountDownLatch firstEntered = new CountDownLatch(1);
			CountDownLatch releaseFirst = new CountDownLatch(1);
			ExecutorService executor = Executors.newFixedThreadPool(2);
			try {
				Future<?> first = executor.submit(() -> service.insertAndHold(1, firstEntered, releaseFirst));
				assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
				assertThat(coordinator.ownerOperation()).contains("insertAndHold");

				long readStarted = System.nanoTime();
				assertThat(service.count()).isZero();
				assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - readStarted)).isLessThan(1000);

				Future<?> second = executor.submit(() -> service.insert(2));
				awaitWaitingWriter(coordinator);
				assertThat(second.isDone()).isFalse();
				releaseFirst.countDown();
				first.get(2, TimeUnit.SECONDS);
				second.get(2, TimeUnit.SECONDS);
				assertThat(service.count()).isEqualTo(2);
			} finally {
				releaseFirst.countDown();
				executor.shutdownNow();
			}
		}
	}

	private AnnotationConfigApplicationContext context(DataSource dataSource) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.register(TestConfiguration.class);
		context.registerBean(DataSource.class, () -> dataSource);
		context.refresh();
		return context;
	}

	private void awaitWaitingWriter(SqliteWriteCoordinator coordinator) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (coordinator.waitingCount() == 0 && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(coordinator.waitingCount()).isEqualTo(1);
	}

	@Configuration
	@EnableTransactionManagement
	static class TestConfiguration {

		@Bean
		LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
			LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
			factory.setDataSource(dataSource);
			factory.setPackagesToScan("com.flower.spirit.entity");
			factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
			Properties properties = new Properties();
			properties.setProperty("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
			properties.setProperty("hibernate.hbm2ddl.auto", "none");
			factory.setJpaProperties(properties);
			return factory;
		}

		@Bean
		SqliteWriteCoordinator sqliteWriteCoordinator() {
			return new SqliteWriteCoordinator(1000, 5000);
		}

		@Bean
		PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory,
				SqliteWriteCoordinator coordinator) {
			JpaTransactionManager manager = new SqliteSerializingJpaTransactionManager(entityManagerFactory,
					coordinator);
			return manager;
		}

		@Bean
		JdbcTemplate jdbcTemplate(DataSource dataSource) {
			return new JdbcTemplate(dataSource);
		}

		@Bean
		ProbeService probeService(JdbcTemplate jdbcTemplate) {
			return new ProbeService(jdbcTemplate);
		}
	}

	static class ProbeService {

		private final JdbcTemplate jdbcTemplate;

		ProbeService(JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
		}

		@Transactional
		public void insertAndHold(int id, CountDownLatch entered, CountDownLatch release) {
			jdbcTemplate.update("INSERT INTO write_probe(id, value) VALUES(?, ?)", id, "value-" + id);
			entered.countDown();
			try {
				release.await();
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(error);
			}
		}

		@Transactional
		public void insert(int id) {
			jdbcTemplate.update("INSERT INTO write_probe(id, value) VALUES(?, ?)", id, "value-" + id);
		}

		@Transactional(readOnly = true)
		public int count() {
			return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM write_probe", Integer.class);
		}
	}
}
