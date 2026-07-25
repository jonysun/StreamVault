package com.flower.spirit.service;

public record CollectEnqueueResult(long runId, Long jobId, CollectRunState state, boolean inserted,
		boolean skippedPaused) {
}
