package com.flower.spirit.service;

public enum DirectDownloadSource {
	SINGLE_LINK,
	YOUTUBE_COLLECTION;

	public static DirectDownloadSource from(String value) {
		if (value == null || value.isBlank()) return SINGLE_LINK;
		try {
			return valueOf(value.trim().toUpperCase());
		} catch (IllegalArgumentException ignored) {
			return SINGLE_LINK;
		}
	}
}
