package com.flower.spirit.service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.alibaba.fastjson.JSON;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.config.Global;
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
	private final MediaPathService mediaPathService;

	public WorkRedownloadService(WorkRefreshService refreshService, MediaDownloadService mediaDownloadService,
			WorkPersistenceService persistenceService, WorkMetadataEditService editService,
			VideoDataDao videoDataDao, GraphicContentDao graphicContentDao,
			HlsTranscodeService hlsTranscodeService, MediaPathService mediaPathService) {
		this.refreshService = refreshService;
		this.mediaDownloadService = mediaDownloadService;
		this.persistenceService = persistenceService;
		this.editService = editService;
		this.videoDataDao = videoDataDao;
		this.graphicContentDao = graphicContentDao;
		this.hlsTranscodeService = hlsTranscodeService;
		this.mediaPathService = mediaPathService;
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public RedownloadResult redownload(WorkOperationRequest request) {
		PreparedWork prepared = refreshService.prepare(request, false);
		if ("a2".equalsIgnoreCase(Global.downtype) && ("douyin".equals(prepared.metadata().getPlatformKey())
				|| "bilibili".equals(prepared.metadata().getPlatformKey())
				|| "kuaishou".equals(prepared.metadata().getPlatformKey()))) {
			throw new WorkMetadataValidationException(
					"unified redownload is unavailable for Aria2; use the legacy platform redownload endpoint");
		}
		Path target = targetDirectory(prepared);
		Path candidate = target.resolveSibling("." + target.getFileName() + ".candidate-" + UUID.randomUUID());
		DownloadOutcome download = mediaDownloadService.download(prepared.adapter(), prepared.metadata(),
				new WorkDownloadRequest(candidate, false));
		if (download.status() == DownloadResult.Status.QUEUED) {
			return RedownloadResult.queued(download.message(), download.workingDirectory());
		}
		boolean targetExisted = Files.exists(target);
		Path backup = null;
		try {
			if (targetExisted) {
				copySidecars(target, candidate, prepared);
				Path proposedBackup = target.resolveSibling("." + target.getFileName()
						+ ".redownload-backup-" + UUID.randomUUID());
				backup = proposedBackup;
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
			} else {
				editService.reapplyStoredOverrides(persistence.graphic());
				graphicContentDao.save(persistence.graphic());
			}
			completeAfterCommit(target, backup, targetExisted,
					persistence.video() == null ? null : persistence.id());
			return RedownloadResult.completed(persistence, target);
		} catch (RuntimeException | IOException e) {
			restore(target, backup, targetExisted);
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

	private void copySidecars(Path source, Path candidate, PreparedWork prepared) throws IOException {
		Set<Path> media = existingMediaPaths(source, prepared);
		try (var paths = Files.walk(source)) {
			for (Path path : paths.sorted().toList()) {
				Path relative = source.relativize(path);
				if (relative.toString().isEmpty() || media.contains(relative)) continue;
				Path destination = candidate.resolve(relative);
				if (Files.isDirectory(path)) {
					Files.createDirectories(destination);
				} else if (!Files.exists(destination)) {
					Files.createDirectories(destination.getParent());
					Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
		}
	}

	private Set<Path> existingMediaPaths(Path target, PreparedWork prepared) {
		Set<Path> paths = new HashSet<>();
		if (prepared.video() != null) {
			addRelative(paths, target, path(prepared.video().getVideoaddr()));
		} else if (prepared.graphic() != null && prepared.graphic().getImages() != null) {
			try {
				for (String value : JSON.parseArray(prepared.graphic().getImages(), String.class)) {
					Path local = mediaPathService.toLocalPath(value);
					if (local == null) local = path(value);
					addRelative(paths, target, local);
				}
			} catch (RuntimeException ignored) {
			}
		}
		return paths;
	}

	private void addRelative(Set<Path> paths, Path target, Path value) {
		if (value == null) return;
		Path normalized = value.toAbsolutePath().normalize();
		if (normalized.startsWith(target)) paths.add(target.relativize(normalized));
	}

	private Path path(String value) {
		try { return value == null || value.trim().isEmpty() ? null : Path.of(value); }
		catch (RuntimeException ignored) { return null; }
	}

	private void completeAfterCommit(Path target, Path backup, boolean targetExisted, Integer videoId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			deleteRecursively(backup);
			if (videoId != null) hlsTranscodeService.enqueueVideo(videoId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override public void afterCommit() {
				deleteRecursively(backup);
				if (videoId != null) hlsTranscodeService.enqueueVideo(videoId);
			}
			@Override public void afterCompletion(int status) {
				if (status != TransactionSynchronization.STATUS_COMMITTED) {
					restore(target, backup, targetExisted);
				}
			}
		});
	}

	private void restore(Path target, Path backup, boolean targetExisted) {
		try {
			if (backup != null && Files.exists(backup)) {
				deleteRecursively(target);
				move(backup, target);
			} else if (!targetExisted) {
				deleteRecursively(target);
			}
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
