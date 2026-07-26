package com.flower.spirit.database.sqlite;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flower.spirit.database.DatabaseWriteContentionException;

public class SqliteWriteCoordinator {

	private static final Logger logger = LoggerFactory.getLogger(SqliteWriteCoordinator.class);

	private final ReentrantLock writerLock = new ReentrantLock(true);
	private final long warnAfterMs;
	private final long timeoutMs;
	private final AtomicInteger waitingCount = new AtomicInteger();
	private final AtomicLong lockTimeoutCount = new AtomicLong();

	private volatile String ownerThread;
	private volatile String ownerOperation;
	private volatile long ownerSinceNanos;

	public SqliteWriteCoordinator(long warnAfterMs, long timeoutMs) {
		if (warnAfterMs < 0 || timeoutMs <= 0 || warnAfterMs > timeoutMs) {
			throw new IllegalArgumentException("Invalid SQLite writer lock configuration");
		}
		this.warnAfterMs = warnAfterMs;
		this.timeoutMs = timeoutMs;
	}

	public Permit acquire(String operation) {
		String normalizedOperation = operation == null || operation.isBlank() ? "unnamed-write" : operation;
		long startedNanos = System.nanoTime();
		waitingCount.incrementAndGet();
		try {
			boolean acquired = tryAcquire(warnAfterMs);
			if (!acquired) {
				logger.warn("[SQLiteWriter] waiting operation={} waitedMs={} ownerThread={} ownerOperation={} ownerHeldMs={} waiters={}",
						normalizedOperation, elapsedMs(startedNanos), ownerThread, ownerOperation, heldMs(),
						waitingCount.get());
				acquired = tryAcquire(timeoutMs - warnAfterMs);
			}
			if (!acquired) {
				lockTimeoutCount.incrementAndGet();
				throw new DatabaseWriteContentionException("SQLite writer lock timed out operation="
						+ normalizedOperation + " timeoutMs=" + timeoutMs + " ownerThread=" + ownerThread
						+ " ownerOperation=" + ownerOperation + " ownerHeldMs=" + heldMs());
			}
			if (writerLock.getHoldCount() == 1) {
				ownerThread = Thread.currentThread().getName();
				ownerOperation = normalizedOperation;
				ownerSinceNanos = System.nanoTime();
			}
			return new Permit(this);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new DatabaseWriteContentionException(
					"Interrupted while waiting for SQLite writer lock operation=" + normalizedOperation, error);
		} finally {
			waitingCount.decrementAndGet();
		}
	}

	private boolean tryAcquire(long waitMs) throws InterruptedException {
		return waitMs <= 0 ? writerLock.tryLock() : writerLock.tryLock(waitMs, TimeUnit.MILLISECONDS);
	}

	private void release() {
		if (!writerLock.isHeldByCurrentThread()) {
			throw new IllegalStateException("SQLite writer lock released by a non-owner thread");
		}
		boolean outermost = writerLock.getHoldCount() == 1;
		if (outermost) {
			ownerThread = null;
			ownerOperation = null;
			ownerSinceNanos = 0;
		}
		writerLock.unlock();
	}

	public boolean isLocked() {
		return writerLock.isLocked();
	}

	public String ownerThread() {
		return ownerThread;
	}

	public String ownerOperation() {
		return ownerOperation;
	}

	public long heldMs() {
		long since = ownerSinceNanos;
		return since == 0 ? 0 : elapsedMs(since);
	}

	public int waitingCount() {
		return waitingCount.get();
	}

	public long lockTimeoutCount() {
		return lockTimeoutCount.get();
	}

	private long elapsedMs(long startedNanos) {
		return TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - startedNanos));
	}

	public static final class Permit implements AutoCloseable {

		private final SqliteWriteCoordinator coordinator;
		private final AtomicBoolean closed = new AtomicBoolean();

		private Permit(SqliteWriteCoordinator coordinator) {
			this.coordinator = coordinator;
		}

		@Override
		public void close() {
			if (closed.compareAndSet(false, true)) {
				coordinator.release();
			}
		}
	}
}
