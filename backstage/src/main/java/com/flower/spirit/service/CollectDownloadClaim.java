package com.flower.spirit.service;

public record CollectDownloadClaim(long id, long runId, int taskId, String taskName,
		String platformKey, String workId, String mediaType, String decision, int ordinal,
		int attemptCount, int maxAttempts, String lockToken, String metadataSnapshot) {

	public CollectDownloadClaim(long id, long runId, int taskId, String taskName,
			String platformKey, String workId, String mediaType, String decision, int ordinal,
			int attemptCount, int maxAttempts, String lockToken) {
		this(id, runId, taskId, taskName, platformKey, workId, mediaType, decision, ordinal,
				attemptCount, maxAttempts, lockToken, null);
	}
}
