package com.flower.spirit.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.platform.WorkParseRequest;

class SocialYtDlpPlatformAdapterTest {

	@TempDir Path tempDir;
	private final YtDlpMetadataParser parser = new YtDlpMetadataParser();
	private final PlatformResolver resolver = new PlatformResolver();

	@Test
	void twitterTreatsXAndTwitterStatusAsOneOrderedMultiVideoWork() throws Exception {
		TwitterGateway gateway = new TwitterGateway(resource("twitter/multi-video.json"));
		TwitterPlatformAdapter adapter = new TwitterPlatformAdapter(resolver, parser, gateway);

		WorkMetadata metadata = adapter.parse(new WorkParseRequest("shared tweet",
				"https://x.com/twitter-author/status/1900000000000000000", true));

		assertThat(adapter.supports("https://twitter.com/a/status/1")).isTrue();
		assertThat(adapter.supports("https://x.com/a/status/1")).isTrue();
		assertThat(metadata.getContentType()).isEqualTo(WorkContentType.MIXED);
		assertThat(metadata.getSourceUrl()).isEqualTo(
				"https://x.com/twitter-author/status/1900000000000000000");
		assertThat(metadata.getMediaResources()).extracting(WorkMediaResource::getSourceUrl)
				.containsExactly("https://media.example/twitter-1.mp4", "https://media.example/twitter-2.mp4");

		DownloadResult result = adapter.download(metadata, new WorkDownloadRequest(tempDir, false));
		assertThat(result.getMediaResources()).extracting(item -> item.getLocalPath().getFileName().toString())
				.containsExactly("1900000000000000000-index-0.mp4", "1900000000000000000-index-1.mp4");
	}

	@Test
	void instagramAcceptsReelAndRejectsCarousel() throws Exception {
		InstagramPlatformAdapter reel = new InstagramPlatformAdapter(parser, resolver,
				new MetadataGateway(resource("instagram/reel.json")));
		InstagramPlatformAdapter carousel = new InstagramPlatformAdapter(parser, resolver,
				new MetadataGateway(resource("instagram/carousel.json")));

		WorkMetadata metadata = reel.parse(new WorkParseRequest("input",
				"https://www.instagram.com/reel/insta-reel-1/", true));
		assertThat(metadata.getPlatformKey()).isEqualTo("instagram");
		assertThat(metadata.getContentType()).isEqualTo(WorkContentType.VIDEO);
		assertThatThrownBy(() -> carousel.parse(new WorkParseRequest("input",
				"https://www.instagram.com/p/insta-carousel-1/", true)))
				.isInstanceOf(WorkMetadataValidationException.class).hasMessageContaining("collections");
	}

	@Test
	void tiktokAcceptsVideoAndRejectsPhotoModeWithoutPartialMedia() throws Exception {
		TikTokPlatformAdapter video = new TikTokPlatformAdapter(parser, resolver,
				new MetadataGateway(resource("tiktok/video.json")));
		TikTokPlatformAdapter photo = new TikTokPlatformAdapter(parser, resolver,
				new MetadataGateway(resource("tiktok/photo-mode.json")));

		WorkMetadata metadata = video.parse(new WorkParseRequest("input",
				"https://www.tiktok.com/@tt-author/video/tiktok-video-1", true));
		assertThat(metadata.getPlatformKey()).isEqualTo("tiktok");
		assertThat(metadata.getMediaResources()).singleElement();
		assertThatThrownBy(() -> photo.parse(new WorkParseRequest("input",
				"https://www.tiktok.com/@tt-author/photo/tiktok-photo-1", true)))
				.isInstanceOf(WorkMetadataValidationException.class).hasMessageContaining("collections");
	}

	private String resource(String fixture) throws IOException {
		try (var stream = getClass().getResourceAsStream("/platform/" + fixture)) {
			if (stream == null) throw new IOException("fixture not found: " + fixture);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static class MetadataGateway implements YtDlpPlatformAdapter.Gateway {
		private final String metadata;
		private MetadataGateway(String metadata) { this.metadata = metadata; }
		@Override public String metadata(String url, String platform) { return metadata; }
		@Override public List<Path> download(String url, Path outputDirectory, String platform) throws IOException {
			Files.createDirectories(outputDirectory);
			return List.of(Files.writeString(outputDirectory.resolve("video.mp4"), "video"));
		}
	}

	private static class TwitterGateway implements TwitterPlatformAdapter.Gateway {
		private final String metadata;
		private TwitterGateway(String metadata) { this.metadata = metadata; }
		@Override public String metadata(String url) { return metadata; }
		@Override public Path download(String url, Path destination, WorkMediaResource source) throws IOException {
			Files.createDirectories(destination.getParent());
			return Files.writeString(destination, "video");
		}
	}
}
