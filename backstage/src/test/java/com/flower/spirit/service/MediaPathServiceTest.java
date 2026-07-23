package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.flower.spirit.platform.WorkMetadataValidationException;

class MediaPathServiceTest {

	private final MediaPathService service = new MediaPathService(Path.of("C:/media"), "/cos/**");

	@Test
	void mapsBetweenConfiguredLocalAndPublicRoots() {
		Path local = Path.of("C:/media/youtube/odd/work/video.mp4");

		assertThat(service.toPublicPath(local)).isEqualTo("/cos/youtube/odd/work/video.mp4");
		assertThat(service.toLocalPath("/cos/youtube/odd/work/video.mp4"))
				.isEqualTo(local.toAbsolutePath().normalize());
	}

	@Test
	void rejectsLocalPathsOutsideConfiguredStorage() {
		assertThatThrownBy(() -> service.toPublicPath(Path.of("C:/other/video.mp4")))
				.isInstanceOf(WorkMetadataValidationException.class)
				.hasMessageContaining("outside configured storage root");
	}

	@Test
	void validatesStoredPathsWithoutAllowingTheStorageRootItself() {
		assertThat(service.requireOwnedLocalPath("youtube/work/video.mp4"))
				.isEqualTo(Path.of("C:/media/youtube/work/video.mp4").toAbsolutePath().normalize());
		assertThatThrownBy(() -> service.requireOwnedLocalPath("C:/media"))
				.isInstanceOf(WorkMetadataValidationException.class);
		assertThatThrownBy(() -> service.requireOwnedLocalPath("C:/outside/video.mp4"))
				.isInstanceOf(WorkMetadataValidationException.class);
	}
}
