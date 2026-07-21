package com.flower.spirit.service;

import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import java.util.function.Function;

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
		return ingest(input, metadata -> outputDirectory, replaceExisting, historyId);
	}

	public IngestResult ingest(String input, Function<WorkMetadata, Path> outputDirectoryResolver,
			boolean replaceExisting, Integer historyId) {
		if (outputDirectoryResolver == null) {
			throw new IllegalArgumentException("output directory resolver is required");
		}
		String stage = "RECOGNIZING";
		try {
			PlatformResolver.Resolution resolution = resolver.resolveRequired(input);
			PlatformWorkAdapter adapter = adapterRegistry.requireByPlatformKey(resolution.platform().getKey());
			stage = "PARSING";
			processHistoryService.recordPlatformStage(historyId, stage);
			List<WorkMetadata> works = adapter.parseAll(new WorkParseRequest(input, resolution.url(), false)).stream()
					.map(normalizer::normalize).toList();
			if (works.isEmpty()) throw new IllegalArgumentException("adapter returned no works");
			stage = "INGESTING";
			IngestResult result = null;
			for (int i = 0; i < works.size(); i++) {
				result = ingestParsed(adapter, works.get(i), outputDirectoryResolver, replaceExisting, historyId,
						i == works.size() - 1);
			}
			return result;
		} catch (RuntimeException e) {
			processHistoryService.failPlatformProcess(historyId, stage, e.getMessage());
			throw e;
		}
	}

	private IngestResult ingestParsed(PlatformWorkAdapter adapter, WorkMetadata metadata,
			Function<WorkMetadata, Path> outputDirectoryResolver, boolean replaceExisting, Integer historyId,
			boolean lastWork) {
		String stage = "DEDUPLICATING";
		DownloadOutcome download = null;
		boolean persisted = false;
		try {
			Optional<PersistenceResult> existing = persistenceService.findExisting(metadata);
			if (existing.isPresent()) {
				processHistoryService.recordPlatformStage(historyId, "DUPLICATE");
				if (lastWork) processHistoryService.completePlatformProcess(historyId);
				return IngestResult.duplicate(historyId, metadata, existing.get());
			}
			Path outputDirectory = outputDirectoryResolver.apply(metadata);
			if (outputDirectory == null) {
				throw new IllegalArgumentException("output directory resolver returned null");
			}

			stage = "DOWNLOADING";
			processHistoryService.recordPlatformStage(historyId, stage);
			download = mediaDownloadService.download(adapter, metadata,
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
			persisted = true;
			mediaDownloadService.commit(download);
			stage = "POST_PROCESSING";
			processHistoryService.recordPlatformStage(historyId, stage);
			postProcessingService.complete(historyId, downloadedMetadata, persistence, lastWork);
			return IngestResult.completed(historyId, downloadedMetadata, persistence, download.workingDirectory());
		} catch (RuntimeException e) {
			if (!persisted) mediaDownloadService.rollback(download);
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

		public static IngestResult duplicate(Integer historyId, WorkMetadata metadata,
				PersistenceResult persistence) {
			return new IngestResult(DownloadResult.Status.COMPLETED, historyId, metadata, persistence,
					"work already exists", null);
		}
	}
}
