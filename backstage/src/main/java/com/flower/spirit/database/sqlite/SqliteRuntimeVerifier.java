package com.flower.spirit.database.sqlite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.flower.spirit.database.DatabaseRuntimeSnapshot;
import com.flower.spirit.service.SqliteWriteRetrier;

@Service
@ConditionalOnProperty(name = "streamvault.database.kind", havingValue = "sqlite", matchIfMissing = true)
public class SqliteRuntimeVerifier implements EnvironmentAware {

	private static final Logger logger = LoggerFactory.getLogger(SqliteRuntimeVerifier.class);

	private final JdbcTemplate jdbcTemplate;
	private final SqliteWriteCoordinator coordinator;
	private final SqliteWriteRetrier writeRetrier;
	private Environment environment;
	private volatile PragmaSnapshot pragmas = new PragmaSnapshot("unknown", 0, false, "unknown");

	public SqliteRuntimeVerifier(JdbcTemplate jdbcTemplate, SqliteWriteCoordinator coordinator,
			SqliteWriteRetrier writeRetrier) {
		this.jdbcTemplate = jdbcTemplate;
		this.coordinator = coordinator;
		this.writeRetrier = writeRetrier;
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	public void verify() {
		java.util.List<String> integrity = jdbcTemplate.query("PRAGMA quick_check(1)",
				(resultSet, rowNumber) -> resultSet.getString(1));
		if (integrity.size() != 1 || !"ok".equalsIgnoreCase(integrity.get(0))) {
			throw new IllegalStateException("SQLite quick_check failed: " + String.join("; ", integrity));
		}
		String journalMode = value("PRAGMA journal_mode", String.class, "unknown");
		int busyTimeoutMs = value("PRAGMA busy_timeout", Integer.class, 0);
		boolean foreignKeys = value("PRAGMA foreign_keys", Integer.class, 0) != 0;
		int synchronousValue = value("PRAGMA synchronous", Integer.class, -1);
		String synchronous = synchronousName(synchronousValue);
		pragmas = new PragmaSnapshot(journalMode, busyTimeoutMs, foreignKeys, synchronous);

		logger.info("[SQLiteRuntime] journalMode={} busyTimeoutMs={} foreignKeys={} synchronous={}",
				journalMode, busyTimeoutMs, foreignKeys, synchronous);

		boolean invalid = !"wal".equalsIgnoreCase(journalMode) || busyTimeoutMs < 5000 || !foreignKeys;
		if (!invalid) {
			return;
		}
		String message = "Invalid SQLite runtime PRAGMAs: journalMode=" + journalMode
				+ " busyTimeoutMs=" + busyTimeoutMs + " foreignKeys=" + foreignKeys;
		if (environment != null && environment.acceptsProfiles(Profiles.of("docker", "prod"))) {
			throw new IllegalStateException(message);
		}
		logger.warn("[SQLiteRuntime] {}", message);
	}

	public DatabaseRuntimeSnapshot snapshot() {
		PragmaSnapshot current = pragmas;
		return new DatabaseRuntimeSnapshot("sqlite", current.journalMode(), current.busyTimeoutMs(),
				current.foreignKeys(), current.synchronous(), coordinator.isLocked(), coordinator.ownerThread(),
				coordinator.ownerOperation(), coordinator.heldMs(), coordinator.waitingCount(),
				writeRetrier.busyRetryCount(), coordinator.lockTimeoutCount());
	}

	private <T> T value(String sql, Class<T> type, T fallback) {
		T value = jdbcTemplate.queryForObject(sql, type);
		return value == null ? fallback : value;
	}

	private String synchronousName(int value) {
		return switch (value) {
		case 0 -> "off";
		case 1 -> "normal";
		case 2 -> "full";
		case 3 -> "extra";
		default -> "unknown(" + value + ")";
		};
	}

	private record PragmaSnapshot(String journalMode, int busyTimeoutMs, boolean foreignKeys, String synchronous) {
	}
}
