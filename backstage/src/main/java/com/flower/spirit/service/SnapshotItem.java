package com.flower.spirit.service;

public record SnapshotItem(int ordinal, String workId, String authorUid, String nickname, String title,
		String publishTime, String mediaType, String decision) {
}
