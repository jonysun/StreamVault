package com.flower.spirit.platform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class WorkMetadata {

	private final String platformKey;
	private final String platformDisplayName;
	private final PlatformSupportTier supportTier;
	private final String workId;
	private final WorkContentType contentType;
	private final String title;
	private final String description;
	private final String authorId;
	private final String authorUsername;
	private final String authorName;
	private final String authorAvatar;
	private final String authorHomepage;
	private final String authorSignature;
	private final String publishTime;
	private final String sourceUrl;
	private final String originalAddress;
	private final String coverUrl;
	private final List<WorkMediaResource> mediaResources;
	private final String rawMetadata;

	private WorkMetadata(Builder builder) {
		this.platformKey = trimToNull(builder.platformKey);
		this.platformDisplayName = trimToNull(builder.platformDisplayName);
		this.supportTier = builder.supportTier;
		this.workId = trimToNull(builder.workId);
		this.contentType = builder.contentType;
		this.title = trimToNull(builder.title);
		this.description = trimToNull(builder.description);
		this.authorId = trimToNull(builder.authorId);
		this.authorUsername = trimToNull(builder.authorUsername);
		this.authorName = trimToNull(builder.authorName);
		this.authorAvatar = trimToNull(builder.authorAvatar);
		this.authorHomepage = trimToNull(builder.authorHomepage);
		this.authorSignature = trimToNull(builder.authorSignature);
		this.publishTime = trimToNull(builder.publishTime);
		this.sourceUrl = trimToNull(builder.sourceUrl);
		this.originalAddress = trimToNull(builder.originalAddress);
		this.coverUrl = trimToNull(builder.coverUrl);
		ArrayList<WorkMediaResource> resources = new ArrayList<>(builder.mediaResources);
		resources.sort(Comparator.comparingInt(WorkMediaResource::getOrder));
		this.mediaResources = List.copyOf(resources);
		this.rawMetadata = builder.rawMetadata;
	}

	public static Builder builder() {
		return new Builder();
	}

	public boolean hasStableIdentity() {
		return platformKey != null && workId != null;
	}

	public String getPlatformKey() {
		return platformKey;
	}

	public String getPlatformDisplayName() {
		return platformDisplayName;
	}

	public PlatformSupportTier getSupportTier() {
		return supportTier;
	}

	public String getWorkId() {
		return workId;
	}

	public WorkContentType getContentType() {
		return contentType;
	}

	public String getTitle() {
		return title;
	}

	public String getDescription() {
		return description;
	}

	public String getAuthorId() {
		return authorId;
	}

	public String getAuthorUsername() {
		return authorUsername;
	}

	public String getAuthorName() {
		return authorName;
	}

	public String getAuthorAvatar() {
		return authorAvatar;
	}

	public String getAuthorHomepage() {
		return authorHomepage;
	}

	public String getAuthorSignature() {
		return authorSignature;
	}

	public String getPublishTime() {
		return publishTime;
	}

	public String getSourceUrl() {
		return sourceUrl;
	}

	public String getOriginalAddress() {
		return originalAddress;
	}

	public String getCoverUrl() {
		return coverUrl;
	}

	public List<WorkMediaResource> getMediaResources() {
		return mediaResources;
	}

	public String getRawMetadata() {
		return rawMetadata;
	}

	public static final class Builder {

		private String platformKey;
		private String platformDisplayName;
		private PlatformSupportTier supportTier;
		private String workId;
		private WorkContentType contentType;
		private String title;
		private String description;
		private String authorId;
		private String authorUsername;
		private String authorName;
		private String authorAvatar;
		private String authorHomepage;
		private String authorSignature;
		private String publishTime;
		private String sourceUrl;
		private String originalAddress;
		private String coverUrl;
		private List<WorkMediaResource> mediaResources = List.of();
		private String rawMetadata;

		private Builder() {
		}

		public Builder platform(PlatformDefinition definition) {
			Objects.requireNonNull(definition, "definition");
			this.platformKey = definition.getKey();
			this.platformDisplayName = definition.getDisplayName();
			this.supportTier = definition.getSupportTier();
			return this;
		}

		public Builder platformKey(String platformKey) {
			this.platformKey = platformKey;
			return this;
		}

		public Builder platformDisplayName(String platformDisplayName) {
			this.platformDisplayName = platformDisplayName;
			return this;
		}

		public Builder supportTier(PlatformSupportTier supportTier) {
			this.supportTier = supportTier;
			return this;
		}

		public Builder workId(String workId) {
			this.workId = workId;
			return this;
		}

		public Builder contentType(WorkContentType contentType) {
			this.contentType = contentType;
			return this;
		}

		public Builder title(String title) {
			this.title = title;
			return this;
		}

		public Builder description(String description) {
			this.description = description;
			return this;
		}

		public Builder authorId(String authorId) {
			this.authorId = authorId;
			return this;
		}

		public Builder authorUsername(String authorUsername) {
			this.authorUsername = authorUsername;
			return this;
		}

		public Builder authorName(String authorName) {
			this.authorName = authorName;
			return this;
		}

		public Builder authorAvatar(String authorAvatar) {
			this.authorAvatar = authorAvatar;
			return this;
		}

		public Builder authorHomepage(String authorHomepage) {
			this.authorHomepage = authorHomepage;
			return this;
		}

		public Builder authorSignature(String authorSignature) {
			this.authorSignature = authorSignature;
			return this;
		}

		public Builder publishTime(String publishTime) {
			this.publishTime = publishTime;
			return this;
		}

		public Builder sourceUrl(String sourceUrl) {
			this.sourceUrl = sourceUrl;
			return this;
		}

		public Builder originalAddress(String originalAddress) {
			this.originalAddress = originalAddress;
			return this;
		}

		public Builder coverUrl(String coverUrl) {
			this.coverUrl = coverUrl;
			return this;
		}

		public Builder mediaResources(List<WorkMediaResource> mediaResources) {
			this.mediaResources = mediaResources == null ? List.of() : List.copyOf(mediaResources);
			return this;
		}

		public Builder rawMetadata(String rawMetadata) {
			this.rawMetadata = rawMetadata;
			return this;
		}

		public WorkMetadata build() {
			return new WorkMetadata(this);
		}
	}

	private static String trimToNull(String value) {
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}
}
