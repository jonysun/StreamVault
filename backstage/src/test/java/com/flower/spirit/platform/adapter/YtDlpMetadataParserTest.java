package com.flower.spirit.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.flower.spirit.platform.PlatformSupportTier;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;

class YtDlpMetadataParserTest {

	private final YtDlpMetadataParser parser = new YtDlpMetadataParser();

	@Test
	void parsesYouTubeMetadataWithoutConfusingWorkAndAuthorUrls() throws Exception {
		WorkMetadata metadata = parse("youtube-ordinary.json", "shared text");

		assertThat(metadata.getPlatformKey()).isEqualTo("youtube");
		assertThat(metadata.getSupportTier()).isEqualTo(PlatformSupportTier.FORMAL);
		assertThat(metadata.getWorkId()).isEqualTo("vid-ordinary");
		assertThat(metadata.getSourceUrl()).isEqualTo("https://www.youtube.com/watch?v=vid-ordinary");
		assertThat(metadata.getAuthorHomepage()).isEqualTo("https://www.youtube.com/@fixture");
		assertThat(metadata.getOriginalAddress()).isEqualTo("shared text");
		assertThat(metadata.getPublishTime()).isEqualTo("1710000000");
		assertThat(metadata.getMediaResources()).singleElement().satisfies(resource -> {
			assertThat(resource.getType()).isEqualTo(WorkMediaResource.Type.VIDEO);
			assertThat(resource.getRequestHeaders()).containsEntry("User-Agent", "fixture-agent");
			assertThat(resource.getRequestHeaders()).doesNotContainKey("Cookie");
		});
	}

	@Test
	void parsesShortsAsOrdinaryYouTubeVideoWork() throws Exception {
		WorkMetadata metadata = parse("youtube-shorts.json", "https://youtube.com/shorts/short-1");

		assertThat(metadata.getPlatformKey()).isEqualTo("youtube");
		assertThat(metadata.getWorkId()).isEqualTo("short-1");
		assertThat(metadata.getPublishTime()).isEqualTo("20250102");
	}

	@Test
	void keepsDashVideoAndAudioPairedInOrder() throws Exception {
		WorkMetadata metadata = parse("youtube-dash.json", "dash input");

		assertThat(metadata.getMediaResources()).extracting(WorkMediaResource::getType)
				.containsExactly(WorkMediaResource.Type.VIDEO, WorkMediaResource.Type.AUDIO);
		assertThat(metadata.getMediaResources()).extracting(WorkMediaResource::getOrder).containsExactly(0, 1);
	}

	@Test
	void createsSanitizedGenericPlatformWithMissingOptionalFields() throws Exception {
		WorkMetadata metadata = parse("generic-missing-optional.json", "generic input");

		assertThat(metadata.getPlatformKey()).isEqualTo("vimeo_on_demand");
		assertThat(metadata.getPlatformDisplayName()).isEqualTo("Vimeo On Demand");
		assertThat(metadata.getSupportTier()).isEqualTo(PlatformSupportTier.GENERIC);
		assertThat(metadata.getAuthorName()).isNull();
		assertThat(metadata.getMediaResources()).singleElement();
	}

	@Test
	void rejectsMultipleObjectsPlaylistsLiveStreamsAndAudioOnlyEntries() throws Exception {
		assertThatThrownBy(() -> parse("multiple-json-lines.jsonl", "input"))
				.isInstanceOf(WorkMetadataValidationException.class).hasMessageContaining("exactly one");
		assertThatThrownBy(() -> parse("playlist.json", "input"))
				.isInstanceOf(WorkMetadataValidationException.class).hasMessageContaining("playlists");
		assertThatThrownBy(() -> parser.parseSingle(
				"{\"id\":\"live\",\"extractor_key\":\"Youtube\",\"is_live\":true}", "input", "https://youtube.com/live"))
				.isInstanceOf(WorkMetadataValidationException.class).hasMessageContaining("live");
		assertThatThrownBy(() -> parser.parseSingle(
				"{\"id\":\"audio\",\"extractor\":\"Example\",\"webpage_url\":\"https://example.com/audio\",\"url\":\"https://media.example/audio.m4a\",\"ext\":\"m4a\",\"vcodec\":\"none\",\"acodec\":\"aac\"}",
				"input", "https://example.com/audio"))
				.isInstanceOf(WorkMetadataValidationException.class).hasMessageContaining("video");
	}

	private WorkMetadata parse(String fixture, String input) throws IOException {
		return parser.parseSingle(resource(fixture), input, "https://request.example/work");
	}

	private String resource(String fixture) throws IOException {
		try (var stream = getClass().getResourceAsStream("/platform/ytdlp/" + fixture)) {
			if (stream == null) throw new IOException("fixture not found: " + fixture);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
