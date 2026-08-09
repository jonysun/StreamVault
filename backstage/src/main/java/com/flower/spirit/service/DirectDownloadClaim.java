package com.flower.spirit.service;

public record DirectDownloadClaim(long jobId, String sourceUrl, String platformKey, String platformName,
		String title, String author, DirectDownloadSource sourceType, String batchId, Integer historyId,
		int attemptCount, int maxAttempts, String lockToken) {
}
