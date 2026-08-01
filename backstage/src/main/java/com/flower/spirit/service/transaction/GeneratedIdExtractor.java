package com.flower.spirit.service.transaction;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.support.KeyHolder;

final class GeneratedIdExtractor {

	private GeneratedIdExtractor() {
	}

	static long requireId(KeyHolder keys, String type) {
		List<Map<String, Object>> rows = keys.getKeyList();
		if (rows.size() != 1) {
			throw new IllegalStateException("Expected one generated key row for " + type + " but got " + rows.size());
		}

		Map<String, Object> row = rows.get(0);
		Object value = row.entrySet().stream()
				.filter(entry -> "id".equalsIgnoreCase(entry.getKey()))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElseGet(() -> row.size() == 1 ? row.values().iterator().next() : null);
		if (!(value instanceof Number number)) {
			throw new IllegalStateException("No numeric generated ID returned for " + type
					+ "; columns=" + row.keySet());
		}
		return number.longValue();
	}
}
