package com.flower.spirit.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class WorkMetadataNormalizerTest {

	private final WorkMetadataNormalizer normalizer = new WorkMetadataNormalizer(ZoneId.of("UTC"));

	@ParameterizedTest
	@CsvSource({
			"1704067200, 2024-01-01 00:00:00",
			"1704067200000, 2024-01-01 00:00:00",
			"20240102, 2024-01-02 00:00:00",
			"2024-01-02T03:04:05Z, 2024-01-02 03:04:05",
			"2024-01-02T03:04:05+08:00, 2024-01-01 19:04:05",
			"2024/01/02 03:04:05, 2024-01-02 03:04:05"
	})
	void normalizesSupportedPublishTimeFormats(String input, String expected) {
		WorkMetadata normalized = normalizer.normalize(formalMetadata(input, "work-1", videoResource()));

		assertThat(normalized.getPublishTime()).isEqualTo(expected);
	}

	@Test
	void preservesDistinctSourceAndAuthorHomepageFields() {
		WorkMetadata input = WorkMetadata.builder()
				.platform(PlatformCatalog.requireByKey("youtube"))
				.workId("work-1")
				.contentType(WorkContentType.VIDEO)
				.sourceUrl("https://www.youtube.com/watch?v=work-1")
				.authorHomepage("https://www.youtube.com/@author")
				.mediaResources(List.of(videoResource()))
				.build();

		WorkMetadata normalized = normalizer.normalize(input);

		assertThat(normalized.getSourceUrl()).isEqualTo("https://www.youtube.com/watch?v=work-1");
		assertThat(normalized.getAuthorHomepage()).isEqualTo("https://www.youtube.com/@author");
	}

	@Test
	void leavesMissingOptionalMetadataEmptyWithoutFallbacks() {
		WorkMetadata normalized = normalizer.normalize(formalMetadata(null, "work-1", videoResource()));

		assertThat(normalized.getTitle()).isNull();
		assertThat(normalized.getDescription()).isNull();
		assertThat(normalized.getAuthorName()).isNull();
		assertThat(normalized.getAuthorHomepage()).isNull();
		assertThat(normalized.getPublishTime()).isNull();
		assertThat(normalized.getSourceUrl()).isNull();
	}

	@Test
	void formalMetadataRequiresStableWorkIdAndVisualMedia() {
		assertThatThrownBy(() -> normalizer.normalize(formalMetadata(null, null, videoResource())))
				.isInstanceOf(WorkMetadataValidationException.class)
				.hasMessageContaining("work ID");
		assertThatThrownBy(() -> normalizer.normalize(formalMetadata(null, "work-1", audioResource())))
				.isInstanceOf(WorkMetadataValidationException.class)
				.hasMessageContaining("visual media");
	}

	@Test
	void genericMetadataAllowsPartialFieldsButRequiresSourceAndDownloadableVideo() {
		WorkMetadata valid = WorkMetadata.builder()
				.platformKey("vimeo")
				.platformDisplayName("Vimeo")
				.supportTier(PlatformSupportTier.GENERIC)
				.contentType(WorkContentType.VIDEO)
				.sourceUrl("https://vimeo.com/123")
				.mediaResources(List.of(videoResource()))
				.build();

		WorkMetadata normalized = normalizer.normalize(valid);
		assertThat(normalized.getWorkId()).isNull();
		assertThat(normalized.getTitle()).isNull();

		WorkMetadata missingSource = WorkMetadata.builder()
				.platformKey("vimeo")
				.platformDisplayName("Vimeo")
				.supportTier(PlatformSupportTier.GENERIC)
				.contentType(WorkContentType.VIDEO)
				.mediaResources(List.of(videoResource()))
				.build();
		assertThatThrownBy(() -> normalizer.normalize(missingSource))
				.hasMessageContaining("canonical source URL");

		WorkMetadata missingVideo = WorkMetadata.builder()
				.platformKey("vimeo")
				.platformDisplayName("Vimeo")
				.supportTier(PlatformSupportTier.GENERIC)
				.contentType(WorkContentType.VIDEO)
				.sourceUrl("https://vimeo.com/123")
				.mediaResources(List.of(audioResource()))
				.build();
		assertThatThrownBy(() -> normalizer.normalize(missingVideo))
				.hasMessageContaining("downloadable video");
	}

	private WorkMetadata formalMetadata(String publishTime, String workId, WorkMediaResource resource) {
		return WorkMetadata.builder()
				.platform(PlatformCatalog.requireByKey("youtube"))
				.workId(workId)
				.contentType(WorkContentType.VIDEO)
				.publishTime(publishTime)
				.mediaResources(List.of(resource))
				.build();
	}

	private WorkMediaResource videoResource() {
		return new WorkMediaResource(0, WorkMediaResource.Type.VIDEO, "https://cdn.example/video.mp4", null,
				"mp4", Map.of());
	}

	private WorkMediaResource audioResource() {
		return new WorkMediaResource(0, WorkMediaResource.Type.AUDIO, "https://cdn.example/audio.m4a", null,
				"m4a", Map.of());
	}
}
