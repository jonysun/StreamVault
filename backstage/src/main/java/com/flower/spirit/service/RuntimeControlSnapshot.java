package com.flower.spirit.service;

import java.util.Map;

public record RuntimeControlSnapshot(boolean allPaused, boolean collectPaused, boolean downloadPaused,
		boolean hlsPaused, boolean effectiveCollectPaused, boolean effectiveDownloadPaused,
		boolean effectiveHlsPaused, Map<String, RuntimeControlValue> values) {

	public record RuntimeControlValue(boolean enabled, String updatedAt, String updatedBy, String reason) {
	}
}
