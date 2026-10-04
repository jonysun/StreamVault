package com.flower.spirit.platform;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class WorkMetadataNormalizer {
	private static final Pattern DOUYIN_PATH_ID = Pattern.compile("/(?:video|note)/([0-9]{8,25})(?:/|\\?|$)");
	private static final Pattern DOUYIN_MODAL_ID = Pattern.compile("(?:[?&](?:modal_id|aweme_id|item_id)=)([0-9]{8,25})(?:&|$)");

	private static final DateTimeFormatter OUTPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final List<DateTimeFormatter> LOCAL_DATE_TIMES = List.of(
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
			DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
			DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
			DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"),
			DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"));
	private static final List<DateTimeFormatter> LOCAL_DATES = List.of(
			DateTimeFormatter.ISO_LOCAL_DATE,
			DateTimeFormatter.ofPattern("yyyy/MM/dd"),
			DateTimeFormatter.ofPattern("yyyy年MM月dd日"));

	private final ZoneId zoneId;

	public WorkMetadataNormalizer() {
		this(ZoneId.systemDefault());
	}

	public WorkMetadataNormalizer(ZoneId zoneId) {
		this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
	}

	public WorkMetadata normalize(WorkMetadata metadata) {
		if (metadata == null) {
			throw new WorkMetadataValidationException("work metadata must not be null");
		}
		validate(metadata);
		return WorkMetadata.builder()
				.platformKey(metadata.getPlatformKey())
				.platformDisplayName(resolveDisplayName(metadata))
				.supportTier(metadata.getSupportTier())
				.workId(normalizeWorkId(metadata.getPlatformKey(), metadata.getWorkId()))
				.contentType(metadata.getContentType())
				.title(metadata.getTitle())
				.description(metadata.getDescription())
				.authorId(metadata.getAuthorId())
				.authorUsername(metadata.getAuthorUsername())
				.authorName(metadata.getAuthorName())
				.authorAvatar(metadata.getAuthorAvatar())
				.authorHomepage(metadata.getAuthorHomepage())
				.authorSignature(metadata.getAuthorSignature())
				.publishTime(normalizePublishTime(metadata.getPublishTime()))
				.sourceUrl(metadata.getSourceUrl())
				.originalAddress(metadata.getOriginalAddress())
				.coverUrl(metadata.getCoverUrl())
				.mediaResources(metadata.getMediaResources())
				.rawMetadata(metadata.getRawMetadata())
				.build();
	}

	/**
	 * F2 and DTK both identify a Douyin work by aweme_id, but direct responses may
	 * contain a full /video/{id} URL or an alias such as content_id. Persist only
	 * the canonical numeric ID so the two providers share one identity.
	 */
	private String normalizeWorkId(String platformKey, String workId) {
		if (!hasText(workId) || !PlatformCatalog.findByAlias(platformKey)
				.map(definition -> "douyin".equals(definition.getKey())).orElse(false)) {
			return hasText(workId) ? workId.trim() : null;
		}
		String value = workId.trim();
		if (value.matches("\\d+")) return value;
		Matcher pathMatcher = DOUYIN_PATH_ID.matcher(value);
		if (pathMatcher.find()) return pathMatcher.group(1);
		Matcher queryMatcher = DOUYIN_MODAL_ID.matcher(value);
		if (queryMatcher.find()) return queryMatcher.group(1);
		// Preserve an opaque provider value when it is not an explicit Douyin ID.
		// Missing or malformed IDs are rejected by the provider/adapter contract;
		// this normalizer must never invent one from arbitrary text.
		return value;
	}

	public String normalizePublishTime(String value) {
		if (!hasText(value)) {
			return null;
		}
		String text = value.trim();
		try {
			if (text.matches("\\d{8}")) {
				return LocalDate.parse(text, COMPACT_DATE).atStartOfDay().format(OUTPUT_FORMAT);
			}
			if (text.matches("\\d{9,13}")) {
				long raw = Long.parseLong(text);
				Instant instant = text.length() >= 12 ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
				return OUTPUT_FORMAT.format(instant.atZone(zoneId));
			}
			try {
				return OUTPUT_FORMAT.format(Instant.parse(text).atZone(zoneId));
			} catch (DateTimeParseException ignored) {
			}
			try {
				return OUTPUT_FORMAT.format(OffsetDateTime.parse(text, DateTimeFormatter.ISO_DATE_TIME)
						.atZoneSameInstant(zoneId));
			} catch (DateTimeParseException ignored) {
			}
			try {
				return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME).format(OUTPUT_FORMAT);
			} catch (DateTimeParseException ignored) {
			}
			for (DateTimeFormatter formatter : LOCAL_DATE_TIMES) {
				try {
					return LocalDateTime.parse(text, formatter).format(OUTPUT_FORMAT);
				} catch (DateTimeParseException ignored) {
				}
			}
			for (DateTimeFormatter formatter : LOCAL_DATES) {
				try {
					return LocalDate.parse(text, formatter).atStartOfDay().format(OUTPUT_FORMAT);
				} catch (DateTimeParseException ignored) {
				}
			}
		} catch (RuntimeException e) {
			throw new WorkMetadataValidationException("invalid publish time: " + text, e);
		}
		throw new WorkMetadataValidationException("invalid publish time: " + text);
	}

	private void validate(WorkMetadata metadata) {
		if (!hasText(metadata.getPlatformKey())) {
			throw new WorkMetadataValidationException("platform key is required");
		}
		if (metadata.getSupportTier() == null) {
			throw new WorkMetadataValidationException("platform support tier is required");
		}
		if (metadata.getContentType() == null) {
			throw new WorkMetadataValidationException("content type is required");
		}
		if (metadata.getSupportTier() == PlatformSupportTier.FORMAL) {
			validateFormal(metadata);
		} else if (metadata.getSupportTier() == PlatformSupportTier.GENERIC) {
			validateGeneric(metadata);
		} else {
			throw new WorkMetadataValidationException("unsupported platform metadata cannot be ingested");
		}
	}

	private void validateFormal(WorkMetadata metadata) {
		PlatformDefinition definition;
		try {
			definition = PlatformCatalog.requireByKey(metadata.getPlatformKey());
		} catch (IllegalArgumentException e) {
			throw new WorkMetadataValidationException("unknown formal platform: " + metadata.getPlatformKey(), e);
		}
		if (definition.getSupportTier() != PlatformSupportTier.FORMAL) {
			throw new WorkMetadataValidationException("platform is not formally supported: " + metadata.getPlatformKey());
		}
		if (!hasText(metadata.getWorkId())) {
			throw new WorkMetadataValidationException("formal platform work ID is required");
		}
		boolean hasVisualMedia = metadata.getMediaResources().stream()
				.anyMatch(resource -> resource.getType() != WorkMediaResource.Type.AUDIO);
		if (!hasVisualMedia) {
			throw new WorkMetadataValidationException("formal platform requires at least one visual media resource");
		}
	}

	private void validateGeneric(WorkMetadata metadata) {
		if (metadata.getContentType() != WorkContentType.VIDEO) {
			throw new WorkMetadataValidationException("generic adapters support video works only");
		}
		if (!isHttpUrl(metadata.getSourceUrl())) {
			throw new WorkMetadataValidationException("generic work canonical source URL is required");
		}
		boolean hasDownloadableVideo = metadata.getMediaResources().stream()
				.anyMatch(resource -> resource.getType() == WorkMediaResource.Type.VIDEO
						&& isHttpUrl(resource.getSourceUrl()));
		if (!hasDownloadableVideo) {
			throw new WorkMetadataValidationException("generic work requires a downloadable video resource");
		}
	}

	private String resolveDisplayName(WorkMetadata metadata) {
		return PlatformCatalog.findByAlias(metadata.getPlatformKey())
				.map(PlatformDefinition::getDisplayName)
				.orElse(metadata.getPlatformDisplayName());
	}

	private static boolean isHttpUrl(String value) {
		if (!hasText(value)) {
			return false;
		}
		try {
			URI uri = URI.create(value.trim());
			return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
					|| "https".equalsIgnoreCase(uri.getScheme()));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
}
