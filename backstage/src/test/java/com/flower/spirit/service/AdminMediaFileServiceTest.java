package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.WorkMetadataValidationException;

class AdminMediaFileServiceTest {

	Path mediaRoot;

	@BeforeEach
	void setUp() throws Exception {
		mediaRoot = Path.of("target", "test-media", UUID.randomUUID().toString()).toAbsolutePath().normalize();
		Files.createDirectories(mediaRoot);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (!Files.exists(mediaRoot)) {
			return;
		}
		try (var paths = Files.walk(mediaRoot)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	@Test
	void deletesVideoDirectoryIncludingHlsArtifacts() throws Exception {
		Path workDir = Files.createDirectories(mediaRoot.resolve("douyin/work-1/hls"));
		Path video = workDir.getParent().resolve("video.mp4");
		Files.writeString(video, "video");
		Files.writeString(workDir.resolve("index.m3u8"), "playlist");
		AdminMediaFileService service = new AdminMediaFileService(new MediaPathService(mediaRoot, "/cos"));
		VideoDataEntity entity = new VideoDataEntity();
		entity.setVideoaddr(video.toString());

		service.deleteVideoMedia(entity);

		assertThat(workDir.getParent()).doesNotExist();
	}

	@Test
	void refusesGraphicDirectoryOutsideConfiguredRoot() {
		AdminMediaFileService service = new AdminMediaFileService(new MediaPathService(mediaRoot, "/cos"));
		GraphicContentEntity entity = new GraphicContentEntity();
		entity.setMarkroute(mediaRoot.resolveSibling("outside").toString());

		assertThatThrownBy(() -> service.deleteGraphicMedia(entity))
				.isInstanceOf(WorkMetadataValidationException.class)
				.hasMessageContaining("outside configured storage root");
	}
}
