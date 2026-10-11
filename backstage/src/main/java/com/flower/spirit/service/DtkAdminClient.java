package com.flower.spirit.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.entity.ConfigEntity;

/** Isolated client for the self-hosted DTK administrative API. */
@Service
public class DtkAdminClient {
	private static final Logger logger = LoggerFactory.getLogger(DtkAdminClient.class);
	private static final int PAGE_SIZE = 500;
	private static final int MAX_PAGES = 1000;
	private final HttpClient client;
	private final ConfigService configService;

	@Autowired
	public DtkAdminClient(ConfigService configService) {
		this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), configService);
	}

	DtkAdminClient(HttpClient client, ConfigService configService) {
		this.client = client;
		this.configService = configService;
	}

	public List<WatchEntry> listAll(String platform) {
		List<WatchEntry> result = new ArrayList<>();
		for (int page = 0; page < MAX_PAGES; page++) {
			int offset = page * PAGE_SIZE;
			JSONObject response = request("GET", "/api/v1/admin/watchlist?platform=" + encode(platform)
					+ "&kind=author&limit=" + PAGE_SIZE + "&offset=" + offset + "&lang=zh", null);
			List<WatchEntry> entries = parseEntries(response);
			result.addAll(entries);
			if (!hasMore(response, entries.size(), PAGE_SIZE, offset)) return List.copyOf(result);
		}
		throw new DtkAdminException(502, "DTK_ADMIN_PAGINATION_INCOMPLETE", "DTK Admin watchlist 超过最大分页页数");
	}

	public WatchEntry add(String platform, String targetId, String label) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("platform", platform);
		body.put("kind", "author");
		body.put("target_id", targetId);
		if (label != null && !label.isBlank()) body.put("label", label.trim());
		JSONObject response = request("POST", "/api/v1/admin/watchlist?lang=zh", JSON.toJSONString(body));
		WatchEntry entry = parseSingleEntry(response);
		return entry == null ? new WatchEntry(text(response, "id", "entry_id"), platform, "author", targetId, true) : entry;
	}

	public void delete(String entryId) {
		if (entryId == null || entryId.isBlank()) throw new DtkAdminException(400, "DTK_ADMIN_INVALID_ENTRY", "DTK watchlist entry_id 为空");
		request("DELETE", "/api/v1/admin/watchlist/" + encode(entryId) + "?lang=zh", null);
	}

	public void testConnection() {
		listAll("douyin");
	}

	private JSONObject request(String method, String path, String body) {
		ConfigEntity config = configService.getData();
		String baseUrl = normalizeBaseUrl(config.getDtkAdminBaseUrl());
		String apiKey = config.getDtkAdminApiKey() == null ? "" : config.getDtkAdminApiKey().trim();
		if (baseUrl.isBlank()) throw new DtkAdminException(400, "DTK_ADMIN_NOT_CONFIGURED", "DTK Admin Base URL 未配置");
		if (apiKey.isBlank()) throw new DtkAdminException(400, "DTK_ADMIN_KEY_NOT_CONFIGURED", "DTK Admin Key 未配置");
		int maxAttempts = "POST".equals(method) ? 2 : 3;
		DtkAdminException last = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
						.timeout(Duration.ofMillis(timeout(config)))
						.header("X-API-Key", apiKey)
						.header("Accept", "application/json")
						.header("Accept-Language", "zh");
				if ("GET".equals(method)) builder.GET();
				else if ("DELETE".equals(method)) builder.DELETE();
				else builder.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body, StandardCharsets.UTF_8));
				HttpResponse<String> http = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				JSONObject response;
				try {
					response = JSON.parseObject(http.body() == null || http.body().isBlank() ? "{}" : http.body());
				} catch (RuntimeException parseError) {
					throw new DtkAdminException(http.statusCode(), "DTK_ADMIN_INVALID_JSON", "DTK Admin 返回了无法解析的 JSON", parseError);
				}
				boolean success = response != null && Boolean.TRUE.equals(response.getBoolean("success"));
				if (http.statusCode() >= 200 && http.statusCode() < 300 && success) return response;
				JSONObject error = response == null ? null : response.getJSONObject("error");
				String code = error == null ? "DTK_ADMIN_HTTP_" + http.statusCode() : error.getString("code");
				String message = error == null ? "DTK Admin HTTP status=" + http.statusCode() : error.getString("message");
				JSONObject meta = response == null ? null : response.getJSONObject("meta");
				String requestId = meta == null ? null : meta.getString("request_id");
				last = new DtkAdminException(http.statusCode(), code, message, requestId);
				logger.warn("[DtkAdmin] failed method={} path={} attempt={}/{} status={} code={} requestId={} message={}", method, path,
						attempt, maxAttempts, http.statusCode(), code, requestId, preview(message));
				if (!retryable(http.statusCode(), error) || ("POST".equals(method) && !safeToRetryPost(http.statusCode(), error))
						|| attempt == maxAttempts) throw last;
				pauseBeforeRetry(error, attempt);
			} catch (DtkAdminException e) {
				if (e == last) throw e;
				last = e;
				if (!retryable(e.status) || ("POST".equals(method) && !safeToRetryPost(e.status, null))
						|| attempt == maxAttempts) throw e;
				pauseBeforeRetry(null, attempt);
			} catch (Exception e) {
				last = new DtkAdminException(503, "DTK_ADMIN_NETWORK", "DTK Admin 请求失败: " + preview(e.getMessage()), e);
				logger.warn("[DtkAdmin] request failed method={} path={} attempt={}/{} error={}", method, path, attempt, maxAttempts, preview(e.getMessage()));
				if ("POST".equals(method) || attempt == maxAttempts) throw last;
				pauseBeforeRetry(null, attempt);
			}
		}
		throw last == null ? new DtkAdminException(503, "DTK_ADMIN_NETWORK", "DTK Admin 请求失败") : last;
	}

	private List<WatchEntry> parseEntries(JSONObject response) {
		Object data = response == null ? null : response.get("data");
		JSONArray array = data instanceof JSONArray ? (JSONArray) data : null;
		if (data instanceof JSONObject object) {
			for (String key : List.of("items", "entries", "watchlist", "results", "entry", "item")) {
				if (object.get(key) instanceof JSONArray candidate) { array = candidate; break; }
				if (object.get(key) instanceof JSONObject candidate) {
					array = new JSONArray();
					array.add(candidate);
					break;
				}
			}
			if (array == null && looksLikeEntry(object)) {
				array = new JSONArray();
				array.add(object);
			}
		}
		if (array == null) {
			if (data == null) return Collections.emptyList();
			throw new DtkAdminException(502, "DTK_ADMIN_SCHEMA", "DTK Admin watchlist 返回结构缺少 entries/items");
		}
		List<WatchEntry> entries = new ArrayList<>();
		for (Object raw : array) {
			if (!(raw instanceof JSONObject item)) {
				throw new DtkAdminException(502, "DTK_ADMIN_SCHEMA", "DTK Admin watchlist 项不是对象");
			}
			String target = text(item, "target_id", "targetId", "sec_user_id", "secUserId");
			String platform = text(item, "platform");
			String kind = text(item, "kind");
			String id = text(item, "id", "entry_id", "entryId");
			if (target.isBlank() || id.isBlank() || platform.isBlank() || kind.isBlank()) {
				throw new DtkAdminException(502, "DTK_ADMIN_SCHEMA", "DTK Admin watchlist 项缺少 id/platform/kind/target_id");
			}
			entries.add(new WatchEntry(id, platform, kind, target, true));
		}
		return entries;
	}

	private WatchEntry parseSingleEntry(JSONObject response) {
		Object data = response == null ? null : response.get("data");
		if (data instanceof JSONObject object && looksLikeEntry(object)) {
			String id = text(object, "id", "entry_id", "entryId");
			if (id.isBlank()) return new WatchEntry("", text(object, "platform"), text(object, "kind"),
					text(object, "target_id", "targetId", "sec_user_id", "secUserId"), true);
			return parseEntry(object);
		}
		List<WatchEntry> entries = parseEntries(response);
		return entries.isEmpty() ? null : entries.get(0);
	}

	private WatchEntry parseEntry(JSONObject item) {
		String target = text(item, "target_id", "targetId", "sec_user_id", "secUserId");
		String platform = text(item, "platform");
		String kind = text(item, "kind");
		String id = text(item, "id", "entry_id", "entryId");
		if (target.isBlank() || id.isBlank() || platform.isBlank() || kind.isBlank()) {
			throw new DtkAdminException(502, "DTK_ADMIN_SCHEMA", "DTK Admin watchlist 项缺少 id/platform/kind/target_id");
		}
		return new WatchEntry(id, platform, kind, target, !Boolean.FALSE.equals(item.getBoolean("enabled")));
	}

	private boolean hasMore(JSONObject response, int itemCount, int pageSize, int offset) {
		JSONObject meta = response == null ? null : response.getJSONObject("meta");
		if (meta != null) {
			Boolean value = firstBoolean(meta, "has_more", "hasMore", "more");
			if (value != null) return value;
			if (meta.containsKey("next_offset")) return meta.getIntValue("next_offset") > offset;
			if (meta.containsKey("total")) return offset + itemCount < meta.getIntValue("total");
		}
		Object data = response == null ? null : response.get("data");
		if (data instanceof JSONObject object) {
			Boolean value = firstBoolean(object, "has_more", "hasMore", "more");
			if (value != null) return value;
			if (object.containsKey("next_offset")) return object.getIntValue("next_offset") > offset;
			if (object.containsKey("total")) return offset + itemCount < object.getIntValue("total");
		}
		return itemCount >= pageSize;
	}

	private static Boolean firstBoolean(JSONObject object, String... keys) {
		for (String key : keys) if (object.containsKey(key)) return object.getBoolean(key);
		return null;
	}

	private static boolean looksLikeEntry(JSONObject object) {
		return object != null && object.containsKey("platform") && object.containsKey("kind")
				&& (object.containsKey("target_id") || object.containsKey("targetId") || object.containsKey("sec_user_id"));
	}

	private static boolean retryable(int status) {
		return status == 429 || status == 502 || status == 503 || status == 504;
	}

	private static boolean retryable(int status, JSONObject error) {
		if (!retryable(status)) return false;
		if (error != null && error.containsKey("retryable")) return Boolean.TRUE.equals(error.getBoolean("retryable"));
		return true;
	}

	private static boolean safeToRetryPost(int status, JSONObject error) {
		return status == 429 || (error != null && Boolean.TRUE.equals(error.getBoolean("retryable")));
	}

	private static void pauseBeforeRetry(JSONObject error, int attempt) {
		long delay = 500L * (1L << Math.min(attempt - 1, 3));
		if (error != null && error.get("retry_after") != null) {
			try { delay = Math.min(10_000L, Math.max(delay, error.getLongValue("retry_after") * 1000L)); }
			catch (RuntimeException ignored) { }
		}
		try { TimeUnit.MILLISECONDS.sleep(delay); }
		catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
	}

	private int timeout(ConfigEntity config) {
		try { return Math.max(1000, Integer.parseInt(config.getDtkTimeoutMs())); }
		catch (RuntimeException e) { return 15000; }
	}

	private String normalizeBaseUrl(String value) {
		String base = value == null ? "" : value.trim();
		while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
		for (String suffix : List.of("/api/v1/admin", "/api/v1", "/api")) {
			if (base.endsWith(suffix)) return base.substring(0, base.length() - suffix.length());
		}
		return base;
	}

	private static String encode(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }
	private static String text(JSONObject object, String... keys) {
		if (object == null) return "";
		for (String key : keys) { String value = object.getString(key); if (value != null && !value.isBlank()) return value.trim(); }
		return "";
	}
	private static String preview(String value) {
		if (value == null) return "";
		String masked = value.replaceAll("(?i)(api[-_ ]?key|authorization)[^,; ]*", "$1=<redacted>");
		return masked.substring(0, Math.min(320, masked.length()));
	}

	public record WatchEntry(String id, String platform, String kind, String targetId, boolean enabled) {}

	public static class DtkAdminException extends RuntimeException {
		private final int status;
		private final String errorCode;
		private final String requestId;
		DtkAdminException(int status, String errorCode, String message) { this(status, errorCode, message, null, null); }
		DtkAdminException(int status, String errorCode, String message, String requestId) { this(status, errorCode, message, requestId, null); }
		DtkAdminException(int status, String errorCode, String message, Throwable cause) { this(status, errorCode, message, null, cause); }
		private DtkAdminException(int status, String errorCode, String message, String requestId, Throwable cause) { super(message, cause); this.status = status; this.errorCode = errorCode; this.requestId = requestId; }
		public int getStatus() { return status; }
		public String getErrorCode() { return errorCode; }
		public String getRequestId() { return requestId; }
	}
}
