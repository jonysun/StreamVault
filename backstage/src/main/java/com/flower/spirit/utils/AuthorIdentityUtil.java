package com.flower.spirit.utils;

import com.flower.spirit.platform.PlatformCatalog;

public final class AuthorIdentityUtil {

	private AuthorIdentityUtil() {
	}

	public static boolean isDouyinPlatform(String platform) {
		return "douyin".equals(canonicalPlatformKey(platform, platform));
	}

	public static String canonicalPlatformKey(String platformKey, String legacyPlatform) {
		return PlatformCatalog.canonicalKey(platformKey, legacyPlatform);
	}

	public static boolean isDouyinSecUid(String value) {
		String normalized = trimToNull(value);
		return normalized != null && normalized.startsWith("MS4");
	}

	public static String canonicalAuthorUid(String platform, String authoruid, String secuid) {
		if (isDouyinPlatform(platform)) {
			String normalizedSecUid = trimToNull(secuid);
			if (isDouyinSecUid(normalizedSecUid)) {
				return normalizedSecUid;
			}
			String normalizedAuthorUid = trimToNull(authoruid);
			return isDouyinSecUid(normalizedAuthorUid) ? normalizedAuthorUid : null;
		}
		return firstNotBlank(authoruid, secuid);
	}

	public static AuthorKey authorKey(String platformKey, String legacyPlatform, String authoruid, String secuid) {
		String canonicalPlatformKey = canonicalPlatformKey(platformKey, legacyPlatform);
		String canonicalUid = canonicalAuthorUid(canonicalPlatformKey, authoruid, secuid);
		return canonicalPlatformKey == null || canonicalUid == null
				? null : new AuthorKey(canonicalPlatformKey, canonicalUid);
	}

	public static String canonicalUsername(String authorusername, String uniqueid) {
		return firstNotBlank(authorusername, uniqueid);
	}

	public static String douyinHomepage(String authorUid) {
		String canonicalUid = canonicalAuthorUid("douyin", authorUid, authorUid);
		return canonicalUid == null ? null : "https://www.douyin.com/user/" + canonicalUid;
	}

	public static String sanitizeHomepage(String platform, String authorUid, String homepage) {
		if (isDouyinPlatform(platform)) {
			return douyinHomepage(authorUid);
		}
		String value = trimToNull(homepage);
		return value != null && (value.startsWith("http://") || value.startsWith("https://")) ? value : null;
	}

	public static String firstNotBlank(String first, String second) {
		String normalizedFirst = trimToNull(first);
		return normalizedFirst != null ? normalizedFirst : trimToNull(second);
	}

	public static String trimToNull(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		return value.trim();
	}

	public record AuthorKey(String platformKey, String authorUid) {
	}
}
