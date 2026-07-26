package com.flower.spirit.database;

public record DatabaseRuntimeSnapshot(
		String databaseKind,
		String journalMode,
		int busyTimeoutMs,
		boolean foreignKeys,
		String synchronous,
		boolean writerLocked,
		String writerOwnerThread,
		String writerOperation,
		long writerHeldMs,
		int writerWaitingCount,
		long writeBusyRetryCount,
		long writeLockTimeoutCount) {
}
