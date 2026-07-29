package com.flower.spirit.service;

import java.util.Set;

public record DouyinFetchRequest(String secUserId, Set<String> knownWorkIds,
		String lastSeenPublishTime, int knownBoundary, int maxPages,
		int emptyPageLimit, DouyinFetchMode mode, int maxItems, String cookie) {

	public DouyinFetchRequest(String secUserId, Set<String> knownWorkIds,
			String lastSeenPublishTime, int knownBoundary, int maxPages,
			int emptyPageLimit, DouyinFetchMode mode, int maxItems) {
		this(secUserId, knownWorkIds, lastSeenPublishTime, knownBoundary, maxPages,
				emptyPageLimit, mode, maxItems, null);
	}
}
