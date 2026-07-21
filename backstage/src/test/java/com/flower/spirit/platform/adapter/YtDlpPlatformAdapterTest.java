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
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.platform.WorkParseRequest;

class YtDlpPlatformAdapterTest {

	@TempDir Path tempDir;

	private final YtDlpMetadataParser parser = new YtDlpMetadataParser();
	private final PlatformResolver resolver = new PlatformResolver();

	@Test
	void youtubeAdapterOwnsWatchAndShortsButGenericOwnsOnlyUnknownDomains() throws Exception {
		FakeGateway gateway = new FakeGateway(resource("youtube-ordinary.json"));
		YtDlpPlatformAdapter youtube = adapter("youtube", gateway);
		YtDlpPlatformAdapter generic = adapter("generic", gateway);

		assertThat(youtube.supports("https://youtube.com/watch?v=vid-ordinary")).isTrue();
		assertThat(youtube.supports("https://youtu.be/vid-ordinary")).isTrue();
		assertThat(youtube.supports("https://vimeo.com/1")).isFalse();
		assertThat(generic.supports("https://vimeo.com/1")).isTrue();
		assertThat(generic.supports("https://youtube.com/watch?v=1")).isFalse();
	}

	@Test
	void parseUsesMetadataProbeOnlyAndPreservesOriginalSharedInput() throws Exception {
		FakeGateway gateway = new FakeGateway(resource("youtube-ordinary.json"));
		YtDlpPlatformAdapter youtube = adapter("youtube", gateway);

		WorkMetadata metadata = youtube.parse(new WorkParseRequest("shared YouTube text",
				"https://youtube.com/watch?v=vid-ordinary", true));

		assertThat(metadata.getWorkId()).isEqualTo("vid-ordinary");
		assertThat(metadata.getOriginalAddress()).isEqualTo("shared YouTube text");
		assertThat(gateway.metadataCalls).isEqualTo(1);
		assertThat(gateway.downloadCalls).isZero();
		assertThat(gateway.lastPlatform).isEqualTo("youtube");
	}

	@Test
	void downloadReturnsOnlyLocalMergedVideoResources() throws Exception {
		FakeGateway gateway = new FakeGateway(resource("youtube-ordinary.json"));
		YtDlpPlatformAdapter youtube = adapter("youtube", gateway);
		WorkMetadata metadata = youtube.parse(new WorkParseRequest("input",
				"https://youtube.com/watch?v=vid-ordinary", false));

		DownloadResult result = youtube.download(metadata, new WorkDownloadRequest(tempDir, false));

		assertThat(result.getStatus()).isEqualTo(DownloadResult.Status.COMPLETED);
		assertThat(result.getMediaResources()).singleElement().satisfies(resource -> {
			assertThat(resource.getLocalPath()).isEqualTo(tempDir.resolve("vid-ordinary.mp4"));
			assertThat(resource.getExpectedExtension()).isEqualTo("mp4");
		});
		assertThat(gateway.downloadCalls).isEqualTo(1);
	}

	@Test
	void genericAdapterUsesExtractorIdentityAndCannotBypassFormalOwnership() throws Exception {
		FakeGateway genericGateway = new FakeGateway(resource("generic-missing-optional.json"));
		WorkMetadata generic = adapter("generic", genericGateway).parse(new WorkParseRequest("input",
				"https://vimeo.example/videos/generic-1", true));
		assertThat(generic.getPlatformKey()).isEqualTo("vimeo_on_demand");
		assertThat(genericGateway.lastPlatform).isNull();

		FakeGateway youtubeGateway = new FakeGateway(resource("youtube-ordinary.json"));
		assertThatThrownBy(() -> adapter("generic", youtubeGateway).parse(new WorkParseRequest("input",
				"https://unknown.example/redirect", true)))
				.isInstanceOf(WorkMetadataValidationException.class)
				.hasMessageContaining("cannot own a formal");
	}

	private YtDlpPlatformAdapter adapter(String key, FakeGateway gateway) {
		return new YtDlpPlatformAdapter(key, parser, resolver, gateway);
	}

	private String resource(String fixture) throws IOException {
		try (var stream = getClass().getResourceAsStream("/platform/ytdlp/" + fixture)) {
			if (stream == null) throw new IOException("fixture not found: " + fixture);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static class FakeGateway implements YtDlpPlatformAdapter.Gateway {
		private final String metadata;
		private int metadataCalls;
		private int downloadCalls;
		private String lastPlatform;

		private FakeGateway(String metadata) {
			this.metadata = metadata;
		}

		@Override
		public String metadata(String url, String platform) {
			metadataCalls++;
			lastPlatform = platform;
			return metadata;
		}

		@Override
		public List<Path> download(String url, Path outputDirectory, String platform) throws IOException {
			downloadCalls++;
			lastPlatform = platform;
			Files.createDirectories(outputDirectory);
			return List.of(Files.writeString(outputDirectory.resolve("vid-ordinary.mp4"), "video"));
		}
	}
}
