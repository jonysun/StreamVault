package com.flower.spirit.service;

public record CollectEnqueueResult(Long runId, Long jobId, CollectRunState state, boolean inserted,
		boolean skippedPaused, boolean skippedUnsupported, String reason) {

	public CollectEnqueueResult(long runId, Long jobId, CollectRunState state, boolean inserted,
			boolean skippedPaused) {
		this(runId, jobId, state, inserted, skippedPaused, false, null);
	}

	public static CollectEnqueueResult unsupported(String reason) {
		return new CollectEnqueueResult(null, null, null, false, false, true, reason);
	}
}
