package com.flower.spirit.service;

import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson.JSON;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataNormalizer;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.service.WorkDeduplicationService.ExistingWork;

@Service
public class WorkPersistenceService {

	private final WorkMetadataNormalizer normalizer;
	private final WorkDeduplicationService deduplicationService;
	private final VideoDataDao videoDataDao;
	private final GraphicContentDao graphicContentDao;
	private final AuthorProfileService authorProfileService;

	public WorkPersistenceService(WorkMetadataNormalizer normalizer, WorkDeduplicationService deduplicationService,
			VideoDataDao videoDataDao, GraphicContentDao graphicContentDao,
			AuthorProfileService authorProfileService) {
		this.normalizer = normalizer;
		this.deduplicationService = deduplicationService;
		this.videoDataDao = videoDataDao;
		this.graphicContentDao = graphicContentDao;
		this.authorProfileService = authorProfileService;
	}

	@Transactional
	public PersistenceResult persist(WorkMetadata input) {
		WorkMetadata metadata = normalizer.normalize(input);
		deduplicationService.assertNotBlocked(metadata);
		ExistingWork existing = deduplicationService.findExisting(metadata).orElse(null);
		PersistenceResult result = metadata.getContentType() == WorkContentType.VIDEO
				? persistVideo(metadata, existing)
				: persistGraphic(metadata, existing);
		authorProfileService.upsertCanonicalAuthor(metadata.getPlatformKey(), metadata.getPlatformDisplayName(),
				metadata.getAuthorId(), metadata.getAuthorUsername(), metadata.getAuthorName(),
				metadata.getAuthorAvatar(), metadata.getAuthorHomepage());
		return result;
	}

	private PersistenceResult persistVideo(WorkMetadata metadata, ExistingWork existing) {
		VideoDataEntity entity = existing == null ? new VideoDataEntity() : existing.video();
		if (entity == null) {
			throw new WorkMetadataValidationException("existing work type conflicts with video metadata");
		}
		boolean created = entity.getId() == null;
		Path localVideo = metadata.getMediaResources().stream()
				.filter(resource -> resource.getType() == WorkMediaResource.Type.VIDEO)
				.map(WorkMediaResource::getLocalPath)
				.filter(path -> path != null)
				.findFirst().orElse(null);
		if (created && localVideo == null) {
			throw new WorkMetadataValidationException("downloaded video local path is required before persistence");
		}
		entity.setVideoid(metadata.getWorkId());
		entity.setVideoplatform(platformDisplayValue(metadata));
		entity.setPlatformkey(metadata.getPlatformKey());
		entity.setContenttype(metadata.getContentType().getValue());
		setVideoOptionalFields(entity, metadata);
		if (localVideo != null) {
			String path = localVideo.normalize().toString();
			entity.setVideoaddr(path);
			entity.setVideounrealaddr(path);
		}
		if (created) {
			entity.setCreatetime(new Date());
		}
		VideoDataEntity saved = videoDataDao.save(entity);
		return PersistenceResult.video(created, saved);
	}

	private void setVideoOptionalFields(VideoDataEntity entity, WorkMetadata metadata) {
		if (hasText(metadata.getOriginalAddress())) entity.setOriginaladdress(metadata.getOriginalAddress());
		if (hasText(metadata.getTitle())) entity.setVideoname(metadata.getTitle());
		if (hasText(metadata.getDescription())) entity.setVideodesc(metadata.getDescription());
		if (hasText(metadata.getAuthorName())) entity.setVideoauthor(metadata.getAuthorName());
		if (hasText(metadata.getAuthorId())) {
			entity.setAuthoruid(metadata.getAuthorId());
			entity.setSecuid(metadata.getAuthorId());
		}
		if (hasText(metadata.getAuthorUsername())) {
			entity.setAuthorusername(metadata.getAuthorUsername());
			entity.setUniqueid(metadata.getAuthorUsername());
		}
		if (hasText(metadata.getAuthorAvatar())) entity.setAuthoravatar(metadata.getAuthorAvatar());
		if (hasText(metadata.getAuthorHomepage())) entity.setAuthorhomepage(metadata.getAuthorHomepage());
		if (hasText(metadata.getPublishTime())) entity.setPublishtime(metadata.getPublishTime());
		if (hasText(metadata.getSourceUrl())) entity.setSourceurl(metadata.getSourceUrl());
		if (hasText(metadata.getCoverUrl())) entity.setVideocover(metadata.getCoverUrl());
		if (metadata.getRawMetadata() != null) entity.setJsonData(metadata.getRawMetadata());
	}

	private PersistenceResult persistGraphic(WorkMetadata metadata, ExistingWork existing) {
		GraphicContentEntity entity = existing == null ? new GraphicContentEntity() : existing.graphic();
		if (entity == null) {
			throw new WorkMetadataValidationException("existing work type conflicts with graphic metadata");
		}
		boolean created = entity.getId() == null;
		List<Path> localPaths = metadata.getMediaResources().stream()
				.filter(resource -> resource.getType() != WorkMediaResource.Type.AUDIO)
				.map(WorkMediaResource::getLocalPath)
				.filter(path -> path != null)
				.toList();
		if (created && localPaths.isEmpty()) {
			throw new WorkMetadataValidationException("downloaded graphic media local path is required before persistence");
		}
		entity.setVideoid(metadata.getWorkId());
		entity.setPlatform(platformDisplayValue(metadata));
		entity.setPlatformkey(metadata.getPlatformKey());
		entity.setContenttype(metadata.getContentType().getValue());
		setGraphicOptionalFields(entity, metadata);
		if (!localPaths.isEmpty()) {
			List<String> paths = localPaths.stream().map(path -> path.normalize().toString()).toList();
			entity.setImages(JSON.toJSONString(paths));
			Path parent = localPaths.get(0).normalize().getParent();
			if (parent != null) {
				entity.setMarkroute(parent.toString());
			}
		}
		if (created) {
			entity.setCreatetime(new Date());
		}
		GraphicContentEntity saved = graphicContentDao.save(entity);
		return PersistenceResult.graphic(created, saved, metadata.getContentType());
	}

	private void setGraphicOptionalFields(GraphicContentEntity entity, WorkMetadata metadata) {
		if (hasText(metadata.getOriginalAddress())) entity.setOriginaladdress(metadata.getOriginalAddress());
		if (hasText(metadata.getTitle())) entity.setTitle(metadata.getTitle());
		if (hasText(metadata.getDescription())) entity.setContent(metadata.getDescription());
		if (hasText(metadata.getAuthorName())) entity.setAuthor(metadata.getAuthorName());
		if (hasText(metadata.getAuthorId())) {
			entity.setAuthoruid(metadata.getAuthorId());
			entity.setSecuid(metadata.getAuthorId());
		}
		if (hasText(metadata.getAuthorUsername())) {
			entity.setAuthorusername(metadata.getAuthorUsername());
			entity.setUniqueid(metadata.getAuthorUsername());
		}
		if (hasText(metadata.getAuthorAvatar())) entity.setAuthoravatar(metadata.getAuthorAvatar());
		if (hasText(metadata.getAuthorHomepage())) entity.setAuthorhomepage(metadata.getAuthorHomepage());
		if (hasText(metadata.getPublishTime())) entity.setPublishtime(metadata.getPublishTime());
		if (hasText(metadata.getSourceUrl())) entity.setSourceurl(metadata.getSourceUrl());
		if (metadata.getRawMetadata() != null) entity.setJsonData(metadata.getRawMetadata());
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}

	private static String platformDisplayValue(WorkMetadata metadata) {
		return hasText(metadata.getPlatformDisplayName())
				? metadata.getPlatformDisplayName().trim() : metadata.getPlatformKey();
	}

	public record PersistenceResult(boolean created, WorkContentType contentType, Integer id,
			VideoDataEntity video, GraphicContentEntity graphic) {

		public static PersistenceResult video(boolean created, VideoDataEntity video) {
			return new PersistenceResult(created, WorkContentType.VIDEO, video.getId(), video, null);
		}

		public static PersistenceResult graphic(boolean created, GraphicContentEntity graphic,
				WorkContentType contentType) {
			return new PersistenceResult(created, contentType, graphic.getId(), null, graphic);
		}
	}
}
