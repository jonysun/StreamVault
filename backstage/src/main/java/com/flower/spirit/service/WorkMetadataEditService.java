package com.flower.spirit.service;

import java.net.URI;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.dto.UpdateWorkMetadataRequest;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.WorkMetadataNormalizer;
import com.flower.spirit.platform.WorkMetadataValidationException;

@Service
public class WorkMetadataEditService {

	private static final Map<String, String> CANONICAL_KEYS = canonicalKeys();

	private final VideoDataDao videoDataDao;
	private final GraphicContentDao graphicContentDao;
	private final AuthorProfileService authorProfileService;
	private final WorkMetadataNormalizer normalizer;

	public WorkMetadataEditService(VideoDataDao videoDataDao, GraphicContentDao graphicContentDao,
			AuthorProfileService authorProfileService, WorkMetadataNormalizer normalizer) {
		this.videoDataDao = videoDataDao;
		this.graphicContentDao = graphicContentDao;
		this.authorProfileService = authorProfileService;
		this.normalizer = normalizer;
	}

	@Transactional
	public EditResult update(UpdateWorkMetadataRequest request, String editor) {
		validateRequest(request);
		Map<String, Object> overrides = request.getOverrides() == null ? Map.of() : request.getOverrides();
		validateEditableKeys(overrides);
		if ("video".equals(normalizeType(request.getWorkType()))) {
			return updateVideo(request, editor, overrides);
		}
		return updateGraphic(request, editor, overrides);
	}

	public void reapplyStoredOverrides(VideoDataEntity entity) {
		if (entity != null) applyVideo(entity, parseStoredOverrides(entity.getMetadataoverrides()));
	}

	public void reapplyStoredOverrides(GraphicContentEntity entity) {
		if (entity != null) applyGraphic(entity, parseStoredOverrides(entity.getMetadataoverrides()));
	}

	private EditResult updateVideo(UpdateWorkMetadataRequest request, String editor, Map<String, Object> overrides) {
		VideoDataEntity entity = videoDataDao.findById(request.getId())
				.orElseThrow(() -> new WorkMetadataValidationException("video work not found: " + request.getId()));
		applyVideo(entity, overrides);
		entity.setMetadataoverrides(mergeOverrides(entity.getMetadataoverrides(), overrides));
		entity.setMetadataeditedat(new Date());
		entity.setMetadataeditedby(trimToNull(editor));
		VideoDataEntity saved = videoDataDao.save(entity);
		boolean synced = syncAuthor(request, entity);
		return new EditResult("video", saved.getId(), synced, saved, null);
	}

	private EditResult updateGraphic(UpdateWorkMetadataRequest request, String editor, Map<String, Object> overrides) {
		GraphicContentEntity entity = graphicContentDao.findById(request.getId())
				.orElseThrow(() -> new WorkMetadataValidationException("graphic work not found: " + request.getId()));
		applyGraphic(entity, overrides);
		entity.setMetadataoverrides(mergeOverrides(entity.getMetadataoverrides(), overrides));
		entity.setMetadataeditedat(new Date());
		entity.setMetadataeditedby(trimToNull(editor));
		GraphicContentEntity saved = graphicContentDao.save(entity);
		boolean synced = syncAuthor(request, entity);
		return new EditResult("graphic", saved.getId(), synced, null, saved);
	}

	private void applyVideo(VideoDataEntity entity, Map<String, Object> overrides) {
		for (Map.Entry<String, Object> entry : canonicalEntries(overrides).entrySet()) {
			String value = normalizeValue(entry.getKey(), entry.getValue());
			switch (entry.getKey()) {
			case "title" -> entity.setVideoname(value);
			case "description" -> entity.setVideodesc(value);
			case "authorName" -> entity.setVideoauthor(value);
			case "authorAvatar" -> entity.setAuthoravatar(value);
			case "authorHomepage" -> entity.setAuthorhomepage(value);
			case "publishTime" -> entity.setPublishtime(value);
			case "sourceUrl" -> entity.setSourceurl(value);
			case "tags" -> entity.setVideotag(value);
			case "privacy" -> entity.setVideoprivacy(value);
			case "favorite" -> entity.setFavorite(value);
			default -> throw new IllegalStateException("unexpected editable field: " + entry.getKey());
			}
		}
	}

	private void applyGraphic(GraphicContentEntity entity, Map<String, Object> overrides) {
		for (Map.Entry<String, Object> entry : canonicalEntries(overrides).entrySet()) {
			String value = normalizeValue(entry.getKey(), entry.getValue());
			switch (entry.getKey()) {
			case "title" -> entity.setTitle(value);
			case "description" -> entity.setContent(value);
			case "authorName" -> entity.setAuthor(value);
			case "authorAvatar" -> entity.setAuthoravatar(value);
			case "authorHomepage" -> entity.setAuthorhomepage(value);
			case "publishTime" -> entity.setPublishtime(value);
			case "sourceUrl" -> entity.setSourceurl(value);
			case "tags" -> entity.setTags(value);
			case "privacy" -> entity.setPrivacy(value);
			case "favorite" -> entity.setFavorite(value);
			default -> throw new IllegalStateException("unexpected editable field: " + entry.getKey());
			}
		}
	}

	private String mergeOverrides(String raw, Map<String, Object> updates) {
		JSONObject merged = new JSONObject(new LinkedHashMap<>());
		merged.putAll(parseStoredOverrides(raw));
		for (Map.Entry<String, Object> entry : canonicalEntries(updates).entrySet()) {
			merged.put(entry.getKey(), normalizeValue(entry.getKey(), entry.getValue()));
		}
		return JSON.toJSONString(merged, SerializerFeature.WriteMapNullValue);
	}

	private Map<String, Object> parseStoredOverrides(String raw) {
		if (raw == null || raw.trim().isEmpty()) return Map.of();
		try {
			JSONObject existing = JSON.parseObject(raw);
			if (existing == null) throw new IllegalArgumentException("not an object");
			Map<String, Object> values = new LinkedHashMap<>(existing);
			validateEditableKeys(values);
			return values;
		} catch (RuntimeException e) {
			throw new WorkMetadataValidationException("stored metadata overrides are invalid", e);
		}
	}

	private Map<String, Object> canonicalEntries(Map<String, Object> overrides) {
		Map<String, Object> values = new LinkedHashMap<>();
		overrides.forEach((key, value) -> values.put(CANONICAL_KEYS.get(key), value));
		return values;
	}

	private String normalizeValue(String key, Object value) {
		if (value == null) return null;
		String text = String.valueOf(value);
		if ("publishTime".equals(key)) return normalizer.normalizePublishTime(text);
		if (("sourceUrl".equals(key) || "authorHomepage".equals(key)) && !isHttpUrl(text)) {
			throw new WorkMetadataValidationException(key + " must be an HTTP(S) URL");
		}
		return text;
	}

	private boolean syncAuthor(UpdateWorkMetadataRequest request, VideoDataEntity entity) {
		if (!request.isSyncAuthorProfile() || !hasText(entity.getPlatformkey()) || !hasText(entity.getAuthoruid())) return false;
		try {
			authorProfileService.upsertCanonicalAuthor(entity.getPlatformkey(), entity.getVideoplatform(), entity.getAuthoruid(),
					entity.getAuthorusername(), entity.getVideoauthor(), entity.getAuthoravatar(), entity.getAuthorhomepage());
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private boolean syncAuthor(UpdateWorkMetadataRequest request, GraphicContentEntity entity) {
		if (!request.isSyncAuthorProfile() || !hasText(entity.getPlatformkey()) || !hasText(entity.getAuthoruid())) return false;
		try {
			authorProfileService.upsertCanonicalAuthor(entity.getPlatformkey(), entity.getPlatform(), entity.getAuthoruid(),
					entity.getAuthorusername(), entity.getAuthor(), entity.getAuthoravatar(), entity.getAuthorhomepage());
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private void validateRequest(UpdateWorkMetadataRequest request) {
		if (request == null || request.getId() == null || request.getId() <= 0) {
			throw new WorkMetadataValidationException("positive work id is required");
		}
		String type = normalizeType(request.getWorkType());
		if (!"video".equals(type) && !"graphic".equals(type)) {
			throw new WorkMetadataValidationException("workType must be video or graphic");
		}
	}

	private void validateEditableKeys(Map<String, Object> overrides) {
		for (String key : overrides.keySet()) {
			if (!CANONICAL_KEYS.containsKey(key)) {
				throw new WorkMetadataValidationException("field is not editable: " + key);
			}
		}
	}

	private static boolean isHttpUrl(String value) {
		try {
			URI uri = URI.create(value.trim());
			return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}

	private static String trimToNull(String value) {
		return hasText(value) ? value.trim() : null;
	}

	private static String normalizeType(String value) {
		return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
	}

	private static Map<String, String> canonicalKeys() {
		Map<String, String> keys = new LinkedHashMap<>();
		keys.put("title", "title");
		keys.put("description", "description");
		keys.put("authorName", "authorName");
		keys.put("author", "authorName");
		keys.put("authorAvatar", "authorAvatar");
		keys.put("authorHomepage", "authorHomepage");
		keys.put("publishTime", "publishTime");
		keys.put("sourceUrl", "sourceUrl");
		keys.put("tags", "tags");
		keys.put("privacy", "privacy");
		keys.put("favorite", "favorite");
		return Map.copyOf(keys);
	}

	public record EditResult(String workType, Integer id, boolean profileSynced,
			VideoDataEntity video, GraphicContentEntity graphic) {
	}
}
