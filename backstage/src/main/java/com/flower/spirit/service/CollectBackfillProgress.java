package com.flower.spirit.service;

import java.time.Instant;

public record CollectBackfillProgress(String sourceId, String cursor, boolean complete,
		boolean verifying, int cleanPasses, Instant verifiedAt) {
}
