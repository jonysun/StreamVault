package com.flower.spirit.service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.springframework.stereotype.Service;

import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.WorkMetadataValidationException;

@Service
public class AdminMediaFileService {

	private final MediaPathService mediaPathService;

	public AdminMediaFileService(MediaPathService mediaPathService) {
		this.mediaPathService = mediaPathService;
	}

	public void deleteVideoMedia(VideoDataEntity video) {
		if (video == null || !hasText(video.getVideoaddr())) {
			return;
		}
		Path stored = mediaPathService.requireOwnedLocalPath(video.getVideoaddr());
		Path target = Files.isDirectory(stored) ? stored : stored.getParent();
		deleteOwnedTree(target);
	}

	public void deleteGraphicMedia(GraphicContentEntity graphic) {
		if (graphic == null || !hasText(graphic.getMarkroute())) {
			return;
		}
		deleteOwnedTree(mediaPathService.requireOwnedLocalPath(graphic.getMarkroute()));
	}

	void deleteOwnedTree(Path target) {
		if (target == null) {
			throw new WorkMetadataValidationException("media directory is missing");
		}
		Path owned = mediaPathService.requireOwnedLocalPath(target.toString());
		if (!Files.exists(owned)) {
			return;
		}
		try {
			Files.walkFileTree(owned, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
					if (error != null) {
						throw error;
					}
					Files.deleteIfExists(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			throw new WorkMetadataValidationException("failed to delete media directory: " + owned, e);
		}
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
}
