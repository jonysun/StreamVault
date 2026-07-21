package com.flower.spirit.service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.dto.WorkOperationRequest;
import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.service.MediaDownloadService.DownloadOutcome;
import com.flower.spirit.service.WorkPersistenceService.PersistenceResult;
import com.flower.spirit.service.WorkRefreshService.PreparedWork;

@Service
public class WorkRedownloadService {

	private final WorkRefreshService refreshService;
	private final MediaDownloadService mediaDownloadService;
	private final WorkPersistenceService persistenceService;
	private final WorkMetadataEditService editService;
	private final VideoDataDao videoDataDao;
	private final GraphicContentDao graphicContentDao;
	private final HlsTranscodeService hlsTranscodeService;

	public WorkRedownloadService(WorkRefreshService refreshService, MediaDownloadService mediaDownloadService,
			WorkPersistenceService persistenceService, WorkMetadataEditService editService,
			VideoDataDao videoDataDao, GraphicContentDao graphicContentDao,
			HlsTranscodeService hlsTranscodeService) {
		this.refreshService = refreshService;
		this.mediaDownloadService = mediaDownloadService;
		this.persistenceService = persistenceService;
		this.editService = editService;
		this.videoDataDao = videoDataDao;
		this.graphicContentDao = graphicContentDao;
		this.hlsTranscodeService = hlsTranscodeService;
	}

	@Transactional
	public RedownloadResult redownload(WorkOperationRequest request) {
		PreparedWork prepared = refreshService.prepare(request, false);
		Path target = targetDirectory(prepared);
		Path candidate = target.resolveSibling("." + target.getFileName() + ".candidate-" + UUID.randomUUID());
		DownloadOutcome download = mediaDownloadService.download(prepared.adapter(), prepared.metadata(),
				new WorkDownloadRequest(candidate, false));
		if (download.status() == DownloadResult.Status.QUEUED) {
			return RedownloadResult.queued(download.message(), download.workingDirectory());
		}
		Path backup = null;
		try {
			if (Files.exists(target)) {
				backup = target.resolveSibling("." + target.getFileName() + ".redownload-backup-" + UUID.randomUUID());
				move(target, backup);
			}
			move(candidate, target);
			WorkMetadata replacement = copyWithTargetPaths(prepared.metadata(), download.mediaResources(),
					download.workingDirectory(), target);
			PersistenceResult persistence = persistenceService.persist(replacement);
			if (!request.getId().equals(persistence.id())) {
				throw new WorkMetadataValidationException("redownload must update the existing work row");
			}
			if (persistence.video() != null) {
				editService.reapplyStoredOverrides(persistence.video());
				videoDataDao.save(persistence.video());
				hlsTranscodeService.enqueueVideo(persistence.id());
			} else {
				editService.reapplyStoredOverrides(persistence.graphic());
				graphicContentDao.save(persistence.graphic());
			}
			deleteRecursively(backup);
			return RedownloadResult.completed(persistence, target);
		} catch (RuntimeException | IOException e) {
			restore(target, backup);
			deleteRecursively(candidate);
			if (e instanceof WorkMetadataValidationException validationException) throw validationException;
			throw new WorkMetadataValidationException("redownload failed: " + e.getMessage(), e);
		}
	}

	private Path targetDirectory(PreparedWork prepared) {
		String path = prepared.video() != null ? prepared.video().getVideoaddr() : prepared.graphic().getMarkroute();
		if (path == null || path.trim().isEmpty()) {
			throw new WorkMetadataValidationException("work has no local media directory");
		}
		Path value = Path.of(path).toAbsolutePath().normalize();
		if (prepared.video() != null) {
			value = value.getParent();
		}
		if (value == null) throw new WorkMetadataValidationException("work local media directory is invalid");
		return value;
	}

	private WorkMetadata copyWithTargetPaths(WorkMetadata metadata, List<WorkMediaResource> resources,
			Path candidate, Path target) {
		List<WorkMediaResource> remapped = resources.stream().map(resource -> {
			Path relative = candidate.toAbsolutePath().normalize().relativize(resource.getLocalPath().toAbsolutePath().normalize());
			return new WorkMediaResource(resource.getOrder(), resource.getType(), resource.getSourceUrl(),
					target.resolve(relative), resource.getExpectedExtension(), resource.getRequestHeaders());
		}).toList();
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
				.mediaResources(remapped)
				.rawMetadata(metadata.getRawMetadata())
				.build();
	}

	private void restore(Path target, Path backup) {
		try {
			deleteRecursively(target);
			if (backup != null && Files.exists(backup)) move(backup, target);
		} catch (IOException ignored) {
		}
	}

	private void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target);
		}
	}

	private void deleteRecursively(Path root) {
		if (root == null || !Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try { Files.deleteIfExists(path); } catch (IOException ignored) { }
			});
		} catch (IOException ignored) {
		}
	}

	public record RedownloadResult(DownloadResult.Status status, PersistenceResult persistence,
			String message, Path workingDirectory) {

		public static RedownloadResult queued(String message, Path directory) {
			return new RedownloadResult(DownloadResult.Status.QUEUED, null, message, directory);
		}

		public static RedownloadResult completed(PersistenceResult persistence, Path directory) {
			return new RedownloadResult(DownloadResult.Status.COMPLETED, persistence, null, directory);
		}
	}
}
