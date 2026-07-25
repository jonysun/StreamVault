package com.flower.spirit.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

@Service
public class SnapshotCodec {

	public static final int DEFAULT_MAX_BYTES = 1024 * 1024;
	public static final int DEFAULT_FORMAT_VERSION = 2;

	private final int maxBytes;
	private final int formatVersion;

	public SnapshotCodec(@Value("${streamvault.collect.snapshot.max-bytes:1048576}") int maxBytes,
			@Value("${streamvault.collect.snapshot.format-version:2}") int formatVersion) {
		this.maxBytes = Math.max(1024, maxBytes);
		this.formatVersion = Math.max(2, formatVersion);
	}

	public String encodeFetch(JSONArray source, Map<String, Object> context) {
		List<SnapshotItem> items = new ArrayList<>();
		int videos = 0;
		int graphics = 0;
		if (source != null) {
			for (int i = 0; i < source.size(); i++) {
				JSONObject item = source.getJSONObject(i);
				if (item == null) continue;
				boolean video = hasVideo(item);
				if (video) videos++; else graphics++;
				items.add(new SnapshotItem(i + 1, item.getString("aweme_id"),
						firstNotBlank(item.getString("sec_uid"), item.getString("author_uid"), item.getString("uid")),
						item.getString("nickname"), item.getString("desc"),
						firstNotBlank(item.getString("publish_time"), item.getString("create_time")),
						video ? "video" : "graphic", null));
			}
		}
		return encode(items, videos, graphics, context);
	}

	public String encodePlan(JSONArray source, Map<String, Object> context) {
		List<SnapshotItem> items = new ArrayList<>();
		int videos = 0;
		int graphics = 0;
		if (source != null) {
			for (int i = 0; i < source.size(); i++) {
				JSONObject item = source.getJSONObject(i);
				if (item == null || "run-summary".equals(item.getString("stage"))) continue;
				String mediaType = normalizeMediaType(item.getString("mediatype"));
				if ("video".equals(mediaType)) videos++;
				if ("graphic".equals(mediaType)) graphics++;
				items.add(new SnapshotItem(items.size() + 1, item.getString("aweme_id"),
						firstNotBlank(item.getString("sec_uid"), item.getString("author_uid")),
						item.getString("nickname"), item.getString("desc"),
						firstNotBlank(item.getString("publish_time"), item.getString("create_time")),
						mediaType, item.getString("decision")));
			}
		}
		return encode(items, videos, graphics, context);
	}

	public SnapshotReadResult read(String raw) {
		if (raw == null || raw.isBlank()) return SnapshotReadResult.empty();
		try {
			Object parsed = JSON.parse(raw);
			if (parsed instanceof JSONObject object && object.getJSONArray("items") != null) {
				return readEnvelope(object);
			}
			if (parsed instanceof JSONArray array) {
				return readLegacy(array);
			}
			return SnapshotReadResult.unavailable("UNSUPPORTED_SNAPSHOT_FORMAT", "无法识别的旧快照格式");
		} catch (RuntimeException error) {
			return SnapshotReadResult.unavailable("LEGACY_TRUNCATED_JSON", "旧快照已被截断，请查看对应运行明细");
		}
	}

	private String encode(List<SnapshotItem> all, int videoTotal, int graphicTotal, Map<String, Object> context) {
		List<SnapshotItem> kept = new ArrayList<>();
		for (SnapshotItem item : all) {
			kept.add(item);
			SnapshotEnvelope candidate = envelope(kept, all.size(), videoTotal, graphicTotal, context);
			if (utf8Bytes(toJson(candidate)) > maxBytes) {
				kept.remove(kept.size() - 1);
				break;
			}
		}
		SnapshotEnvelope result = envelope(kept, all.size(), videoTotal, graphicTotal, context);
		String json = toJson(result);
		while (utf8Bytes(json) > maxBytes && !kept.isEmpty()) {
			kept.remove(kept.size() - 1);
			result = envelope(kept, all.size(), videoTotal, graphicTotal, context);
			json = toJson(result);
		}
		if (utf8Bytes(json) > maxBytes) {
			result = envelope(List.of(), all.size(), videoTotal, graphicTotal, Map.of());
			json = toJson(result);
		}
		return json;
	}

	private SnapshotEnvelope envelope(List<SnapshotItem> kept, int total, int videos, int graphics,
			Map<String, Object> context) {
		Map<String, Object> safeContext = new LinkedHashMap<>();
		if (context != null) context.forEach((key, value) -> {
			if (key != null && value != null) safeContext.put(key, value);
		});
		return new SnapshotEnvelope(formatVersion, List.copyOf(kept), total, kept.size(), kept.size() < total,
				videos, graphics, Map.copyOf(safeContext));
	}

	private String toJson(SnapshotEnvelope envelope) {
		JSONObject object = new JSONObject(true);
		object.put("version", envelope.version());
		JSONArray items = new JSONArray();
		for (SnapshotItem item : envelope.items()) items.add(toJson(item));
		object.put("items", items);
		object.put("totalCount", envelope.totalCount());
		object.put("storedCount", envelope.storedCount());
		object.put("truncated", envelope.truncated());
		object.put("videoTotal", envelope.videoTotal());
		object.put("graphicTotal", envelope.graphicTotal());
		object.put("context", envelope.context());
		return object.toJSONString();
	}

	private JSONObject toJson(SnapshotItem item) {
		JSONObject object = new JSONObject(true);
		object.put("ordinal", item.ordinal());
		object.put("workId", item.workId());
		object.put("authorUid", item.authorUid());
		object.put("nickname", item.nickname());
		object.put("title", item.title());
		object.put("publishTime", item.publishTime());
		object.put("mediaType", item.mediaType());
		object.put("decision", item.decision());
		return object;
	}

	private SnapshotReadResult readEnvelope(JSONObject object) {
		JSONArray array = object.getJSONArray("items");
		List<SnapshotItem> items = new ArrayList<>();
		for (int i = 0; i < array.size(); i++) items.add(readItem(array.getJSONObject(i), i + 1));
		JSONObject contextObject = object.getJSONObject("context");
		Map<String, Object> context = contextObject == null ? Map.of() : new LinkedHashMap<>(contextObject);
		return new SnapshotReadResult(true, object.getIntValue("version"), List.copyOf(items),
				object.getIntValue("totalCount"), object.getIntValue("storedCount"),
				object.getBooleanValue("truncated"), object.getIntValue("videoTotal"),
				object.getIntValue("graphicTotal"), Map.copyOf(context), null, null);
	}

	private SnapshotReadResult readLegacy(JSONArray array) {
		List<SnapshotItem> items = new ArrayList<>();
		int videoTotal = 0;
		int graphicTotal = 0;
		int totalCount = 0;
		boolean truncated = false;
		for (int i = 0; i < array.size(); i++) {
			JSONObject object = array.getJSONObject(i);
			if (object == null) continue;
			if (object.getBooleanValue("snapshot_truncated")) {
				truncated = true;
				totalCount = Math.max(totalCount, object.getIntValue("total_count"));
				videoTotal = Math.max(videoTotal, object.getIntValue("video_total"));
				graphicTotal = Math.max(graphicTotal, object.getIntValue("image_total"));
				continue;
			}
			SnapshotItem item = readItem(object, items.size() + 1);
			items.add(item);
			if ("video".equals(item.mediaType())) videoTotal++;
			if ("graphic".equals(item.mediaType())) graphicTotal++;
		}
		if (totalCount == 0) totalCount = items.size();
		return new SnapshotReadResult(true, 1, List.copyOf(items), totalCount, items.size(), truncated,
				videoTotal, graphicTotal, Map.of(), null, null);
	}

	private SnapshotItem readItem(JSONObject item, int fallbackOrdinal) {
		boolean legacyMediaPresent = item.containsKey("has_video_play_addr");
		boolean legacyVideo = legacyMediaPresent && item.getBooleanValue("has_video_play_addr");
		String mediaType = normalizeMediaType(firstNotBlank(item.getString("mediaType"),
				item.getString("mediatype"), legacyMediaPresent ? (legacyVideo ? "video" : "graphic") : null));
		return new SnapshotItem(firstPositive(item.getIntValue("ordinal"), item.getIntValue("index"), fallbackOrdinal),
				firstNotBlank(item.getString("workId"), item.getString("aweme_id")),
				firstNotBlank(item.getString("authorUid"), item.getString("sec_uid"), item.getString("uid")),
				item.getString("nickname"), firstNotBlank(item.getString("title"), item.getString("desc")),
				firstNotBlank(item.getString("publishTime"), item.getString("publish_time"), item.getString("create_time")),
				mediaType, item.getString("decision"));
	}

	private boolean hasVideo(JSONObject item) {
		JSONArray playAddress = item.getJSONArray("video_play_addr");
		return playAddress != null && !playAddress.isEmpty();
	}

	private String normalizeMediaType(String mediaType) {
		if (mediaType == null) return "unknown";
		String value = mediaType.trim().toLowerCase();
		if ("image".equals(value) || "graphic".equals(value)) return "graphic";
		if ("video".equals(value)) return "video";
		return value.isEmpty() ? "unknown" : value;
	}

	private int utf8Bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

	private int firstPositive(int... values) {
		for (int value : values) if (value > 0) return value;
		return 0;
	}

	private String firstNotBlank(String... values) {
		for (String value : values) if (value != null && !value.isBlank()) return value;
		return null;
	}
}
