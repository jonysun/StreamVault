package com.flower.spirit.dto;

import java.time.Instant;

public record FeedCursor(Instant sortTime, String mediaType, int internalId, String order, String filterHash) {
}
