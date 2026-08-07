package com.flower.spirit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class SqliteSchemaPreflightTest {

	@Test
	void acceptsProductionCompatibleIdentityTablesAndNativeIdsDoNotTouchSequenceTable() {
		JdbcTemplate jdbcTemplate = jdbcTemplate("compatible.db");
		jdbcTemplate.execute("CREATE TABLE biz_author_profile (id INTEGER NOT NULL PRIMARY KEY, authoruid TEXT)");
		jdbcTemplate.execute("CREATE TABLE biz_author_name_history (id INTEGER NOT NULL PRIMARY KEY, displayname TEXT)");
		jdbcTemplate.execute("CREATE TABLE biz_author_enrichment_job (id INTEGER NOT NULL PRIMARY KEY, state TEXT)");
		jdbcTemplate.execute("CREATE TABLE biz_collect_data_detail "
				+ "(id INTEGER NOT NULL PRIMARY KEY, dataid INTEGER, videoid TEXT)");
		jdbcTemplate.execute("CREATE TABLE biz_database_maintenance_operation "
				+ "(id INTEGER NOT NULL PRIMARY KEY, status TEXT)");
		jdbcTemplate.execute("CREATE TABLE biz_video (id INTEGER NOT NULL PRIMARY KEY, videoid TEXT)");
		jdbcTemplate.execute("CREATE TABLE biz_graphic_content (id INTEGER NOT NULL PRIMARY KEY, videoid TEXT)");
		jdbcTemplate.execute("CREATE TABLE seq_common (seq_id TEXT PRIMARY KEY, seq_count INTEGER)");
		jdbcTemplate.update("INSERT INTO seq_common(seq_id, seq_count) VALUES('author', 91)");
		jdbcTemplate.update("INSERT INTO seq_common(seq_id, seq_count) VALUES('biz_video', 101)");
		jdbcTemplate.update("INSERT INTO seq_common(seq_id, seq_count) VALUES('biz_graphic_content', 202)");

		SqliteSchemaPreflight preflight = new SqliteSchemaPreflight(jdbcTemplate);
		assertThatCode(preflight::verifyIdentitySchemas).doesNotThrowAnyException();

		jdbcTemplate.update("INSERT INTO biz_author_profile(authoruid) VALUES('author-1')");
		jdbcTemplate.update("INSERT INTO biz_author_profile(authoruid) VALUES('author-2')");
		jdbcTemplate.update("INSERT INTO biz_author_name_history(displayname) VALUES('first')");
		jdbcTemplate.update("INSERT INTO biz_author_name_history(displayname) VALUES('second')");
		jdbcTemplate.update("INSERT INTO biz_author_enrichment_job(state) VALUES('QUEUED')");
		jdbcTemplate.update("INSERT INTO biz_collect_data_detail(dataid, videoid) VALUES(1, 'work-1')");
		jdbcTemplate.update("INSERT INTO biz_database_maintenance_operation(status) VALUES('RUNNING')");
		jdbcTemplate.update("INSERT INTO biz_video(videoid) VALUES('video-1')");
		jdbcTemplate.update("INSERT INTO biz_graphic_content(videoid) VALUES('graphic-1')");

		assertThat(jdbcTemplate.queryForList("SELECT id FROM biz_author_profile ORDER BY id", Integer.class))
				.containsExactly(1, 2);
		assertThat(jdbcTemplate.queryForList("SELECT id FROM biz_author_name_history ORDER BY id", Integer.class))
				.containsExactly(1, 2);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT seq_count FROM seq_common WHERE seq_id = 'author'", Integer.class)).isEqualTo(91);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT seq_count FROM seq_common WHERE seq_id = 'biz_video'", Integer.class)).isEqualTo(101);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT seq_count FROM seq_common WHERE seq_id = 'biz_graphic_content'", Integer.class)).isEqualTo(202);
		assertThat(jdbcTemplate.queryForObject("SELECT id FROM biz_author_enrichment_job", Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT id FROM biz_collect_data_detail", Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT id FROM biz_database_maintenance_operation", Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT id FROM biz_video", Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT id FROM biz_graphic_content", Integer.class)).isEqualTo(1);
	}

	@Test
	void allowsHibernateToCreateMissingTables() {
		SqliteSchemaPreflight preflight = new SqliteSchemaPreflight(jdbcTemplate("missing.db"));

		assertThatCode(preflight::verifyIdentitySchemas).doesNotThrowAnyException();
	}

	@Test
	void rejectsNonIntegerPrimaryKeyWithoutChangingSchema() {
		JdbcTemplate jdbcTemplate = jdbcTemplate("wrong-type.db");
		jdbcTemplate.execute("CREATE TABLE biz_author_profile (id BIGINT PRIMARY KEY, authoruid TEXT)");
		SqliteSchemaPreflight preflight = new SqliteSchemaPreflight(jdbcTemplate);

		assertThatThrownBy(preflight::verifyIdentitySchemas)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("biz_author_profile")
				.hasMessageContaining("no automatic table rebuild");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT type FROM pragma_table_info('biz_author_profile') WHERE name = 'id'", String.class))
				.isEqualTo("BIGINT");
	}

	@Test
	void rejectsIncompatibleExistingMediaIdentityWithoutChangingSchema() {
		JdbcTemplate jdbcTemplate = jdbcTemplate("wrong-media-type.db");
		jdbcTemplate.execute("CREATE TABLE biz_video (id BIGINT PRIMARY KEY, videoid TEXT)");
		SqliteSchemaPreflight preflight = new SqliteSchemaPreflight(jdbcTemplate);

		assertThatThrownBy(preflight::verifyIdentitySchemas)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("biz_video")
				.hasMessageContaining("single id INTEGER PRIMARY KEY")
				.hasMessageContaining("no automatic table rebuild");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT type FROM pragma_table_info('biz_video') WHERE name = 'id'", String.class))
				.isEqualTo("BIGINT");
	}

	@Test
	void rejectsIncompatibleExistingCollectDetailIdentityWithoutChangingSchema() {
		JdbcTemplate jdbcTemplate = jdbcTemplate("wrong-collect-detail-type.db");
		jdbcTemplate.execute("CREATE TABLE biz_collect_data_detail (id BIGINT PRIMARY KEY, dataid INTEGER)");
		SqliteSchemaPreflight preflight = new SqliteSchemaPreflight(jdbcTemplate);

		assertThatThrownBy(preflight::verifyIdentitySchemas)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("biz_collect_data_detail")
				.hasMessageContaining("single id INTEGER PRIMARY KEY");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT type FROM pragma_table_info('biz_collect_data_detail') WHERE name = 'id'", String.class))
				.isEqualTo("BIGINT");
	}

	@Test
	void rejectsCompositePrimaryKey() {
		JdbcTemplate jdbcTemplate = jdbcTemplate("composite.db");
		jdbcTemplate.execute("CREATE TABLE biz_author_profile (id INTEGER, authoruid TEXT, PRIMARY KEY(id, authoruid))");

		assertThatThrownBy(() -> new SqliteSchemaPreflight(jdbcTemplate).verifyIdentitySchemas())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("single id INTEGER PRIMARY KEY");
	}

	@Test
	void rejectsExistingPipelineTableWithMissingMigrationColumns() {
		JdbcTemplate jdbcTemplate = jdbcTemplate("missing-pipeline-columns.db");
		jdbcTemplate.execute("CREATE TABLE biz_collect_data (id INTEGER PRIMARY KEY, taskname TEXT)");

		assertThatThrownBy(() -> new SqliteSchemaPreflight(jdbcTemplate).verifyPipelineSchemas())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("biz_collect_data")
				.hasMessageContaining("last_successful_fetch_at")
				.hasMessageContaining("last_seen_publish_time")
				.hasMessageContaining("last_seen_work_id")
				.hasMessageContaining("backfill_cursor")
				.hasMessageContaining("backfill_complete")
				.hasMessageContaining("backfill_source_id")
				.hasMessageContaining("backfill_verifying")
				.hasMessageContaining("backfill_clean_passes")
				.hasMessageContaining("backfill_verified_at");
	}

	@Test
	void rejectsMissingRequiredPipelineTableAndNamesIt() {
		assertThatThrownBy(() -> new SqliteSchemaPreflight(jdbcTemplate("missing-table.db")).verifyPipelineSchemas())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("biz_collect_data")
				.hasMessageContaining("missing required collection pipeline table");
	}

	@Test
	void rejectsLegacyUniqueRunItemIndexAndAcceptsTheObservationIndex() {
		JdbcTemplate jdbcTemplate = jdbcTemplate("pipeline-index-migration.db");
		jdbcTemplate.execute("CREATE TABLE biz_collect_data (id INTEGER PRIMARY KEY, "
				+ "last_successful_fetch_at TIMESTAMP, last_seen_publish_time TEXT, last_seen_work_id TEXT, "
				+ "backfill_cursor TEXT, backfill_complete INTEGER, backfill_source_id TEXT, "
				+ "backfill_verifying INTEGER, backfill_clean_passes INTEGER, backfill_verified_at TIMESTAMP, "
				+ "remote_account_state TEXT, remote_account_reason TEXT, remote_account_detected_at TIMESTAMP)");
		jdbcTemplate.execute("CREATE TABLE biz_collect_run (id INTEGER PRIMARY KEY, "
				+ "fetch_stop_reason TEXT, fetch_warning TEXT)");
		jdbcTemplate.execute("CREATE TABLE biz_collect_run_item (id INTEGER PRIMARY KEY, run_id INTEGER, "
				+ "platform_key TEXT, work_id TEXT, attempt_count INTEGER, max_attempts INTEGER, available_at TIMESTAMP, "
				+ "locked_by TEXT, locked_at TIMESTAMP, started_at TIMESTAMP, finished_at TIMESTAMP, "
				+ "error_detail TEXT, queue_generation TEXT)");
		jdbcTemplate.execute("CREATE UNIQUE INDEX uq_collect_run_item_work "
				+ "ON biz_collect_run_item(run_id, platform_key, work_id)");
		SqliteSchemaPreflight preflight = new SqliteSchemaPreflight(jdbcTemplate);

		assertThatThrownBy(preflight::verifyPipelineSchemas)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("index migration is incomplete")
				.hasMessageContaining("uq_collect_run_item_work");

		jdbcTemplate.execute("DROP INDEX uq_collect_run_item_work");
		jdbcTemplate.execute("CREATE INDEX idx_collect_run_item_work "
				+ "ON biz_collect_run_item(run_id, platform_key, work_id)");
		assertThatCode(preflight::verifyPipelineSchemas).doesNotThrowAnyException();
	}

	@Test
	void identityAndPipelineListenersRunAtTheirRequiredOrders() throws Exception {
		Method identityListener = SqliteSchemaPreflight.class.getMethod("verifyIdentitySchemas");
		Method pipelineListener = SqliteSchemaPreflight.class.getMethod("verifyPipelineSchemas");

		assertThat(identityListener.getAnnotation(Order.class).value()).isEqualTo(10);
		assertThat(pipelineListener.getAnnotation(Order.class).value()).isEqualTo(180);
	}

	private JdbcTemplate jdbcTemplate(String filename) {
		Path databaseDirectory = Path.of("target", "test-databases");
		try {
			Files.createDirectories(databaseDirectory);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Failed to create SQLite test directory", e);
		}
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + databaseDirectory.resolve(UUID.randomUUID() + "-" + filename));
		return new JdbcTemplate(dataSource);
	}
}
