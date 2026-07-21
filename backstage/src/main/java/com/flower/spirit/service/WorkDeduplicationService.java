package com.flower.spirit.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.PlatformDefinition;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;

@Service
public class WorkDeduplicationService {

	private final VideoDataDao videoDataDao;
	private final GraphicContentDao graphicContentDao;
	private final BlockedWorkService blockedWorkService;

	public WorkDeduplicationService(VideoDataDao videoDataDao, GraphicContentDao graphicContentDao,
			BlockedWorkService blockedWorkService) {
		this.videoDataDao = videoDataDao;
		this.graphicContentDao = graphicContentDao;
		this.blockedWorkService = blockedWorkService;
	}

	public Optional<ExistingWork> findExisting(WorkMetadata metadata) {
		if (metadata == null || !hasText(metadata.getPlatformKey()) || metadata.getContentType() == null) {
			return Optional.empty();
		}
		return metadata.getContentType() == WorkContentType.VIDEO
				? findVideo(metadata)
				: findGraphic(metadata);
	}

	public boolean isBlocked(WorkMetadata metadata) {
		if (metadata == null || !hasText(metadata.getWorkId()) || metadata.getContentType() == null) {
			return false;
		}
		String workType = metadata.getContentType() == WorkContentType.VIDEO ? "video" : "graphic";
		for (String alias : platformAliases(metadata)) {
			if (blockedWorkService.isBlocked(alias, metadata.getWorkId(), workType)) {
				return true;
			}
		}
		return false;
	}

	public void assertNotBlocked(WorkMetadata metadata) {
		if (isBlocked(metadata)) {
			throw new WorkMetadataValidationException("work is blocked: " + metadata.getPlatformKey() + "/"
					+ metadata.getWorkId());
		}
	}

	private Optional<ExistingWork> findVideo(WorkMetadata metadata) {
		List<String> aliases = platformAliases(metadata);
		if (hasText(metadata.getWorkId())) {
			Optional<VideoDataEntity> exact = firstMatchingVideo(
					videoDataDao.findByPlatformkeyAndVideoid(metadata.getPlatformKey(), metadata.getWorkId()),
					metadata.getContentType());
			if (exact.isPresent()) {
				return exact.map(ExistingWork::video);
			}
			Optional<VideoDataEntity> legacy = firstMatchingVideo(
					videoDataDao.findByVideoidAndVideoplatformIn(metadata.getWorkId(), aliases), metadata.getContentType());
			if (legacy.isPresent()) {
				return legacy.map(ExistingWork::video);
			}
		}
		if (hasText(metadata.getOriginalAddress())) {
			return firstMatchingVideo(videoDataDao.findByOriginaladdressAndVideoplatformIn(
					metadata.getOriginalAddress(), aliases),
					metadata.getContentType()).map(ExistingWork::video);
		}
		return Optional.empty();
	}

	private Optional<ExistingWork> findGraphic(WorkMetadata metadata) {
		List<String> aliases = platformAliases(metadata);
		if (hasText(metadata.getWorkId())) {
			Optional<GraphicContentEntity> exact = firstMatchingGraphic(
					graphicContentDao.findByPlatformkeyAndVideoid(metadata.getPlatformKey(), metadata.getWorkId()),
					metadata.getContentType());
			if (exact.isPresent()) {
				return exact.map(ExistingWork::graphic);
			}
			Optional<GraphicContentEntity> legacy = firstMatchingGraphic(
					graphicContentDao.findByVideoidAndPlatformIn(metadata.getWorkId(), aliases), metadata.getContentType());
			if (legacy.isPresent()) {
				return legacy.map(ExistingWork::graphic);
			}
		}
		if (hasText(metadata.getOriginalAddress())) {
			return firstMatchingGraphic(graphicContentDao.findByOriginaladdressAndPlatformIn(
					metadata.getOriginalAddress(), aliases),
					metadata.getContentType()).map(ExistingWork::graphic);
		}
		return Optional.empty();
	}

	private Optional<VideoDataEntity> firstMatchingVideo(List<VideoDataEntity> rows, WorkContentType contentType) {
		if (rows == null) {
			return Optional.empty();
		}
		return rows.stream().filter(row -> row != null && matchesContentType(row.getContenttype(), contentType)).findFirst();
	}

	private Optional<GraphicContentEntity> firstMatchingGraphic(List<GraphicContentEntity> rows,
			WorkContentType contentType) {
		if (rows == null) {
			return Optional.empty();
		}
		return rows.stream().filter(row -> row != null && matchesContentType(row.getContenttype(), contentType)).findFirst();
	}

	private boolean matchesContentType(String storedType, WorkContentType requestedType) {
		return !hasText(storedType) || requestedType.getValue().equalsIgnoreCase(storedType.trim());
	}

	private List<String> platformAliases(WorkMetadata metadata) {
		Set<String> aliases = new LinkedHashSet<>();
		PlatformCatalog.findByAlias(metadata.getPlatformKey())
				.map(PlatformDefinition::getAliases)
				.ifPresent(aliases::addAll);
		if (hasText(metadata.getPlatformKey())) {
			aliases.add(metadata.getPlatformKey().trim());
		}
		if (hasText(metadata.getPlatformDisplayName())) {
			aliases.add(metadata.getPlatformDisplayName().trim());
		}
		return List.copyOf(aliases);
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}

	public record ExistingWork(WorkContentType contentType, VideoDataEntity video, GraphicContentEntity graphic) {

		public static ExistingWork video(VideoDataEntity video) {
			return new ExistingWork(WorkContentType.VIDEO, video, null);
		}

		public static ExistingWork graphic(GraphicContentEntity graphic) {
			WorkContentType type = "mixed".equalsIgnoreCase(graphic.getContenttype())
					? WorkContentType.MIXED : WorkContentType.GRAPHIC;
			return new ExistingWork(type, null, graphic);
		}
	}
}
