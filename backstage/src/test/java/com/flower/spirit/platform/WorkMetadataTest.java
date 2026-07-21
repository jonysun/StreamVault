package com.flower.spirit.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WorkMetadataTest {

	@Test
	void metadataSortsAndDefensivelyCopiesMediaResources() {
		Map<String, String> headers = new HashMap<>();
		headers.put("Referer", "https://example.com/");
		WorkMediaResource second = new WorkMediaResource(2, WorkMediaResource.Type.VIDEO,
				"https://cdn.example.com/second.mp4", null, "mp4", headers);
		WorkMediaResource first = new WorkMediaResource(1, WorkMediaResource.Type.IMAGE,
				"https://cdn.example.com/first.jpg", null, "jpg", Map.of());
		List<WorkMediaResource> resources = new ArrayList<>(List.of(second, first));

		WorkMetadata metadata = WorkMetadata.builder()
				.platform(PlatformCatalog.requireByKey("xiaohongshu"))
				.workId("note-1")
				.contentType(WorkContentType.MIXED)
				.title("work title")
				.sourceUrl("https://www.xiaohongshu.com/explore/note-1")
				.mediaResources(resources)
				.build();

		resources.clear();
		headers.put("Cookie", "secret");

		assertThat(metadata.hasStableIdentity()).isTrue();
		assertThat(metadata.getMediaResources()).extracting(WorkMediaResource::getOrder).containsExactly(1, 2);
		assertThat(metadata.getMediaResources().get(1).getRequestHeaders())
				.containsExactlyEntriesOf(Map.of("Referer", "https://example.com/"));
		assertThatThrownBy(() -> metadata.getMediaResources().add(first))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void incompleteMetadataReportsMissingStableIdentity() {
		WorkMetadata metadata = WorkMetadata.builder()
				.platform(PlatformCatalog.requireByKey("youtube"))
				.contentType(WorkContentType.VIDEO)
				.build();

		assertThat(metadata.hasStableIdentity()).isFalse();
	}

	@Test
	void downloadResultDefensivelyCopiesResources() {
		List<WorkMediaResource> resources = new ArrayList<>();
		resources.add(new WorkMediaResource(0, WorkMediaResource.Type.VIDEO, "https://example.com/video.mp4",
				Path.of("video.mp4"), "mp4", Map.of()));

		DownloadResult result = DownloadResult.completed(resources);
		resources.clear();

		assertThat(result.getStatus()).isEqualTo(DownloadResult.Status.COMPLETED);
		assertThat(result.getMediaResources()).hasSize(1);
	}

	@Test
	void parseAndDownloadRequestsRetainExplicitIntent() {
		WorkParseRequest parseRequest = new WorkParseRequest("shared text", "https://example.com/work", true);
		WorkDownloadRequest downloadRequest = new WorkDownloadRequest(Path.of("target", "downloads"), true);

		assertThat(parseRequest.getInput()).isEqualTo("shared text");
		assertThat(parseRequest.getUrl()).isEqualTo("https://example.com/work");
		assertThat(parseRequest.isPreview()).isTrue();
		assertThat(downloadRequest.getOutputDirectory()).isEqualTo(Path.of("target", "downloads"));
		assertThat(downloadRequest.isReplaceExisting()).isTrue();
	}
}
