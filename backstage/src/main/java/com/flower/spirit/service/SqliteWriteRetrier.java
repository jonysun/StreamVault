package com.flower.spirit.service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.flower.spirit.database.DatabaseWriteContentionException;
import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.utils.SqliteErrors;

@Service
@ConditionalOnProperty(name = "streamvault.database.kind", havingValue = "sqlite", matchIfMissing = true)
public class SqliteWriteRetrier implements DatabaseWriteExecutor {

	private static final Logger logger = LoggerFactory.getLogger(SqliteWriteRetrier.class);

	private final int maxAttempts;
	private final long initialDelayMs;
	private final long maxDelayMs;
	private final Sleeper sleeper;
	private final AtomicLong busyRetryCount = new AtomicLong();

	@Autowired
	public SqliteWriteRetrier(
			@Value("${streamvault.sqlite.write-retry.max-attempts:6}") int maxAttempts,
			@Value("${streamvault.sqlite.write-retry.initial-delay-ms:150}") long initialDelayMs,
			@Value("${streamvault.sqlite.write-retry.max-delay-ms:2000}") long maxDelayMs) {
		this(maxAttempts, initialDelayMs, maxDelayMs, Thread::sleep);
	}

	SqliteWriteRetrier(int maxAttempts, long initialDelayMs, long maxDelayMs, Sleeper sleeper) {
		if (maxAttempts < 1 || initialDelayMs < 0 || maxDelayMs < initialDelayMs) {
			throw new IllegalArgumentException("Invalid SQLite write retry configuration");
		}
		this.maxAttempts = maxAttempts;
		this.initialDelayMs = initialDelayMs;
		this.maxDelayMs = maxDelayMs;
		this.sleeper = sleeper;
	}

	@Override
	public <T> T execute(String operation, Supplier<T> newTransactionCall) {
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return newTransactionCall.get();
			} catch (RuntimeException error) {
				if (containsWriteContention(error) || !SqliteErrors.isBusy(error) || attempt == maxAttempts) {
					throw error;
				}
				busyRetryCount.incrementAndGet();
				long delayMs = retryDelayMs(attempt);
				logger.warn("SQLite write was busy; retrying in a new transaction operation={} attempt={}/{} delayMs={} root={}",
						operation, attempt + 1, maxAttempts, delayMs, rootMessage(error));
				try {
					sleeper.sleep(delayMs);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					error.addSuppressed(interrupted);
					throw error;
				}
			}
		}
		throw new IllegalStateException("SQLite retry loop ended unexpectedly");
	}

	public long busyRetryCount() {
		return busyRetryCount.get();
	}

	private boolean containsWriteContention(Throwable error) {
		for (Throwable current = error; current != null && current.getCause() != current;
				current = current.getCause()) {
			if (current instanceof DatabaseWriteContentionException) {
				return true;
			}
		}
		return false;
	}

	private long retryDelayMs(int failedAttempt) {
		long exponential;
		if (failedAttempt >= 63 || initialDelayMs > (Long.MAX_VALUE >> failedAttempt - 1)) {
			exponential = maxDelayMs;
		} else {
			exponential = initialDelayMs << (failedAttempt - 1);
		}
		long capped = Math.min(maxDelayMs, exponential);
		if (capped <= 1) {
			return capped;
		}
		long jitterBound = Math.max(1, capped / 2);
		return Math.min(maxDelayMs, capped + ThreadLocalRandom.current().nextLong(jitterBound + 1));
	}

	private String rootMessage(Throwable error) {
		Throwable root = error;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String message = root.getMessage();
		return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
	}

	@FunctionalInterface
	interface Sleeper {
		void sleep(long delayMs) throws InterruptedException;
	}
}
