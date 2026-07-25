package com.flower.spirit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class SqliteSchemaPreflightTest {

	@Test
	void acceptsProductionCompatibleIdentityTablesAndNativeIdsDoNotTouchSequenceTable() {
		JdbcTemplate jdbcTemplate = jdbcTemplate("compatible.db");
		jdbcTemplate.execute("CREATE TABLE biz_author_profile (id INTEGER NOT NULL PRIMARY KEY, authoruid TEXT)");
		jdbcTemplate.execute("CREATE TABLE biz_author_name_history (id INTEGER NOT NULL PRIMARY KEY, displayname TEXT)");
		jdbcTemplate.execute("CREATE TABLE biz_author_enrichment_job (id INTEGER NOT NULL PRIMARY KEY, state TEXT)");
		jdbcTemplate.execute("CREATE TABLE biz_database_maintenance_operation "
				+ "(id INTEGER NOT NULL PRIMARY KEY, status TEXT)");
		jdbcTemplate.execute("CREATE TABLE seq_common (seq_id TEXT PRIMARY KEY, seq_count INTEGER)");
		jdbcTemplate.update("INSERT INTO seq_common(seq_id, seq_count) VALUES('author', 91)");

		SqliteSchemaPreflight preflight = new SqliteSchemaPreflight(jdbcTemplate);
		assertThatCode(preflight::verifyIdentitySchemas).doesNotThrowAnyException();

		jdbcTemplate.update("INSERT INTO biz_author_profile(authoruid) VALUES('author-1')");
		jdbcTemplate.update("INSERT INTO biz_author_profile(authoruid) VALUES('author-2')");
		jdbcTemplate.update("INSERT INTO biz_author_name_history(displayname) VALUES('first')");
		jdbcTemplate.update("INSERT INTO biz_author_name_history(displayname) VALUES('second')");
		jdbcTemplate.update("INSERT INTO biz_author_enrichment_job(state) VALUES('QUEUED')");
		jdbcTemplate.update("INSERT INTO biz_database_maintenance_operation(status) VALUES('RUNNING')");

		assertThat(jdbcTemplate.queryForList("SELECT id FROM biz_author_profile ORDER BY id", Integer.class))
				.containsExactly(1, 2);
		assertThat(jdbcTemplate.queryForList("SELECT id FROM biz_author_name_history ORDER BY id", Integer.class))
				.containsExactly(1, 2);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT seq_count FROM seq_common WHERE seq_id = 'author'", Integer.class)).isEqualTo(91);
		assertThat(jdbcTemplate.queryForObject("SELECT id FROM biz_author_enrichment_job", Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT id FROM biz_database_maintenance_operation", Integer.class)).isEqualTo(1);
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
	void rejectsCompositePrimaryKey() {
		JdbcTemplate jdbcTemplate = jdbcTemplate("composite.db");
		jdbcTemplate.execute("CREATE TABLE biz_author_profile (id INTEGER, authoruid TEXT, PRIMARY KEY(id, authoruid))");

		assertThatThrownBy(() -> new SqliteSchemaPreflight(jdbcTemplate).verifyIdentitySchemas())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("single id INTEGER PRIMARY KEY");
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
