package com.flower.spirit.service;

import java.util.List;
import java.util.Map;

public record SnapshotEnvelope(int version, List<SnapshotItem> items, int totalCount, int storedCount,
		boolean truncated, int videoTotal, int graphicTotal, Map<String, Object> context) {
}
