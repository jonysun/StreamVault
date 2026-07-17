package com.flower.spirit.utils;

public final class DouyinSourceUrlUtil {

	private DouyinSourceUrlUtil() {
	}

	public static String video(String videoId) {
		return build("video", videoId);
	}

	public static String note(String noteId) {
		return build("note", noteId);
	}

	public static String graphic(String authorUid, String awemeId) {
		if (isBlank(authorUid) || isBlank(awemeId)) {
			return null;
		}
		return "https://www.douyin.com/user/" + authorUid.trim() + "?modal_id=" + awemeId.trim();
	}

	private static String build(String type, String id) {
		if (isBlank(id)) {
			return null;
		}
		return "https://www.douyin.com/" + type + "/" + id.trim();
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
