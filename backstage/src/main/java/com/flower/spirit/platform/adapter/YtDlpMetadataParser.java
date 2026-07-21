package com.flower.spirit.platform.adapter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.PlatformDefinition;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;

@Component
public class YtDlpMetadataParser {

	public WorkMetadata parseSingle(String output, String originalInput, String requestUrl) {
		return parseSingle(output, originalInput, requestUrl, false);
	}

	public WorkMetadata parseSingle(String output, String originalInput, String requestUrl,
			boolean allowMultiVideoPost) {
		List<JSONObject> objects = parseObjects(output);
		if (objects.size() != 1) {
			throw new WorkMetadataValidationException(
					"single-work parsing requires exactly one yt-dlp JSON object, received " + objects.size());
		}
		JSONObject object = objects.get(0);
		if (allowMultiVideoPost && isCollection(object)) {
			return parseMultiVideoPost(object, originalInput, requestUrl);
		}
		validateSingleVideo(object);
		String extractor = firstText(object.getString("extractor_key"), object.getString("extractor"));
		if (extractor == null) {
			throw new WorkMetadataValidationException("yt-dlp metadata has no extractor identity");
		}
		PlatformDefinition platform = PlatformCatalog.definitionForExtractor(extractor);
		String workId = text(object.get("id"));
		if (workId == null) {
			throw new WorkMetadataValidationException("yt-dlp metadata has no work id");
		}
		String sourceUrl = firstText(object.getString("webpage_url"), object.getString("original_url"), requestUrl);
		List<WorkMediaResource> resources = selectResources(object);
		if (resources.stream().noneMatch(resource -> resource.getType() == WorkMediaResource.Type.VIDEO)) {
			throw new WorkMetadataValidationException("yt-dlp metadata has no downloadable video stream");
		}
		return WorkMetadata.builder()
				.platform(platform)
				.workId(workId)
				.contentType(WorkContentType.VIDEO)
				.title(object.getString("title"))
				.description(object.getString("description"))
				.authorId(firstText(object.getString("uploader_id"), object.getString("channel_id")))
				.authorUsername(firstText(object.getString("uploader_id"), object.getString("channel")))
				.authorName(firstText(object.getString("uploader"), object.getString("channel")))
				.authorAvatar(firstText(object.getString("uploader_avatar"), object.getString("channel_avatar")))
				.authorHomepage(firstText(object.getString("uploader_url"), object.getString("channel_url")))
				.publishTime(publishTime(object))
				.sourceUrl(sourceUrl)
				.originalAddress(originalInput)
				.coverUrl(coverUrl(object))
				.mediaResources(resources)
				.rawMetadata(JSON.toJSONString(object))
				.build();
	}

	private WorkMetadata parseMultiVideoPost(JSONObject object, String originalInput, String requestUrl) {
		if (requestUrl == null || !requestUrl.toLowerCase(Locale.ROOT).contains("/status/")) {
			throw new WorkMetadataValidationException("Twitter timelines and collections are not single works");
		}
		String extractor = firstText(object.getString("extractor_key"), object.getString("extractor"));
		PlatformDefinition platform = PlatformCatalog.definitionForExtractor(extractor);
		if (!"twitter".equals(platform.getKey())) {
			throw new WorkMetadataValidationException("only Twitter multi-video posts are supported as single works");
		}
		JSONArray entries = object.getJSONArray("entries");
		if (entries == null || entries.isEmpty()) {
			throw new WorkMetadataValidationException("multi-video post has no entries");
		}
		List<WorkMediaResource> resources = new ArrayList<>();
		for (JSONObject entry : jsonObjects(entries)) {
			WorkMediaResource video = selectResources(entry).stream()
					.filter(resource -> resource.getType() == WorkMediaResource.Type.VIDEO)
					.findFirst().orElseThrow(() -> new WorkMetadataValidationException(
							"multi-video post entry has no downloadable video"));
			resources.add(new WorkMediaResource(resources.size(), WorkMediaResource.Type.VIDEO,
					video.getSourceUrl(), null, video.getExpectedExtension(), video.getRequestHeaders()));
		}
		String workId = text(object.get("id"));
		if (workId == null) throw new WorkMetadataValidationException("multi-video post has no work id");
		String sourceUrl = firstText(object.getString("webpage_url"), object.getString("original_url"), requestUrl);
		return WorkMetadata.builder()
				.platform(platform)
				.workId(workId)
				.contentType(com.flower.spirit.platform.WorkContentType.MIXED)
				.title(object.getString("title"))
				.description(object.getString("description"))
				.authorId(firstText(object.getString("uploader_id"), object.getString("channel_id")))
				.authorUsername(object.getString("uploader_id"))
				.authorName(firstText(object.getString("uploader"), object.getString("channel")))
				.authorHomepage(firstText(object.getString("uploader_url"), object.getString("channel_url")))
				.publishTime(publishTime(object))
				.sourceUrl(sourceUrl)
				.originalAddress(originalInput)
				.coverUrl(coverUrl(object))
				.mediaResources(resources)
				.rawMetadata(JSON.toJSONString(object))
				.build();
	}

	private List<JSONObject> parseObjects(String output) {
		if (output == null || output.trim().isEmpty()) {
			throw new WorkMetadataValidationException("yt-dlp returned no metadata");
		}
		List<JSONObject> objects = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new StringReader(output))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.trim().isEmpty()) continue;
				try {
					JSONObject object = JSON.parseObject(line);
					if (object == null) throw new IllegalArgumentException("JSON value is null");
					objects.add(object);
				} catch (RuntimeException e) {
					throw new WorkMetadataValidationException("yt-dlp returned invalid JSON metadata", e);
				}
			}
		} catch (IOException e) {
			throw new WorkMetadataValidationException("yt-dlp metadata could not be read", e);
		}
		return objects;
	}

	private void validateSingleVideo(JSONObject object) {
		if (isCollection(object)) {
			throw new WorkMetadataValidationException("playlists, channels and multi-video collections are not single works");
		}
		if (object.getBooleanValue("is_live") || "is_live".equalsIgnoreCase(object.getString("live_status"))) {
			throw new WorkMetadataValidationException("active live streams are not supported");
		}
	}

	private boolean isCollection(JSONObject object) {
		String type = object.getString("_type");
		return "playlist".equalsIgnoreCase(type) || "multi_video".equalsIgnoreCase(type)
				|| object.getJSONArray("entries") != null;
	}

	private List<WorkMediaResource> selectResources(JSONObject object) {
		JSONArray requested = object.getJSONArray("requested_formats");
		List<JSONObject> selected = requested == null ? List.of() : jsonObjects(requested);
		if (selected.isEmpty()) {
			JSONArray downloads = object.getJSONArray("requested_downloads");
			selected = downloads == null ? List.of() : jsonObjects(downloads);
		}
		List<WorkMediaResource> resources = resourcesFromSelected(selected, object);
		if (!resources.isEmpty()) return resources;

		JSONArray formatsArray = object.getJSONArray("formats");
		List<JSONObject> formats = formatsArray == null ? List.of() : jsonObjects(formatsArray);
		JSONObject progressive = formats.stream().filter(this::hasVideo).filter(this::hasAudio)
				.max(Comparator.comparingLong(this::qualityScore)).orElse(null);
		if (progressive != null) return List.of(resource(0, WorkMediaResource.Type.VIDEO, progressive, object));

		JSONObject video = formats.stream().filter(this::hasVideo)
				.max(Comparator.comparingLong(this::qualityScore)).orElse(null);
		JSONObject audio = formats.stream().filter(format -> !hasVideo(format) && hasAudio(format))
				.max(Comparator.comparingLong(this::qualityScore)).orElse(null);
		if (video != null) {
			List<WorkMediaResource> result = new ArrayList<>();
			result.add(resource(0, WorkMediaResource.Type.VIDEO, video, object));
			if (!hasAudio(video) && audio != null) {
				result.add(resource(1, WorkMediaResource.Type.AUDIO, audio, object));
			}
			return List.copyOf(result);
		}
		if (hasVideo(object) && hasText(object.getString("url"))) {
			return List.of(resource(0, WorkMediaResource.Type.VIDEO, object, object));
		}
		return List.of();
	}

	private List<WorkMediaResource> resourcesFromSelected(List<JSONObject> selected, JSONObject root) {
		JSONObject progressive = selected.stream().filter(this::hasVideo).filter(this::hasAudio)
				.max(Comparator.comparingLong(this::qualityScore)).orElse(null);
		if (progressive != null) return List.of(resource(0, WorkMediaResource.Type.VIDEO, progressive, root));
		JSONObject video = selected.stream().filter(this::hasVideo)
				.max(Comparator.comparingLong(this::qualityScore)).orElse(null);
		if (video == null) return List.of();
		List<WorkMediaResource> result = new ArrayList<>();
		result.add(resource(0, WorkMediaResource.Type.VIDEO, video, root));
		JSONObject audio = selected.stream().filter(format -> !hasVideo(format) && hasAudio(format))
				.max(Comparator.comparingLong(this::qualityScore)).orElse(null);
		if (!hasAudio(video) && audio != null) {
			result.add(resource(1, WorkMediaResource.Type.AUDIO, audio, root));
		}
		return List.copyOf(result);
	}

	private WorkMediaResource resource(int order, WorkMediaResource.Type type, JSONObject format, JSONObject root) {
		String url = format.getString("url");
		if (!hasText(url)) throw new WorkMetadataValidationException("selected yt-dlp format has no URL");
		Map<String, String> headers = new LinkedHashMap<>();
		copyHeaders(headers, root.getJSONObject("http_headers"));
		copyHeaders(headers, format.getJSONObject("http_headers"));
		return new WorkMediaResource(order, type, url, null, format.getString("ext"), headers);
	}

	private void copyHeaders(Map<String, String> target, JSONObject source) {
		if (source == null) return;
		source.forEach((key, value) -> {
			if (key == null || value == null) return;
			String normalized = key.trim().toLowerCase(Locale.ROOT);
			if (!"cookie".equals(normalized) && !"authorization".equals(normalized)) {
				target.put(key, String.valueOf(value));
			}
		});
	}

	private boolean hasVideo(JSONObject format) {
		String codec = format.getString("vcodec");
		if (hasText(codec)) return !"none".equalsIgnoreCase(codec);
		String ext = format.getString("ext");
		return ext != null && List.of("mp4", "webm", "mkv", "mov", "flv").contains(ext.toLowerCase(Locale.ROOT));
	}

	private boolean hasAudio(JSONObject format) {
		String codec = format.getString("acodec");
		return hasText(codec) && !"none".equalsIgnoreCase(codec);
	}

	private long qualityScore(JSONObject format) {
		return number(format, "height") * 1_000_000L + number(format, "width") * 1_000L
				+ number(format, "tbr") + number(format, "filesize") / 1_000_000L;
	}

	private long number(JSONObject object, String key) {
		Number value = object.getObject(key, Number.class);
		return value == null ? 0 : value.longValue();
	}

	private List<JSONObject> jsonObjects(JSONArray array) {
		List<JSONObject> values = new ArrayList<>();
		for (Object value : array) {
			if (value instanceof JSONObject object) values.add(object);
			else if (value instanceof Map<?, ?> map) {
				JSONObject object = new JSONObject();
				map.forEach((key, item) -> {
					if (key instanceof String textKey) object.put(textKey, item);
				});
				values.add(object);
			}
		}
		return values;
	}

	private String publishTime(JSONObject object) {
		String timestamp = text(object.get("timestamp"));
		return firstText(timestamp, text(object.get("release_timestamp")), object.getString("upload_date"));
	}

	private String coverUrl(JSONObject object) {
		String direct = object.getString("thumbnail");
		if (hasText(direct)) return direct;
		JSONArray thumbnails = object.getJSONArray("thumbnails");
		if (thumbnails == null) return null;
		for (int i = thumbnails.size() - 1; i >= 0; i--) {
			JSONObject thumbnail = thumbnails.getJSONObject(i);
			if (thumbnail != null && hasText(thumbnail.getString("url"))) return thumbnail.getString("url");
		}
		return null;
	}

	private static String text(Object value) {
		return value == null ? null : firstText(String.valueOf(value));
	}

	private static String firstText(String... values) {
		if (values == null) return null;
		for (String value : values) {
			if (hasText(value)) return value.trim();
		}
		return null;
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
}
