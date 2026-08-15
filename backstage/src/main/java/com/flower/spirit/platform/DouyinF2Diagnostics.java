package com.flower.spirit.platform;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/** Allowlisted, bounded transport evidence emitted by the F2 single-work command. */
public final class DouyinF2Diagnostics {

	private static final int MAX_ATTEMPTS = 2;
	private final String method;
	private final String origin;
	private final String path;
	private final boolean signedQueryPresent;
	private final List<String> queryKeyNames;
	private final List<Attempt> attempts;

	private DouyinF2Diagnostics(String method, String origin, String path, boolean signedQueryPresent,
			List<String> queryKeyNames, List<Attempt> attempts) {
		this.method = method;
		this.origin = origin;
		this.path = path;
		this.signedQueryPresent = signedQueryPresent;
		this.queryKeyNames = List.copyOf(queryKeyNames);
		this.attempts = List.copyOf(attempts);
	}

	public static DouyinF2Diagnostics from(JSONObject diagnostics) {
		if (diagnostics == null) return new DouyinF2Diagnostics(null, null, null, false, List.of(), List.of());
		JSONObject identity = diagnostics.getJSONObject("requestIdentity");
		JSONArray rawAttempts = diagnostics.getJSONArray("requestAttempts");
		JSONArray rawQueryKeys = identity == null ? null : identity.getJSONArray("queryKeyNames");
		List<String> queryKeys = new ArrayList<>();
		if (rawQueryKeys != null) {
			for (int i = 0; i < rawQueryKeys.size() && queryKeys.size() < 24; i++) {
				String key = queryKeyName(rawQueryKeys.getString(i));
				if (key != null) queryKeys.add(key);
			}
		}
		List<Attempt> attempts = new ArrayList<>();
		if (rawAttempts != null) {
			for (int i = Math.max(0, rawAttempts.size() - MAX_ATTEMPTS); i < rawAttempts.size(); i++) {
				JSONObject attempt = rawAttempts.getJSONObject(i);
				if (attempt != null) attempts.add(Attempt.from(attempt));
			}
		}
		return new DouyinF2Diagnostics(text(identity, "method", 12), text(identity, "origin", 120),
				text(identity, "path", 180), identity != null && Boolean.TRUE.equals(identity.getBoolean("signedQueryPresent")), queryKeys, attempts);
	}

	public String summary() {
		if (attempts.isEmpty()) return "f2Evidence=unavailable";
		Attempt last = attempts.get(attempts.size() - 1);
		String attemptSummary = attempts.stream().map(attempt -> "attempt=" + attempt.attempt
				+ ":status=" + value(attempt.statusCode, "none") + ",empty=" + attempt.bodyEmpty
				+ ",error=" + value(attempt.errorKind, "none"))
				.collect(java.util.stream.Collectors.joining(";"));
		return "f2 endpoint=" + value(method, "GET") + " " + value(origin, "unknown") + value(path, "")
				+ " queryKeys=" + queryKeyNames + " signedQuery=" + signedQueryPresent + " attempts=" + attempts.size()
				+ " history=[" + attemptSummary + "]"
				+ " last={attempt=" + last.attempt + ",status=" + value(last.statusCode, "none")
				+ ",empty=" + last.bodyEmpty + ",bodyLength=" + last.bodyLength
				+ ",contentType=" + value(last.contentType, "none") + ",errorKind="
				+ value(last.errorKind, "none") + ",exception=" + value(last.exceptionType, "none")
				+ ",durationMs=" + last.durationMs + "}";
	}

	private static String text(JSONObject value, String key, int max) {
		if (value == null) return null;
		String raw = value.getString(key);
		if (raw == null || raw.isBlank()) return null;
		String safe = raw.replaceAll("[^A-Za-z0-9 ._:/-]", "?").trim();
		return safe.length() <= max ? safe : safe.substring(0, max);
	}

	private static String queryKeyName(String value) {
		if (value == null || !value.matches("[A-Za-z0-9_-]{1,60}")) return null;
		return value;
	}

	private static String value(Object value, String fallback) { return value == null || value.toString().isBlank() ? fallback : value.toString(); }

	private static final class Attempt {
		private final int attempt; private final Integer statusCode; private final boolean bodyEmpty;
		private final long bodyLength; private final String contentType; private final String errorKind;
		private final String exceptionType; private final long durationMs;
		private Attempt(int attempt, Integer statusCode, boolean bodyEmpty, long bodyLength, String contentType,
				String errorKind, String exceptionType, long durationMs) {
			this.attempt = attempt; this.statusCode = statusCode; this.bodyEmpty = bodyEmpty; this.bodyLength = bodyLength;
			this.contentType = contentType; this.errorKind = errorKind; this.exceptionType = exceptionType; this.durationMs = durationMs;
		}
		private static Attempt from(JSONObject value) {
			Integer status = value.getInteger("statusCode");
			if (status != null && (status < 100 || status > 599)) status = null;
			return new Attempt(clamp(value.getInteger("attempt"), 1, 99), status, Boolean.TRUE.equals(value.getBoolean("bodyEmpty")),
					clamp(value.getLong("bodyLength"), 0, 50_000_000), text(value, "contentType", 120),
					text(value, "errorKind", 80), text(value, "exceptionType", 120), clamp(value.getLong("durationMs"), 0, 300_000));
		}
		private static int clamp(Integer value, int min, int max) { return value == null ? min : Math.max(min, Math.min(max, value)); }
		private static long clamp(Long value, long min, long max) { return value == null ? min : Math.max(min, Math.min(max, value)); }
	}
}
