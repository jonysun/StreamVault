package com.flower.spirit.service;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.entity.AuthorProfileEntity;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.PlatformDefinition;

@Service
public class PlatformMetadataCompatibilityService {

	private static final List<String> IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp", ".gif");
	private static final List<String> VIDEO_EXTENSIONS = List.of(".mp4", ".webm", ".mov", ".m4v");

	private final AuthorProfileDao authorProfileDao;

	@Autowired
	public PlatformMetadataCompatibilityService(AuthorProfileDao authorProfileDao) {
		this.authorProfileDao = authorProfileDao;
	}

	public void enrichVideo(VideoDataEntity video) {
		enrichCanonicalVideo(video);
		if (video == null || hasText(video.getAuthorhomepage())) {
			return;
		}
		findAuthorProfile(video.getPlatformkey(), video.getVideoplatform(), video.getAuthoruid())
				.map(AuthorProfileEntity::getHomepage)
				.filter(PlatformMetadataCompatibilityService::hasText)
				.ifPresent(video::setAuthorhomepage);
	}

	public void enrichGraphic(GraphicContentEntity graphic) {
		enrichCanonicalGraphic(graphic);
		if (graphic == null || hasText(graphic.getAuthorhomepage())) {
			return;
		}
		findAuthorProfile(graphic.getPlatformkey(), graphic.getPlatform(), graphic.getAuthoruid())
				.map(AuthorProfileEntity::getHomepage)
				.filter(PlatformMetadataCompatibilityService::hasText)
				.ifPresent(graphic::setAuthorhomepage);
	}

	public Optional<AuthorProfileEntity> findAuthorProfile(String platformKey, String legacyPlatform, String authorUid) {
		if (authorProfileDao == null || !hasText(platformKey) || !hasText(authorUid)) {
			return Optional.empty();
		}
		Optional<AuthorProfileEntity> canonical = authorProfileDao.findByPlatformkeyAndAuthoruid(platformKey.trim(),
				authorUid.trim());
		if (canonical.isPresent()) {
			return canonical;
		}
		Optional<PlatformDefinition> definition = PlatformCatalog.findByAlias(platformKey);
		if (definition.isPresent()) {
			for (String alias : definition.get().getAliases()) {
				Optional<AuthorProfileEntity> legacy = authorProfileDao.findByPlatformAndAuthoruid(alias, authorUid.trim());
				if (legacy.isPresent()) {
					return legacy;
				}
			}
		}
		if (hasText(legacyPlatform)) {
			return authorProfileDao.findByPlatformAndAuthoruid(legacyPlatform.trim(), authorUid.trim());
		}
		return Optional.empty();
	}

	public static void enrichCanonicalVideo(VideoDataEntity video) {
		if (video == null) {
			return;
		}
		video.setPlatformkey(resolvePlatformKey(video.getPlatformkey(), video.getVideoplatform()));
		if (!hasText(video.getContenttype())) {
			video.setContenttype("video");
		}
	}

	public static void enrichCanonicalGraphic(GraphicContentEntity graphic) {
		if (graphic == null) {
			return;
		}
		graphic.setPlatformkey(resolvePlatformKey(graphic.getPlatformkey(), graphic.getPlatform()));
		if (!hasText(graphic.getContenttype())) {
			graphic.setContenttype(inferGraphicContentType(graphic.getImages()));
		}
	}

	public static String resolvePlatformKey(String explicitKey, String legacyPlatform) {
		if (hasText(explicitKey)) {
			return explicitKey.trim();
		}
		return PlatformCatalog.findByAlias(legacyPlatform).map(PlatformDefinition::getKey).orElse(null);
	}

	public static String resolveDisplayName(String platformKey, String legacyPlatform) {
		Optional<PlatformDefinition> definition = PlatformCatalog.findByAlias(platformKey);
		if (definition.isEmpty()) {
			definition = PlatformCatalog.findByAlias(legacyPlatform);
		}
		return definition.map(PlatformDefinition::getDisplayName)
				.orElseGet(() -> hasText(legacyPlatform) ? legacyPlatform.trim() : trimToNull(platformKey));
	}

	public static List<String> resolveFilterAliases(String requestedPlatform) {
		if (!hasText(requestedPlatform)) {
			return List.of();
		}
		LinkedHashSet<String> aliases = new LinkedHashSet<>();
		PlatformCatalog.findByAlias(requestedPlatform)
				.ifPresent(definition -> definition.getAliases().stream()
						.filter(PlatformMetadataCompatibilityService::hasText)
						.map(alias -> alias.trim().toLowerCase(Locale.ROOT))
						.forEach(aliases::add));
		if (aliases.isEmpty()) {
			aliases.add(requestedPlatform.trim().toLowerCase(Locale.ROOT));
		}
		return List.copyOf(aliases);
	}

	public static String inferGraphicContentType(String rawImages) {
		if (!hasText(rawImages)) {
			return "graphic";
		}
		try {
			List<String> resources = JSON.parseArray(rawImages, String.class);
			boolean hasImage = false;
			boolean hasVideo = false;
			if (resources != null) {
				for (String resource : resources) {
					String path = stripQuery(resource).toLowerCase(Locale.ROOT);
					hasImage |= endsWithAny(path, IMAGE_EXTENSIONS);
					hasVideo |= endsWithAny(path, VIDEO_EXTENSIONS);
				}
			}
			return hasVideo || (hasImage && hasVideo) ? "mixed" : "graphic";
		} catch (Exception e) {
			return "graphic";
		}
	}

	private static boolean endsWithAny(String value, List<String> extensions) {
		if (value == null) {
			return false;
		}
		return extensions.stream().anyMatch(value::endsWith);
	}

	private static String stripQuery(String value) {
		if (value == null) {
			return "";
		}
		int query = value.indexOf('?');
		int hash = value.indexOf('#');
		int end = value.length();
		if (query >= 0) end = Math.min(end, query);
		if (hash >= 0) end = Math.min(end, hash);
		return value.substring(0, end);
	}

	private static String trimToNull(String value) {
		return hasText(value) ? value.trim() : null;
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
}
