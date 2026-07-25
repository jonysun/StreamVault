package com.flower.spirit.dto;

import java.util.List;

public record MediaFeedCursorPage(List<AdminMediaFeedItem> items, String nextCursor, boolean hasMore) {
}
