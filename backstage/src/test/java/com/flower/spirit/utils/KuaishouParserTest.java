package com.flower.spirit.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class KuaishouParserTest {

	@Test
	void parsesCanonicalWorkAuthorAndHighestH264FallbackFields() throws Exception {
		String source = "https://www.kuaishou.com/short-video/ks-video-1";
		KuaishouParser.VideoInfo result = KuaishouParser.parseResponse(fixture(), source);

		assertThat(result.getVideoId()).isEqualTo("ks-video-1");
		assertThat(result.getVideoUrl()).isEqualTo("https://media.example/ks-h264-1080.mp4");
		assertThat(result.getH265Url()).isEqualTo("https://media.example/ks-h265.mp4");
		assertThat(result.getAuthorAvatar()).isEqualTo("https://media.example/ks-avatar.jpg");
		assertThat(result.getAuthorHomepage()).isEqualTo("https://www.kuaishou.com/profile/ks-author-1");
		assertThat(result.getSourceUrl()).isEqualTo(source);
	}

	private String fixture() throws IOException {
		try (var stream = getClass().getResourceAsStream("/platform/kuaishou/video.json")) {
			if (stream == null) throw new IOException("fixture not found");
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
