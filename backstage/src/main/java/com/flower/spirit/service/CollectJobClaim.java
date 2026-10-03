package com.flower.spirit.service;

public record CollectJobClaim(long jobId, long runId, int taskId, CollectTriggerType triggerType,
		int attemptCount, int maxAttempts, String lastErrorCode) {

	public CollectJobClaim(long jobId, long runId, int taskId, CollectTriggerType triggerType,
			int attemptCount, int maxAttempts) {
		this(jobId, runId, taskId, triggerType, attemptCount, maxAttempts, null);
	}
}
