package com.flower.spirit.service;

import java.util.List;
import java.util.Map;

public record SnapshotReadResult(boolean available, int version, List<SnapshotItem> items, int totalCount,
		int storedCount, boolean truncated, int videoTotal, int graphicTotal, Map<String, Object> context,
		String warningCode, String warningMessage) {

	public static SnapshotReadResult empty() {
		return new SnapshotReadResult(true, 2, List.of(), 0, 0, false, 0, 0, Map.of(), null, null);
	}

	public static SnapshotReadResult unavailable(String code, String message) {
		return new SnapshotReadResult(false, 0, List.of(), 0, 0, false, 0, 0, Map.of(), code, message);
	}
}
