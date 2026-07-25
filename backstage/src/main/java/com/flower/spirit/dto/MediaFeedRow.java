package com.flower.spirit.dto;

import java.time.Instant;
import java.util.List;

public record MediaFeedRow(String mediaKey, String mediaType, Integer internalId, String platformKey,
		String platformDisplayName, String workId, String authorUid, String authorUsername,
		String authorDisplayName, String authorAvatar, String title, String summary, Instant publishTime,
		Instant downloadedAt, String coverUrl, String fallbackUrl, String sourceUrl, String originalAddress,
		String favorite, String privacy, String contentType, String authorHomepage, List<MediaSlideRow> slides,
		String localVideoPath, long sortTimeMillis) {
}
