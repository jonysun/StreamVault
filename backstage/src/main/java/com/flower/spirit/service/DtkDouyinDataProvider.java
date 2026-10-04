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
	private static final List<String> ITEM_ARRAY_KEYS = List.of("items", "aweme_list", "list", "posts", "works");

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
			JSONArray rawItems = findItemArray(response, 0);
			if (rawItems == null) {
				throw new CollectFetchException("DTK_UPSTREAM_SCHEMA",
						"DTK 作者列表缺少作品数组 " + schemaDiagnostics(response, null));
			}
			for (int i = 0; i < rawItems.size(); i++) {
				Object rawItem = rawItems.get(i);
				JSONObject item = rawItem instanceof JSONObject value ? value : null;
				JSONObject normalized = normalizeItem(item);
				String id = normalized.getString("aweme_id");
				if (blank(id)) {
					throw new CollectFetchException("DTK_UPSTREAM_SCHEMA",
							"DTK 作品项缺少 aweme_id " + schemaDiagnostics(response, item));
				}
				items.add(normalized);
				if (!known.contains(id)) newIds.add(id);
				if (request.maxItems() > 0 && newIds.size() >= request.maxItems()) break;
			}
			pages++;
			String next = firstText(data, "cursor", "max_cursor", "next_cursor", "nextCursor");
			if (blank(next)) next = text(response, "cursor");
			if (blank(next)) next = firstText(response, "max_cursor", "next_cursor", "nextCursor");
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
		String cover = mediaUrlValue(video == null ? null : video.get("cover"));
		if (blank(cover)) cover = mediaUrlValue(video == null ? null : video.get("origin_cover"));
		if (blank(cover)) cover = mediaUrlValue(detail.get("cover"));
		if (blank(cover)) cover = firstText(detail, "cover_url", "coverUrl");
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
		if (item == null) return new JSONObject(true);
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
		if (hasWorkId(object)) return normalizeDetail(object);
		for (String key : List.of("aweme", "aweme_info", "item", "work", "post")) {
			JSONObject nested = object.getJSONObject(key);
			if (nested != null && (hasWorkId(nested) || nested.getJSONObject("video") != null)) {
				return normalizeDetail(nested);
			}
		}
		return object.getJSONObject("video") != null
				|| object.getJSONArray("video_play_addr") != null ? normalizeDetail(coerceDetail(object)) : null;
	}

	private JSONObject coerceDetail(JSONObject source) {
		JSONObject detail = new JSONObject(true);
		detail.putAll(source);
		if (blank(detail.getString("aweme_id"))) detail.put("aweme_id",
				firstText(source, "id", "awemeId", "video_id", "videoId", "item_id", "itemId", "content_id", "contentId"));
		if (blank(detail.getString("desc"))) detail.put("desc", firstText(source, "title", "description"));
		if (blank(detail.getString("create_time"))) detail.put("create_time",
				firstText(source, "created_at", "createdAt", "publish_time", "publishTime"));
		if (detail.getJSONObject("author") == null && source.getJSONObject("user") != null) {
			detail.put("author", source.getJSONObject("user"));
		}
		JSONObject author = detail.getJSONObject("author");
		if (author != null) {
			String uid = firstText(author, "uid", "id", "user_id", "userId");
			String secUid = firstText(author, "sec_uid", "secUserId", "sec_user_id");
			String nickname = firstText(author, "nickname", "name", "username", "unique_id");
			String uniqueId = firstText(author, "unique_id", "uniqueId", "username");
			if (blank(detail.getString("uid"))) detail.put("uid", uid);
			if (blank(detail.getString("sec_uid"))) detail.put("sec_uid", secUid);
			if (blank(detail.getString("nickname"))) detail.put("nickname", nickname);
			if (blank(detail.getString("unique_id"))) detail.put("unique_id", uniqueId);
			if (blank(author.getString("uid"))) author.put("uid", uid);
			if (blank(author.getString("sec_uid"))) author.put("sec_uid", secUid);
			if (blank(author.getString("nickname"))) author.put("nickname", nickname);
			if (blank(author.getString("unique_id"))) author.put("unique_id", uniqueId);
		}
		if (detail.getJSONObject("video") == null && source.getJSONArray("video_play_addr") != null) {
			JSONObject video = new JSONObject(true);
			JSONObject play = new JSONObject(true);
			play.put("url_list", source.getJSONArray("video_play_addr"));
			video.put("play_addr", play);
			detail.put("video", video);
		}
		normalizeDtkMedia(detail, source);
		return detail;
	}

	private void normalizeDtkMedia(JSONObject detail, JSONObject source) {
		Object media = source.get("media");
		if (media == null) return;
		boolean sourceVideo = isVideoType(firstText(source, "kind", "type", "content_type", "contentType"));
		List<JSONObject> entries = new ArrayList<>();
		if (media instanceof JSONObject object) {
			JSONObject video = object.getJSONObject("video");
			if (video != null) {
				video.putIfAbsent("type", "video");
				entries.add(video);
			}
			JSONArray nestedImages = object.getJSONArray("images");
			if (nestedImages != null) {
				for (Object value : nestedImages) {
					if (value instanceof JSONObject image) entries.add(image);
					else if (value != null && !blank(String.valueOf(value))) {
						JSONObject image = new JSONObject(true);
						image.put("url", String.valueOf(value));
						entries.add(image);
					}
				}
			}
			if (video == null && nestedImages == null) entries.add(object);
		}
		if (media instanceof JSONArray array) {
			for (Object value : array) {
				if (value instanceof JSONObject object) entries.add(object);
				else if (value != null && !blank(String.valueOf(value))) {
					JSONObject image = new JSONObject(true);
					image.put("url", String.valueOf(value));
					entries.add(image);
				}
			}
		}
		if (entries.isEmpty()) return;
		JSONArray images = detail.getJSONArray("images");
		if (images == null) images = new JSONArray();
		for (JSONObject entry : entries) {
			String url = mediaUrl(entry);
			if (blank(url)) url = firstText(entry, "url", "download_url", "downloadUrl", "play_url", "playUrl",
					"src", "source_url", "sourceUrl", "no_watermark", "noWatermark", "no_watermark_url",
					"video_url", "videoUrl");
			String type = firstText(entry, "type", "kind", "media_type", "mediaType");
			if (blank(url)) url = mediaUrlFromEntry(entry);
			if (blank(url)) {
				JSONObject nested = entry.getJSONObject("video");
				url = firstText(nested, "url", "download_url", "play_url", "no_watermark", "noWatermark");
				if (blank(url)) url = mediaUrlFromEntry(nested);
			}
			if (blank(url)) continue;
			boolean video = sourceVideo || isVideoType(type) || entry.getJSONObject("video") != null
					|| entry.getJSONObject("play_addr") != null || entry.getJSONObject("download_addr") != null
					|| "mp4".equalsIgnoreCase(entry.getString("ext"))
					|| url.toLowerCase(java.util.Locale.ROOT).contains(".mp4");
			if (video && !hasPlayableUrl(detail.getJSONArray("video_play_addr"))) {
				JSONArray urls = new JSONArray();
				urls.add(url);
				detail.put("video_play_addr", urls);
				JSONObject videoObject = detail.getJSONObject("video");
				if (videoObject == null) videoObject = new JSONObject(true);
				JSONObject play = new JSONObject(true);
				play.put("url_list", urls);
				videoObject.put("play_addr", play);
				detail.put("video", videoObject);
			} else if (!video) {
				JSONObject image = new JSONObject(true);
				JSONArray urls = new JSONArray();
				urls.add(url);
				image.put("url_list", urls);
				images.add(image);
			}
		}
		if (!images.isEmpty()) detail.put("images", images);
	}

	private String mediaUrlFromEntry(JSONObject entry) {
		if (entry == null) return null;
		String direct = mediaUrl(entry);
		if (!blank(direct)) return direct;
		for (String key : List.of("play_addr", "download_addr", "download_url_list", "play_url_list")) {
			Object value = entry.get(key);
			if (value instanceof JSONObject object) {
				String nested = mediaUrl(object);
				if (!blank(nested)) return nested;
			} else if (value instanceof JSONArray array) {
				for (Object item : array) if (item != null && !blank(String.valueOf(item))) return String.valueOf(item);
			}
		}
		return null;
	}

	private boolean isVideoType(String type) {
		if (blank(type)) return false;
		String normalized = type.trim().toLowerCase(java.util.Locale.ROOT);
		return normalized.equals("video") || normalized.equals("mp4") || normalized.equals("movie")
				|| normalized.contains("video");
	}

	private JSONObject normalizeDetail(JSONObject source) {
		if (source == null) return null;
		JSONObject detail = coerceDetail(source);
		JSONObject video = detail.getJSONObject("video");
		if (!hasPlayableUrl(detail.getJSONArray("video_play_addr"))) {
			String play = mediaUrl(video == null ? null : video.getJSONObject("play_addr"));
			if (blank(play)) play = mediaUrl(video == null ? null : video.getJSONObject("download_addr"));
			if (blank(play)) play = mediaUrl(video);
			if (blank(play)) play = firstText(video, "url", "download_url", "downloadUrl", "play_url", "playUrl",
					"no_watermark", "noWatermark", "video_url", "videoUrl");
			if (blank(play)) play = firstText(detail, "video_url", "download_url", "play_url", "no_watermark",
					"noWatermark", "videoUrl");
			if (!blank(play)) {
				JSONArray urls = new JSONArray();
				urls.add(play);
				detail.put("video_play_addr", urls);
				if (video == null) video = new JSONObject(true);
				JSONObject playAddr = video.getJSONObject("play_addr");
				if (playAddr == null) playAddr = new JSONObject(true);
				playAddr.put("url_list", urls);
				video.put("play_addr", playAddr);
				detail.put("video", video);
			}
		}
		normalizeImages(detail);
		video = detail.getJSONObject("video");
		if (blank(mediaUrlValue(detail.get("cover")))) {
			String cover = mediaUrl(video == null ? null : video.getJSONObject("cover"));
			if (blank(cover)) cover = mediaUrl(video == null ? null : video.getJSONObject("origin_cover"));
			if (blank(cover)) cover = firstText(detail, "cover_url", "coverUrl", "thumbnail", "thumbnail_url");
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

	private boolean hasPlayableUrl(JSONArray urls) {
		if (urls == null || urls.isEmpty()) return false;
		for (Object value : urls) if (value != null && !blank(String.valueOf(value))) return true;
		return false;
	}

	private void normalizeImages(JSONObject detail) {
		JSONArray raw = detail.getJSONArray("images");
		if (raw == null || raw.isEmpty()) return;
		JSONArray normalized = new JSONArray();
		for (Object value : raw) {
			if (value instanceof JSONObject image) {
				JSONObject nestedVideo = image.getJSONObject("video");
				if (nestedVideo != null) {
					String play = mediaUrl(nestedVideo.getJSONObject("play_addr"));
					if (blank(play)) play = mediaUrl(nestedVideo.getJSONObject("download_addr"));
					if (blank(play)) play = mediaUrl(nestedVideo);
					if (blank(play)) play = firstText(nestedVideo, "url", "download_url", "downloadUrl",
							"play_url", "playUrl", "no_watermark", "noWatermark", "video_url", "videoUrl");
					JSONObject playAddr = nestedVideo.getJSONObject("play_addr");
					if (!blank(play) && blank(mediaUrl(playAddr))) {
						if (playAddr == null) playAddr = new JSONObject(true);
						JSONArray urls = new JSONArray();
						urls.add(play);
						playAddr.put("url_list", urls);
						nestedVideo.put("play_addr", playAddr);
					}
					normalized.add(image);
					continue;
				}
				String url = mediaUrl(image);
				if (blank(url)) url = firstText(image, "download_url", "downloadUrl", "src", "source_url", "sourceUrl");
				if (!blank(url)) {
					if (image.getJSONArray("url_list") == null) {
						JSONArray urls = new JSONArray();
						urls.add(url);
						image.put("url_list", urls);
					}
					normalized.add(image);
				}
			} else if (value != null && !blank(String.valueOf(value))) {
				JSONObject image = new JSONObject(true);
				JSONArray urls = new JSONArray();
				urls.add(String.valueOf(value));
				image.put("url_list", urls);
				normalized.add(image);
			}
		}
		detail.put("images", normalized);
	}

	private String firstText(JSONObject source, String... keys) {
		if (source == null) return null;
		for (String key : keys) {
			String value = source.getString(key);
			if (!blank(value)) return value;
		}
		return null;
	}

	private String mediaUrl(JSONObject media) {
		if (media == null) return null;
		JSONArray urls = media.getJSONArray("url_list");
		if (urls == null || urls.isEmpty()) urls = media.getJSONArray("urls");
		if (urls == null || urls.isEmpty()) {
			Object download = media.get("download_url_list");
			if (download instanceof JSONObject object) urls = object.getJSONArray("url_list");
			if (download instanceof JSONArray array) urls = array;
		}
		if (urls == null || urls.isEmpty()) {
			String direct = media.getString("url");
			return blank(direct) ? null : direct;
		}
		String fallback = null;
		for (int i = 0; i < urls.size(); i++) {
			String value = urls.getString(i);
			if (blank(value)) continue;
			if (fallback == null) fallback = value;
			if (isPreferredMediaUrl(value)) return value;
		}
		return fallback;
	}

	private String mediaUrlValue(Object value) {
		if (value instanceof JSONObject object) return mediaUrl(object);
		if (value instanceof JSONArray array) {
			for (int i = array.size() - 1; i >= 0; i--) {
				String candidate = mediaUrlValue(array.get(i));
				if (!blank(candidate)) return candidate;
			}
			return null;
		}
		if (value instanceof String text) return blank(text) ? null : text.trim();
		return value == null ? null : mediaUrlValue(String.valueOf(value));
	}

	private boolean isPreferredMediaUrl(String value) {
		try {
			URI uri = URI.create(value);
			if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) return false;
			String host = uri.getHost();
			String path = uri.getPath();
			return host != null && !("www.douyin.com".equalsIgnoreCase(host)
					&& path != null && path.startsWith("/aweme/v1/play/"));
		} catch (RuntimeException error) {
			return false;
		}
	}

	private JSONObject payload(JSONObject response) {
		if (response == null) return null;
		JSONObject data = response.getJSONObject("data");
		return data == null ? response : data;
	}

	private JSONArray findItemArray(Object value, int depth) {
		if (value == null || depth > 4) return null;
		if (value instanceof JSONObject object) {
			for (String key : ITEM_ARRAY_KEYS) {
				JSONArray candidate = object.getJSONArray(key);
				if (candidate != null) return candidate;
			}
			for (String key : List.of("data", "result", "response", "payload")) {
				JSONArray nested = findItemArray(object.get(key), depth + 1);
				if (nested != null) return nested;
			}
		}
		return null;
	}

	private boolean hasWorkId(JSONObject object) {
		return object != null && !blank(firstText(object, "aweme_id", "awemeId", "id", "video_id", "videoId",
				"item_id", "itemId", "content_id", "contentId"));
	}

	private String schemaDiagnostics(JSONObject response, JSONObject item) {
		JSONObject diagnostics = new JSONObject(true);
		diagnostics.put("topLevelKeys", response == null ? List.of() : response.keySet());
		Object data = response == null ? null : response.get("data");
		diagnostics.put("dataType", data == null ? "null" : data.getClass().getSimpleName());
		diagnostics.put("arrayCandidates", response == null ? List.of() : arrayCandidates(response, 0));
		diagnostics.put("itemKeys", item == null ? List.of() : item.keySet());
		if (response != null) {
			diagnostics.put("code", firstText(response, "code", "status_code"));
			diagnostics.put("message", firstText(response, "message", "status_msg"));
			diagnostics.put("success", response.get("success"));
		}
		return diagnostics.toJSONString();
	}

	private List<String> arrayCandidates(JSONObject object, int depth) {
		if (object == null || depth > 3) return List.of();
		Set<String> result = new LinkedHashSet<>();
		for (String key : ITEM_ARRAY_KEYS) if (object.getJSONArray(key) != null) result.add(key);
		for (String key : List.of("data", "result", "response", "payload")) {
			Object nested = object.get(key);
			if (nested instanceof JSONObject child) result.addAll(arrayCandidates(child, depth + 1));
		}
		return List.copyOf(result);
	}

	private String waitSeconds() {
		return String.valueOf(Math.max(1, Math.min(25, Global.dtkTimeoutMs / 1000)));
	}

	private String avatar(JSONObject author) {
		JSONObject avatar = author == null ? null : author.getJSONObject("avatar_thumb");
		return mediaUrl(avatar);
	}

	private JSONObject object(JSONObject o, String key) { return o == null ? null : o.getJSONObject(key); }
	private String text(JSONObject o, String key) { return o == null ? null : o.getString(key); }
	private boolean bool(JSONObject o, String key) {
		if (o == null) return false;
		Object value = o.get(key);
		if (value instanceof Boolean flag) return flag;
		if (value instanceof Number number) return number.intValue() != 0;
		return value != null && ("1".equals(value.toString()) || "true".equalsIgnoreCase(value.toString()));
	}
	private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
	private String trimSlash(String value) { return value == null ? "" : value.replaceAll("/+$", ""); }
}
