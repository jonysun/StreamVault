package com.flower.spirit.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

class SqliteErrorsTest {

	@Test
	void recognizesBusySnapshotThroughNestedCause() {
		RuntimeException error = new RuntimeException("repository failed",
				new SQLException("[SQLITE_BUSY_SNAPSHOT] Another database connection has already written", null, 517));

		assertThat(SqliteErrors.isBusy(error)).isTrue();
	}

	@Test
	void recognizesLockedDatabaseMessage() {
		assertThat(SqliteErrors.isBusy(new RuntimeException("database is locked"))).isTrue();
	}

	@Test
	void doesNotClassifyUnrelatedSqlErrorsAsBusy() {
		assertThat(SqliteErrors.isBusy(new SQLException("constraint failed", null, 19))).isFalse();
	}
}
