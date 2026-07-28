package com.flower.spirit.database.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;

class MediaIdentitySqliteIntegrationTest {

	@Test
	void mediaInsertsAfterReadSnapshotUseNativeIdsWithoutUpdatingSequenceTable() throws Exception {
		String jdbcUrl = jdbcUrl();
		StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
				.applySetting("hibernate.connection.driver_class", "org.sqlite.JDBC")
				.applySetting("hibernate.connection.url", jdbcUrl)
				.applySetting("hibernate.connection.pool_size", "4")
				.applySetting("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect")
				.applySetting("hibernate.hbm2ddl.auto", "create-drop")
				.build();
		try (SessionFactory sessionFactory = new MetadataSources(registry)
				.addAnnotatedClass(VideoDataEntity.class)
				.addAnnotatedClass(GraphicContentEntity.class)
				.buildMetadata()
				.buildSessionFactory()) {
			JdbcTemplate jdbcTemplate = jdbcTemplate(jdbcUrl);
			createSequenceSentinels(jdbcTemplate);

			VideoDataEntity video = new VideoDataEntity();
			video.setVideoid("video-1");
			persistAfterReadSnapshot(sessionFactory, "biz_video", video);

			GraphicContentEntity graphic = new GraphicContentEntity();
			graphic.setVideoid("graphic-1");
			persistAfterReadSnapshot(sessionFactory, "biz_graphic_content", graphic);

			assertThat(video.getId()).isNotNull();
			assertThat(graphic.getId()).isNotNull();
			assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_video", Integer.class)).isOne();
			assertThat(jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM biz_graphic_content", Integer.class)).isOne();
			assertThat(sequenceValue(jdbcTemplate, "biz_video")).isEqualTo(41);
			assertThat(sequenceValue(jdbcTemplate, "biz_graphic_content")).isEqualTo(73);
		} finally {
			StandardServiceRegistryBuilder.destroy(registry);
		}
	}

	private void persistAfterReadSnapshot(SessionFactory sessionFactory, String table, Object entity) {
		try (Session session = sessionFactory.openSession()) {
			Transaction transaction = session.beginTransaction();
			try {
				session.createNativeQuery("SELECT COUNT(*) FROM " + table, Long.class).getSingleResult();
				session.persist(entity);
				session.flush();
				transaction.commit();
			} catch (RuntimeException | Error error) {
				if (transaction.isActive()) transaction.rollback();
				throw error;
			}
		}
	}

	private void createSequenceSentinels(JdbcTemplate jdbcTemplate) {
		jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS seq_common "
				+ "(seq_id TEXT PRIMARY KEY, seq_count INTEGER NOT NULL)");
		jdbcTemplate.update("INSERT OR REPLACE INTO seq_common(seq_id, seq_count) VALUES('biz_video', 41)");
		jdbcTemplate.update("INSERT OR REPLACE INTO seq_common(seq_id, seq_count) "
				+ "VALUES('biz_graphic_content', 73)");
	}

	private int sequenceValue(JdbcTemplate jdbcTemplate, String segment) {
		return jdbcTemplate.queryForObject(
				"SELECT seq_count FROM seq_common WHERE seq_id = ?", Integer.class, segment);
	}

	private JdbcTemplate jdbcTemplate(String jdbcUrl) {
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl(jdbcUrl);
		return new JdbcTemplate(dataSource);
	}

	private String jdbcUrl() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		return "jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-media-identity.db")
				+ "?journal_mode=WAL&busy_timeout=3000&foreign_keys=on&synchronous=NORMAL";
	}
}
