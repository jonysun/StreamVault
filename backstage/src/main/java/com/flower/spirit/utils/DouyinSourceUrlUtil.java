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

	private static String build(String type, String id) {
		if (id == null || id.trim().isEmpty()) {
			return null;
		}
		return "https://www.douyin.com/" + type + "/" + id.trim();
	}
}
