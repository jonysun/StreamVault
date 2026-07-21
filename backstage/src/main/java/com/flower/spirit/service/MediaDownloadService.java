package com.flower.spirit.service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.platform.adapter.PlatformWorkAdapter;

@Service
public class MediaDownloadService {

	public DownloadOutcome download(PlatformWorkAdapter adapter, WorkMetadata metadata, WorkDownloadRequest request) {
		if (adapter == null || metadata == null || request == null) {
			throw new IllegalArgumentException("adapter, metadata and download request are required");
		}
		Path target = request.getOutputDirectory().toAbsolutePath().normalize();
		Path parent = target.getParent();
		if (parent == null) {
			throw new WorkMetadataValidationException("download output directory must have a parent");
		}
		Path staging = parent.resolve("." + target.getFileName() + ".staging-" + UUID.randomUUID()).normalize();
		try {
			Files.createDirectories(parent);
			Files.createDirectory(staging);
			DownloadResult result = adapter.download(metadata,
					new WorkDownloadRequest(staging, request.isReplaceExisting()));
			if (result == null) {
				throw new WorkMetadataValidationException("adapter returned no download result");
			}
			if (result.getStatus() == DownloadResult.Status.QUEUED) {
				return DownloadOutcome.queued(result.getMessage(), staging);
			}
			if (result.getStatus() != DownloadResult.Status.COMPLETED) {
				throw new WorkMetadataValidationException(messageOrDefault(result.getMessage(), "download failed"));
			}
			adapter.postProcessDownloaded(metadata, staging, result.getMediaResources());
			List<ResourcePath> verified = verifyResources(result.getMediaResources(), staging);
			Promotion promotion = promote(staging, target, request.isReplaceExisting());
			List<WorkMediaResource> promoted = verified.stream()
					.map(resource -> copyWithLocalPath(resource.resource(), target.resolve(resource.relativePath())))
					.sorted(Comparator.comparingInt(WorkMediaResource::getOrder))
					.toList();
			return DownloadOutcome.completed(promoted, target, promotion.backup(), promotion.targetPreviouslyExisted());
		} catch (RuntimeException | IOException e) {
			deleteRecursively(staging);
			if (e instanceof WorkMetadataValidationException validationException) {
				throw validationException;
			}
			throw new WorkMetadataValidationException("media download failed: " + e.getMessage(), e);
		}
	}

	private List<ResourcePath> verifyResources(List<WorkMediaResource> resources, Path staging) throws IOException {
		if (resources == null || resources.isEmpty()) {
			throw new WorkMetadataValidationException("completed download returned no media resources");
		}
		List<ResourcePath> verified = new ArrayList<>();
		for (WorkMediaResource resource : resources) {
			if (resource == null || resource.getLocalPath() == null) {
				throw new WorkMetadataValidationException("completed media resource has no local path");
			}
			Path local = resource.getLocalPath().toAbsolutePath().normalize();
			if (!local.startsWith(staging)) {
				throw new WorkMetadataValidationException("downloaded media must remain inside the staging directory");
			}
			if (!Files.isRegularFile(local) || Files.size(local) <= 0) {
				throw new WorkMetadataValidationException("downloaded media is missing or empty: " + local.getFileName());
			}
			verified.add(new ResourcePath(resource, staging.relativize(local)));
		}
		return verified;
	}

	public void commit(DownloadOutcome outcome) {
		if (outcome != null && outcome.status() == DownloadResult.Status.COMPLETED) {
			deleteRecursively(outcome.backupDirectory());
		}
	}

	public void rollback(DownloadOutcome outcome) {
		if (outcome == null || outcome.status() != DownloadResult.Status.COMPLETED) return;
		try {
			restorePromotion(outcome.workingDirectory(), outcome.backupDirectory(),
					outcome.targetPreviouslyExisted());
		} catch (IOException ignored) {
		}
	}

	private Promotion promote(Path staging, Path target, boolean replaceExisting) throws IOException {
		if (!Files.exists(target)) {
			move(staging, target);
			return new Promotion(null, false);
		}
		if (!replaceExisting) {
			throw new WorkMetadataValidationException("download target already exists: " + target);
		}
		Path backup = target.resolveSibling("." + target.getFileName() + ".backup-" + UUID.randomUUID());
		try {
			move(target, backup);
			move(staging, target);
			return new Promotion(backup, true);
		} catch (IOException | RuntimeException e) {
			restorePromotion(target, backup, true);
			throw e;
		}
	}

	private void restorePromotion(Path target, Path backup, boolean targetPreviouslyExisted) throws IOException {
		if (backup != null && Files.exists(backup)) {
			deleteRecursively(target);
			move(backup, target);
		} else if (!targetPreviouslyExisted) {
			deleteRecursively(target);
		}
	}

	private void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target);
		}
	}

	private WorkMediaResource copyWithLocalPath(WorkMediaResource source, Path localPath) {
		return new WorkMediaResource(source.getOrder(), source.getType(), source.getSourceUrl(), localPath,
				source.getExpectedExtension(), source.getRequestHeaders());
	}

	private void deleteRecursively(Path root) {
		if (root == null || !Files.exists(root)) {
			return;
		}
		try (var paths = Files.walk(root)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}

	private static String messageOrDefault(String message, String fallback) {
		return message == null || message.trim().isEmpty() ? fallback : message.trim();
	}

	private record ResourcePath(WorkMediaResource resource, Path relativePath) {
	}

	private record Promotion(Path backup, boolean targetPreviouslyExisted) {
	}

	public record DownloadOutcome(DownloadResult.Status status, List<WorkMediaResource> mediaResources,
			String message, Path workingDirectory, Path backupDirectory, boolean targetPreviouslyExisted) {

		public static DownloadOutcome completed(List<WorkMediaResource> resources, Path directory) {
			return completed(resources, directory, null, false);
		}

		private static DownloadOutcome completed(List<WorkMediaResource> resources, Path directory,
				Path backupDirectory, boolean targetPreviouslyExisted) {
			return new DownloadOutcome(DownloadResult.Status.COMPLETED, List.copyOf(resources), null, directory,
					backupDirectory, targetPreviouslyExisted);
		}

		public static DownloadOutcome queued(String message, Path directory) {
			return new DownloadOutcome(DownloadResult.Status.QUEUED, List.of(), message, directory, null, false);
		}
	}
}
