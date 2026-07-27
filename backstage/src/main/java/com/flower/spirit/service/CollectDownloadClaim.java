package com.flower.spirit.service;

public record CollectDownloadClaim(long id, long runId, int taskId, String taskName,
		String platformKey, String workId, String mediaType, int ordinal,
		int attemptCount, int maxAttempts, String lockToken) {
}
