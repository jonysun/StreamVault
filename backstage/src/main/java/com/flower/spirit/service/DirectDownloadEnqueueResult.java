package com.flower.spirit.service;

public record DirectDownloadEnqueueResult(long jobId, Integer historyId, boolean created,
		DirectDownloadSource sourceType) {
}
