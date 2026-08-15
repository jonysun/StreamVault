package com.flower.spirit.service;

public record CollectRunFetchedItem(int ordinal, String platformKey, String workId, String authorUid,
		String nickname, String title, String publishTime, String mediaType,
		String decision, String processState, String metadataSnapshot) {

	public CollectRunFetchedItem(int ordinal, String platformKey, String workId, String authorUid,
			String nickname, String title, String publishTime, String mediaType,
			String decision, String processState) {
		this(ordinal, platformKey, workId, authorUid, nickname, title, publishTime, mediaType,
				decision, processState, null);
	}

	public record FetchWatermark(String publishTime, String workId, int pagesFetched,
			int emptyPages, String lastCursor) {
	}
}
