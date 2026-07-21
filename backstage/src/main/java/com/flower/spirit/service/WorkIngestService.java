package com.flower.spirit.service;

import java.nio.file.Path;

import org.springframework.stereotype.Service;

import com.flower.spirit.entity.ProcessHistoryEntity;
import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataNormalizer;
import com.flower.spirit.platform.WorkParseRequest;
import com.flower.spirit.platform.adapter.PlatformAdapterRegistry;
import com.flower.spirit.platform.adapter.PlatformWorkAdapter;
import com.flower.spirit.service.MediaDownloadService.DownloadOutcome;
import com.flower.spirit.service.WorkPersistenceService.PersistenceResult;

@Service
public class WorkIngestService {

	private final PlatformResolver resolver;
	private final PlatformAdapterRegistry adapterRegistry;
	private final WorkMetadataNormalizer normalizer;
	private final MediaDownloadService mediaDownloadService;
	private final WorkPersistenceService persistenceService;
	private final WorkPostProcessingService postProcessingService;
	private final ProcessHistoryService processHistoryService;

	public WorkIngestService(PlatformResolver resolver, PlatformAdapterRegistry adapterRegistry,
			WorkMetadataNormalizer normalizer, MediaDownloadService mediaDownloadService,
			WorkPersistenceService persistenceService, WorkPostProcessingService postProcessingService,
			ProcessHistoryService processHistoryService) {
		this.resolver = resolver;
		this.adapterRegistry = adapterRegistry;
		this.normalizer = normalizer;
		this.mediaDownloadService = mediaDownloadService;
		this.persistenceService = persistenceService;
		this.postProcessingService = postProcessingService;
		this.processHistoryService = processHistoryService;
	}

	public WorkMetadata preview(String input) {
		PlatformResolver.Resolution resolution = resolver.resolveRequired(input);
		PlatformWorkAdapter adapter = adapterRegistry.requireByPlatformKey(resolution.platform().getKey());
		return normalizer.normalize(adapter.parse(new WorkParseRequest(input, resolution.url(), true)));
	}

	public IngestResult ingest(String input, Path outputDirectory, boolean replaceExisting) {
		ProcessHistoryEntity history = processHistoryService.beginPlatformProcess(input, "unknown", "RECOGNIZING");
		Integer historyId = history == null ? null : history.getId();
		String stage = "RECOGNIZING";
		try {
			PlatformResolver.Resolution resolution = resolver.resolveRequired(input);
			PlatformWorkAdapter adapter = adapterRegistry.requireByPlatformKey(resolution.platform().getKey());
			stage = "PARSING";
			processHistoryService.recordPlatformStage(historyId, stage);
			WorkMetadata metadata = normalizer.normalize(
					adapter.parse(new WorkParseRequest(input, resolution.url(), false)));

			stage = "DOWNLOADING";
			processHistoryService.recordPlatformStage(historyId, stage);
			DownloadOutcome download = mediaDownloadService.download(adapter, metadata,
					new WorkDownloadRequest(outputDirectory, replaceExisting));
			if (download.status() == DownloadResult.Status.QUEUED) {
				processHistoryService.recordPlatformStage(historyId, "QUEUED");
				return IngestResult.queued(historyId, metadata, download.message(), download.workingDirectory());
			}

			stage = "VERIFYING";
			processHistoryService.recordPlatformStage(historyId, stage);
			WorkMetadata downloadedMetadata = copyWithDownloadedResources(metadata, download);
			stage = "PERSISTING";
			processHistoryService.recordPlatformStage(historyId, stage);
			PersistenceResult persistence = persistenceService.persist(downloadedMetadata);
			stage = "POST_PROCESSING";
			processHistoryService.recordPlatformStage(historyId, stage);
			postProcessingService.complete(historyId, downloadedMetadata, persistence);
			return IngestResult.completed(historyId, downloadedMetadata, persistence, download.workingDirectory());
		} catch (RuntimeException e) {
			processHistoryService.failPlatformProcess(historyId, stage, e.getMessage());
			throw e;
		}
	}

	private WorkMetadata copyWithDownloadedResources(WorkMetadata metadata, DownloadOutcome download) {
		return WorkMetadata.builder()
				.platformKey(metadata.getPlatformKey())
				.platformDisplayName(metadata.getPlatformDisplayName())
				.supportTier(metadata.getSupportTier())
				.workId(metadata.getWorkId())
				.contentType(metadata.getContentType())
				.title(metadata.getTitle())
				.description(metadata.getDescription())
				.authorId(metadata.getAuthorId())
				.authorUsername(metadata.getAuthorUsername())
				.authorName(metadata.getAuthorName())
				.authorAvatar(metadata.getAuthorAvatar())
				.authorHomepage(metadata.getAuthorHomepage())
				.authorSignature(metadata.getAuthorSignature())
				.publishTime(metadata.getPublishTime())
				.sourceUrl(metadata.getSourceUrl())
				.originalAddress(metadata.getOriginalAddress())
				.coverUrl(metadata.getCoverUrl())
				.mediaResources(download.mediaResources())
				.rawMetadata(metadata.getRawMetadata())
				.build();
	}

	public record IngestResult(DownloadResult.Status status, Integer historyId, WorkMetadata metadata,
			PersistenceResult persistence, String message, Path workingDirectory) {

		public static IngestResult queued(Integer historyId, WorkMetadata metadata, String message, Path directory) {
			return new IngestResult(DownloadResult.Status.QUEUED, historyId, metadata, null, message, directory);
		}

		public static IngestResult completed(Integer historyId, WorkMetadata metadata,
				PersistenceResult persistence, Path directory) {
			return new IngestResult(DownloadResult.Status.COMPLETED, historyId, metadata, persistence, null, directory);
		}
	}
}
