package com.flower.spirit.service;

public record CollectRunFetchedItem(int ordinal, String platformKey, String workId, String authorUid,
		String nickname, String title, String publishTime, String mediaType,
		String decision, String processState) {

	public record FetchWatermark(String publishTime, String workId, int pagesFetched,
			int emptyPages, String lastCursor) {
	}
}
