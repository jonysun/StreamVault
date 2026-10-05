package com.flower.spirit.dto;

import java.time.Instant;

public record FeedCursor(Instant sortTime, String mediaType, int internalId, String order, String filterHash,
		boolean nullTime) {
	public FeedCursor(Instant sortTime, String mediaType, int internalId, String order, String filterHash) {
		this(sortTime, mediaType, internalId, order, filterHash, false);
	}
}
