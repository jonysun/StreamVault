package com.flower.spirit.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class PublicVideoTemplateTest {

	@Test
	void playlistResultsUseCheckboxesAndResolveOnlySelectedFlatEntries() throws IOException {
		String template = template();

		assertThat(template).contains("video-item-checkbox")
				.contains("result.videos.length > 0")
				.contains("setVideoSelection(index, checkbox.checked)")
				.contains("async function downloadSelectedVideos()")
				.contains("async function resolvePlaylistVideo(video)")
				.contains("formData.append('video', video.sourceUrl)");
	}

	private String template() throws IOException {
		try (var input = getClass().getResourceAsStream("/templates/video.html")) {
			if (input == null) throw new IOException("video template not found");
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
