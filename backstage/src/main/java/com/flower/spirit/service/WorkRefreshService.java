package com.flower.spirit.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.dto.WorkOperationRequest;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.PlatformDefinition;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataNormalizer;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.platform.WorkParseRequest;
import com.flower.spirit.platform.adapter.PlatformAdapterRegistry;
import com.flower.spirit.platform.adapter.PlatformWorkAdapter;
import com.flower.spirit.service.WorkPersistenceService.PersistenceResult;

@Service
public class WorkRefreshService {

	private final VideoDataDao videoDataDao;
	private final GraphicContentDao graphicContentDao;
	private final PlatformAdapterRegistry adapterRegistry;
	private final WorkMetadataNormalizer normalizer;
	private final WorkPersistenceService persistenceService;
	private final WorkMetadataEditService editService;

	public WorkRefreshService(VideoDataDao videoDataDao, GraphicContentDao graphicContentDao,
			PlatformAdapterRegistry adapterRegistry, WorkMetadataNormalizer normalizer,
			WorkPersistenceService persistenceService, WorkMetadataEditService editService) {
		this.videoDataDao = videoDataDao;
		this.graphicContentDao = graphicContentDao;
		this.adapterRegistry = adapterRegistry;
		this.normalizer = normalizer;
		this.persistenceService = persistenceService;
		this.editService = editService;
	}

	@Transactional
	public PersistenceResult refresh(WorkOperationRequest request) {
		PreparedWork prepared = prepare(request, true);
		PersistenceResult result = persistenceService.persist(prepared.metadata());
		if (result.video() != null) {
			editService.reapplyStoredOverrides(result.video());
			videoDataDao.save(result.video());
		} else if (result.graphic() != null) {
			editService.reapplyStoredOverrides(result.graphic());
			graphicContentDao.save(result.graphic());
		}
		return result;
	}

	public PreparedWork prepare(WorkOperationRequest request, boolean preview) {
		validateRequest(request);
		if ("video".equals(normalizeType(request.getWorkType()))) {
			VideoDataEntity entity = videoDataDao.findById(request.getId())
					.orElseThrow(() -> new WorkMetadataValidationException("video work not found: " + request.getId()));
			String source = source(entity.getSourceurl(), entity.getOriginaladdress());
			String platformKey = platformKey(entity.getPlatformkey(), entity.getVideoplatform());
			PlatformWorkAdapter adapter = adapterRegistry.requireByPlatformKey(platformKey);
			WorkMetadata latest = normalizer.normalize(adapter.parse(new WorkParseRequest(source, source, preview)));
			return new PreparedWork("video", lockVideoIdentity(entity, latest), adapter, entity, null);
		}
		GraphicContentEntity entity = graphicContentDao.findById(request.getId())
				.orElseThrow(() -> new WorkMetadataValidationException("graphic work not found: " + request.getId()));
		String source = source(entity.getSourceurl(), entity.getOriginaladdress());
		String platformKey = platformKey(entity.getPlatformkey(), entity.getPlatform());
		PlatformWorkAdapter adapter = adapterRegistry.requireByPlatformKey(platformKey);
		WorkMetadata latest = normalizer.normalize(adapter.parse(new WorkParseRequest(source, source, preview)));
		return new PreparedWork("graphic", lockGraphicIdentity(entity, latest), adapter, null, entity);
	}

	private WorkMetadata lockVideoIdentity(VideoDataEntity entity, WorkMetadata latest) {
		String key = platformKey(entity.getPlatformkey(), entity.getVideoplatform());
		return copy(latest, key, displayName(key, entity.getVideoplatform()), entity.getVideoid(), WorkContentType.VIDEO,
				entity.getOriginaladdress(), firstText(latest.getSourceUrl(), entity.getSourceurl()));
	}

	private WorkMetadata lockGraphicIdentity(GraphicContentEntity entity, WorkMetadata latest) {
		String key = platformKey(entity.getPlatformkey(), entity.getPlatform());
		String storedType = entity.getContenttype();
		if (storedType == null || storedType.trim().isEmpty()) {
			storedType = PlatformMetadataCompatibilityService.inferGraphicContentType(entity.getImages());
		}
		WorkContentType type = "mixed".equalsIgnoreCase(storedType) ? WorkContentType.MIXED : WorkContentType.GRAPHIC;
		return copy(latest, key, displayName(key, entity.getPlatform()), entity.getVideoid(), type,
				entity.getOriginaladdress(), firstText(latest.getSourceUrl(), entity.getSourceurl()));
	}

	private WorkMetadata copy(WorkMetadata latest, String platformKey, String displayName, String workId,
			WorkContentType type, String originalAddress, String sourceUrl) {
		PlatformDefinition definition = PlatformCatalog.findByAlias(platformKey).orElse(null);
		return WorkMetadata.builder()
				.platformKey(platformKey)
				.platformDisplayName(displayName)
				.supportTier(definition == null ? latest.getSupportTier() : definition.getSupportTier())
				.workId(workId)
				.contentType(type)
				.title(latest.getTitle())
				.description(latest.getDescription())
				.authorId(latest.getAuthorId())
				.authorUsername(latest.getAuthorUsername())
				.authorName(latest.getAuthorName())
				.authorAvatar(latest.getAuthorAvatar())
				.authorHomepage(latest.getAuthorHomepage())
				.authorSignature(latest.getAuthorSignature())
				.publishTime(latest.getPublishTime())
				.sourceUrl(sourceUrl)
				.originalAddress(originalAddress)
				.coverUrl(latest.getCoverUrl())
				.mediaResources(latest.getMediaResources())
				.rawMetadata(latest.getRawMetadata())
				.build();
	}

	private String platformKey(String explicit, String legacy) {
		String key = PlatformMetadataCompatibilityService.resolvePlatformKey(explicit, legacy);
		if (key == null) throw new WorkMetadataValidationException("work platform cannot be resolved");
		return key;
	}

	private String displayName(String key, String legacy) {
		return PlatformMetadataCompatibilityService.resolveDisplayName(key, legacy);
	}

	private String source(String sourceUrl, String originalAddress) {
		String source = firstText(sourceUrl, originalAddress);
		if (source == null) throw new WorkMetadataValidationException("work has no source URL or original address");
		return source;
	}

	private String firstText(String first, String second) {
		if (first != null && !first.trim().isEmpty()) return first.trim();
		return second == null || second.trim().isEmpty() ? null : second.trim();
	}

	private void validateRequest(WorkOperationRequest request) {
		if (request == null || request.getId() == null || request.getId() <= 0) {
			throw new WorkMetadataValidationException("positive work id is required");
		}
		String type = normalizeType(request.getWorkType());
		if (!"video".equals(type) && !"graphic".equals(type)) {
			throw new WorkMetadataValidationException("workType must be video or graphic");
		}
	}

	private String normalizeType(String value) {
		return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
	}

	public record PreparedWork(String workType, WorkMetadata metadata, PlatformWorkAdapter adapter,
			VideoDataEntity video, GraphicContentEntity graphic) {
	}
}
