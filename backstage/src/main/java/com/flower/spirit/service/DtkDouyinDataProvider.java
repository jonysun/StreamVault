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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.config.Global;

/** HTTP adapter for Douyin_TikTok_Download_API. DTK owns all upstream identity/signing concerns. */
@Service
public class DtkDouyinDataProvider implements DouyinDataProvider {
	private static final Logger logger = LoggerFactory.getLogger(DtkDouyinDataProvider.class);
	private final HttpClient client;

	public DtkDouyinDataProvider() {
		this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
	}

	DtkDouyinDataProvider(HttpClient client) {
		this.client = java.util.Objects.requireNonNull(client, "client");
	}

	@Override
	public String name() { return "DTK"; }

	@Override
	public DouyinFetchEnvelope fetchAuthorWorks(DouyinFetchRequest request) {
		if (blank(Global.dtkBaseUrl)) throw new CollectFetchException("DTK_NOT_CONFIGURED", "DTK Base URL 未配置");
		if (blank(request.secUserId())) throw new CollectFetchException("DTK_INVALID_SOURCE", "DTK 需要 sec_user_id");
		if (request.mode() != DouyinFetchMode.INITIAL && request.mode() != DouyinFetchMode.INCREMENTAL
				&& request.mode() != DouyinFetchMode.AUDIT) {
			throw new CollectFetchException("DTK_UNSUPPORTED_SOURCE", "DTK 不支持该收藏来源");
		}
		Set<String> known = request.knownWorkIds() == null ? Set.of() : request.knownWorkIds();
		List<JSONObject> items = new ArrayList<>();
		Set<String> newIds = new LinkedHashSet<>();
		String cursor = blank(request.backfillCursor()) ? "0" : request.backfillCursor();
		int pages = 0;
		boolean hasMore = true;
		while (hasMore && pages < request.maxPages() && (request.maxItems() <= 0 || newIds.size() < request.maxItems())) {
			JSONObject response = get("/api/v1/douyin/user/posts", "sec_user_id", request.secUserId(),
					"cursor", cursor, "count", "50", "wait", waitSeconds());
			JSONObject data = payload(response);
			JSONArray rawItems = array(data, "items");
			if (rawItems == null) rawItems = array(response, "items");
			if (rawItems == null) throw new CollectFetchException("DTK_UPSTREAM_SCHEMA", "DTK 作者列表缺少 data.items");
			for (int i = 0; i < rawItems.size(); i++) {
				JSONObject normalized = normalizeItem(rawItems.getJSONObject(i));
				String id = normalized.getString("aweme_id");
				if (blank(id)) throw new CollectFetchException("DTK_UPSTREAM_SCHEMA", "DTK 作品项缺少 aweme_id");
				items.add(normalized);
				if (!known.contains(id)) newIds.add(id);
				if (request.maxItems() > 0 && newIds.size() >= request.maxItems()) break;
			}
			pages++;
			String next = text(data, "cursor");
			if (blank(next)) next = text(response, "cursor");
			JSONObject meta = object(response, "meta");
			JSONObject metaCursor = object(meta, "cursor");
			if (blank(next)) next = text(metaCursor, "next");
			if (blank(next) && metaCursor == null) next = text(meta, "cursor");
			hasMore = bool(data, "has_more") || bool(response, "has_more")
					|| bool(meta, "has_more") || bool(metaCursor, "has_more");
			if (blank(next) || next.equals(cursor)) hasMore = false;
			cursor = blank(next) ? cursor : next;
		}
		JSONObject diagnostics = new JSONObject(true);
		diagnostics.put("provider", name());
		diagnostics.put("observedCount", items.size());
		diagnostics.put("pagesFetched", pages);
		return new DouyinFetchEnvelope(List.copyOf(items), Collections.unmodifiableSet(newIds),
				items.isEmpty() ? "NO_PUBLIC_WORKS" : hasMore ? "BATCH_LIMIT" : "NO_MORE", pages, 0,
				cursor, cursor, !hasMore, false, 0, diagnostics);
	}

	@Override
	public String fetchWorkData(String workId) {
		JSONObject response = get("/api/v1/douyin/video", "aweme_id", workId, "wait", waitSeconds());
		JSONObject detail = findDetail(response);
		if (detail == null) throw new CollectFetchException("DTK_UPSTREAM_SCHEMA", "DTK 作品详情缺少 aweme_detail");
		JSONObject root = new JSONObject(true);
		root.put("aweme_detail", detail);
		return root.toJSONString();
	}

	@Override
	public java.util.Map<String, String> fetchDirect(String url) {
		JSONObject response = get("/api/v1/douyin/video", "url", url, "wait", waitSeconds());
		JSONObject detail = findDetail(response);
		if (detail == null) throw new CollectFetchException("DTK_UPSTREAM_SCHEMA", "DTK 直链详情缺少 aweme_detail");
		JSONObject author = detail.getJSONObject("author");
		JSONObject video = detail.getJSONObject("video");
		String play = mediaUrl(video == null ? null : video.getJSONObject("play_addr"));
		if (blank(play)) play = firstText(detail, "video_url", "download_url", "play_url");
		String cover = mediaUrl(video == null ? null : video.getJSONObject("cover"));
		if (blank(cover)) cover = firstText(detail, "cover_url", "cover");
		if (blank(play)) throw new CollectFetchException("DTK_UPSTREAM_SCHEMA", "DTK 直链详情缺少视频地址");
		HashMap<String, String> result = new HashMap<>();
		result.put("awemeid", detail.getString("aweme_id"));
		result.put("videoplay", play);
		result.put("cover", cover);
		result.put("desc", detail.getString("desc"));
		result.put("create_time", detail.getString("create_time"));
		if (author != null) {
			result.put("nickname", author.getString("nickname"));
			result.put("uid", author.getString("uid"));
			result.put("sec_uid", author.getString("sec_uid"));
			result.put("unique_id", author.getString("unique_id"));
			result.put("avatar_thumb", avatar(author));
		}
		result.put("type", "api");
		result.put("jsonData", detail.toJSONString());
		return result;
	}

	@Override
	public JSONObject fetchAuthorProfile(String secUid) {
		JSONObject response = get("/api/v1/douyin/user", "sec_user_id", secUid, "wait", waitSeconds());
		JSONObject data = payload(response);
		if (data == null) return response.getJSONObject("user");
		JSONObject user = data.getJSONObject("user");
		return user == null ? data : user;
	}

	private JSONObject get(String path, String... params) {
		try {
			StringBuilder url = new StringBuilder(trimSlash(Global.dtkBaseUrl)).append(path);
			for (int i = 0; i + 1 < params.length; i += 2) {
				url.append(i == 0 ? '?' : '&').append(URLEncoder.encode(params[i], StandardCharsets.UTF_8))
						.append('=').append(URLEncoder.encode(params[i + 1] == null ? "" : params[i + 1], StandardCharsets.UTF_8));
			}
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url.toString()))
					.timeout(Duration.ofMillis(Math.max(1000, Global.dtkTimeoutMs)))
					.header("Accept", "application/json");
			if (!blank(Global.dtkApiKey)) builder.header("X-API-Key", Global.dtkApiKey);
			HttpResponse<String> response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
			logger.info("[DTK] endpoint={} status={}", path, response.statusCode());
			if (response.statusCode() == 401 || response.statusCode() == 403)
				throw new CollectFetchException("DTK_AUTH_FAILED", "DTK API 鉴权失败");
			if (response.statusCode() == 429)
				throw new CollectFetchException("DTK_RATE_LIMITED", "DTK API 被限流 Retry-After=" + response.headers().firstValue("Retry-After").orElse("unknown"));
			if (response.statusCode() < 200 || response.statusCode() >= 300)
				throw new CollectFetchException("DTK_UPSTREAM_HTTP", "DTK API HTTP status=" + response.statusCode());
			JSONObject parsed = JSON.parseObject(response.body());
			if (parsed == null) throw new CollectFetchException("DTK_UPSTREAM_SCHEMA", "DTK 返回空 JSON");
			Boolean success = parsed.getBoolean("success");
			JSONObject error = parsed.getJSONObject("error");
			String errorCode = error == null ? parsed.getString("code") : error.getString("code");
			if (Boolean.FALSE.equals(success) || (errorCode != null && !errorCode.isBlank()
					&& !"000001".equals(errorCode) && !"0".equals(errorCode))) {
				String message = error == null ? parsed.getString("message") : error.getString("message");
				throw new CollectFetchException("DTK_" + (errorCode == null ? "UPSTREAM_ERROR" : errorCode.toUpperCase()), message);
			}
			return parsed;
		} catch (CollectFetchException e) {
			throw e;
		} catch (Exception e) {
			throw new CollectFetchException("DTK_UNAVAILABLE", "DTK API 请求失败: " + e.getClass().getSimpleName(), e);
		}
	}

	private JSONObject normalizeItem(JSONObject item) {
		JSONObject detail = findDetail(item);
		if (detail == null) detail = item;
		detail = normalizeDetail(detail);
		JSONObject root = new JSONObject(true);
		root.putAll(detail);
		JSONObject snapshot = new JSONObject(true);
		snapshot.put("aweme_detail", detail);
		root.put("download_snapshot", snapshot);
		return root;
	}

	private JSONObject findDetail(JSONObject object) {
		if (object == null) return null;
		object = payload(object);
		if (object == null) return null;
		JSONObject detail = object.getJSONObject("aweme_detail");
		if (detail != null) return normalizeDetail(detail);
		JSONObject data = object.getJSONObject("data");
		if (data != null) {
			detail = data.getJSONObject("aweme_detail");
			if (detail != null) return normalizeDetail(detail);
			if (data.getString("aweme_id") != null) return normalizeDetail(data);
			if (data.getString("id") != null || data.getJSONObject("video") != null
					|| data.getJSONArray("video_play_addr") != null) {
				return coerceDetail(data);
			}
		}
		if (object.getString("aweme_id") != null) return normalizeDetail(object);
		return object.getString("id") != null || object.getJSONObject("video") != null
				|| object.getJSONArray("video_play_addr") != null ? normalizeDetail(coerceDetail(object)) : null;
	}

	private JSONObject coerceDetail(JSONObject source) {
		JSONObject detail = new JSONObject(true);
		detail.putAll(source);
		if (detail.getString("aweme_id") == null) detail.put("aweme_id", firstText(source, "id", "awemeId"));
		if (detail.getString("desc") == null) detail.put("desc", firstText(source, "title", "description"));
		if (detail.getJSONObject("author") == null && source.getJSONObject("user") != null) {
			detail.put("author", source.getJSONObject("user"));
		}
		if (detail.getJSONObject("video") == null && source.getJSONArray("video_play_addr") != null) {
			JSONObject video = new JSONObject(true);
			JSONObject play = new JSONObject(true);
			play.put("url_list", source.getJSONArray("video_play_addr"));
			video.put("play_addr", play);
			detail.put("video", video);
		}
		return detail;
	}

	private JSONObject normalizeDetail(JSONObject source) {
		if (source == null) return null;
		JSONObject detail = coerceDetail(source);
		JSONObject video = detail.getJSONObject("video");
		if (detail.getJSONArray("video_play_addr") == null) {
			String play = mediaUrl(video == null ? null : video.getJSONObject("play_addr"));
			if (blank(play)) play = firstText(detail, "video_url", "download_url", "play_url");
			if (!blank(play)) {
				JSONArray urls = new JSONArray();
				urls.add(play);
				detail.put("video_play_addr", urls);
			}
		}
		if (detail.getJSONArray("cover") == null) {
			String cover = mediaUrl(video == null ? null : video.getJSONObject("cover"));
			if (blank(cover)) cover = firstText(detail, "cover_url", "cover");
			if (!blank(cover)) {
				JSONArray urls = new JSONArray();
				urls.add(cover);
				detail.put("cover", urls);
			}
		}
		JSONObject author = detail.getJSONObject("author");
		if (author != null) {
			if (blank(detail.getString("nickname"))) detail.put("nickname", firstText(author, "nickname", "name"));
			if (blank(detail.getString("uid"))) detail.put("uid", firstText(author, "uid", "user_id"));
			if (blank(detail.getString("sec_uid"))) detail.put("sec_uid", firstText(author, "sec_uid", "sec_user_id"));
		}
		return detail;
	}

	private String firstText(JSONObject source, String... keys) {
		for (String key : keys) {
			String value = source.getString(key);
			if (!blank(value)) return value;
		}
		return null;
	}

	private String mediaUrl(JSONObject media) {
		if (media == null) return null;
		JSONArray urls = media.getJSONArray("url_list");
		if (urls == null || urls.isEmpty()) return media.getString("url");
		return urls.getString(urls.size() - 1);
	}

	private JSONObject payload(JSONObject response) {
		if (response == null) return null;
		JSONObject data = response.getJSONObject("data");
		return data == null ? response : data;
	}

	private String waitSeconds() {
		return String.valueOf(Math.max(1, Math.min(25, Global.dtkTimeoutMs / 1000)));
	}

	private String avatar(JSONObject author) {
		JSONObject avatar = author == null ? null : author.getJSONObject("avatar_thumb");
		return mediaUrl(avatar);
	}

	private JSONObject object(JSONObject o, String key) { return o == null ? null : o.getJSONObject(key); }
	private JSONArray array(JSONObject o, String key) { return o == null ? null : o.getJSONArray(key); }
	private String text(JSONObject o, String key) { return o == null ? null : o.getString(key); }
	private boolean bool(JSONObject o, String key) { return o != null && Boolean.TRUE.equals(o.getBoolean(key)); }
	private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
	private String trimSlash(String value) { return value == null ? "" : value.replaceAll("/+$", ""); }
}
