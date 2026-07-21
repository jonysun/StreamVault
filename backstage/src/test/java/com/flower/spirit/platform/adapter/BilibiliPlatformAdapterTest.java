package com.flower.spirit.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flower.spirit.config.Global;
import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.platform.WorkParseRequest;
import com.flower.spirit.utils.BiliUtil;

class BilibiliPlatformAdapterTest {

	@TempDir Path tempDir;
	private final String originalCookie = Global.bilicookies;

	@AfterEach
	void restoreCookie() {
		Global.bilicookies = originalCookie;
	}

	@Test
	void ownsBvAvAndShortLinksAndMapsOneCidPerPart() throws Exception {
		FakeGateway gateway = new FakeGateway(resource("view-single.json"), resource("play-durl.json"));
		BilibiliPlatformAdapter adapter = adapter(gateway);

		WorkMetadata metadata = adapter.parse(new WorkParseRequest("shared Bilibili link",
				"https://www.bilibili.com/video/BV1single/", true));

		assertThat(adapter.supports("https://www.bilibili.com/video/BV1single/")).isTrue();
		assertThat(adapter.supports("https://www.bilibili.com/video/av1001/")).isTrue();
		assertThat(adapter.supports("https://b23.tv/example")).isTrue();
		assertThat(metadata.getPlatformKey()).isEqualTo("bilibili");
		assertThat(metadata.getWorkId()).isEqualTo("2001");
		assertThat(metadata.getContentType()).isEqualTo(WorkContentType.VIDEO);
		assertThat(metadata.getSourceUrl()).isEqualTo("https://www.bilibili.com/video/BV1single/");
		assertThat(metadata.getAuthorId()).isEqualTo("9001");
		assertThat(metadata.getAuthorHomepage()).isEqualTo("https://space.bilibili.com/9001");
		assertThat(metadata.getPublishTime()).isEqualTo("1710000000");
		assertThat(metadata.getRawMetadata()).contains("BV1single").doesNotContain("Cookie", "SESSDATA");
	}

	@Test
	void selectsRequestedPartAndCanExpandMultiPartUpload() throws Exception {
		FakeGateway gateway = new FakeGateway(resource("view-multi.json"), resource("play-durl.json"));
		BilibiliPlatformAdapter adapter = adapter(gateway);

		WorkMetadata selected = adapter.parse(new WorkParseRequest("input",
				"https://www.bilibili.com/video/BV1multi/?p=2", true));
		List<WorkMetadata> parts = adapter.parseParts(new WorkParseRequest("input",
				"https://b23.tv/multi", true));
		List<WorkMetadata> routedParts = adapter.parseAll(new WorkParseRequest("input",
				"https://b23.tv/multi", true));

		assertThat(selected.getWorkId()).isEqualTo("2102");
		assertThat(selected.getTitle()).contains("Ending");
		assertThat(selected.getSourceUrl()).endsWith("/video/BV1multi/?p=2");
		assertThat(parts).extracting(WorkMetadata::getWorkId).containsExactly("2101", "2102");
		assertThat(routedParts).extracting(WorkMetadata::getWorkId).containsExactly("2101", "2102");
		assertThat(parts).extracting(WorkMetadata::getSourceUrl).containsExactly(
				"https://www.bilibili.com/video/BV1multi/?p=1",
				"https://www.bilibili.com/video/BV1multi/?p=2");
	}

	@Test
	void downloadsAndMergesDashInsideRequestedDirectory() throws Exception {
		Global.bilicookies = "SESSDATA=not-committed";
		FakeGateway gateway = new FakeGateway(resource("view-single.json"), resource("play-dash.json"));
		BilibiliPlatformAdapter adapter = adapter(gateway);
		WorkMetadata metadata = adapter.parse(new WorkParseRequest("input",
				"https://www.bilibili.com/video/BV1single/", false));

		assertThat(metadata.getMediaResources()).extracting(WorkMediaResource::getType)
				.containsExactly(WorkMediaResource.Type.VIDEO, WorkMediaResource.Type.AUDIO);
		assertThat(metadata.getMediaResources()).allSatisfy(resource ->
				assertThat(resource.getRequestHeaders()).doesNotContainKeys("Cookie", "cookie"));

		DownloadResult result = adapter.download(metadata, new WorkDownloadRequest(tempDir, false));

		assertThat(result.getStatus()).isEqualTo(DownloadResult.Status.COMPLETED);
		assertThat(result.getMediaResources()).filteredOn(resource -> resource.getType() == WorkMediaResource.Type.VIDEO)
				.singleElement().satisfies(resource -> {
			assertThat(resource.getLocalPath()).isEqualTo(tempDir.resolve("2001.mp4"));
			assertThat(resource.getType()).isEqualTo(WorkMediaResource.Type.VIDEO);
		});
		assertThat(Files.readString(tempDir.resolve("2001.mp4"))).isEqualTo("merged");
		assertThat(tempDir.resolve("2001-video.m4s")).doesNotExist();
		assertThat(tempDir.resolve("2001-audio.m4s")).doesNotExist();
		assertThat(gateway.cookies).containsOnly("SESSDATA=not-committed");
	}

	@Test
	void preservesAndConcatenatesEveryDurlSegment() throws Exception {
		String segmented = "{\"code\":0,\"data\":{\"durl\":["
				+ "{\"url\":\"https://cdn.example/one.mp4\"},"
				+ "{\"url\":\"https://cdn.example/two.mp4\"}]}}";
		FakeGateway gateway = new FakeGateway(resource("view-single.json"), segmented);
		BilibiliPlatformAdapter adapter = adapter(gateway);
		WorkMetadata metadata = adapter.parse(new WorkParseRequest("input",
				"https://www.bilibili.com/video/BV1single/", false));

		assertThat(metadata.getMediaResources()).hasSize(2);
		DownloadResult result = adapter.download(metadata, new WorkDownloadRequest(tempDir, false));

		assertThat(result.getMediaResources()).filteredOn(resource -> resource.getType() == WorkMediaResource.Type.VIDEO)
				.singleElement().satisfies(resource ->
				assertThat(resource.getLocalPath()).hasContent("concatenated"));
	}

	@Test
	void rejectsUnsupportedAndMissingPartsExplicitly() throws Exception {
		FakeGateway gateway = new FakeGateway(resource("view-single.json"), resource("play-durl.json"));
		BilibiliPlatformAdapter adapter = adapter(gateway);

		assertThatThrownBy(() -> adapter.parse(new WorkParseRequest("live",
				"https://live.bilibili.com/123", true)))
				.isInstanceOf(WorkMetadataValidationException.class).hasMessageContaining("not supported");
		assertThatThrownBy(() -> adapter.parse(new WorkParseRequest("bangumi",
				"https://www.bilibili.com/bangumi/play/ep1", true)))
				.isInstanceOf(WorkMetadataValidationException.class).hasMessageContaining("not supported");
		assertThatThrownBy(() -> adapter.parse(new WorkParseRequest("part",
				"https://www.bilibili.com/video/BV1single/?p=3", true)))
				.isInstanceOf(WorkMetadataValidationException.class).hasMessageContaining("does not exist");
	}

	@Test
	void legacyResponseParserKeepsCollectionMapShape() throws Exception {
		List<Map<String, String>> parts = BiliUtil.parseVideoDataInfo(resource("view-multi.json"));

		assertThat(parts).hasSize(2);
		assertThat(parts.get(1)).containsEntry("cid", "2102")
				.containsEntry("bvid", "BV1multi")
				.containsEntry("page", "2")
				.containsEntry("part", "Ending")
				.containsKeys("aid", "desc", "quality", "pic", "duration", "owner", "ctime");
	}

	private BilibiliPlatformAdapter adapter(FakeGateway gateway) {
		return new BilibiliPlatformAdapter(new PlatformResolver(), gateway);
	}

	private String resource(String name) throws IOException {
		try (var stream = getClass().getResourceAsStream("/platform/bilibili/" + name)) {
			if (stream == null) throw new IOException("fixture not found: " + name);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static class FakeGateway implements BilibiliPlatformAdapter.Gateway {
		private final String view;
		private final String play;
		private final java.util.ArrayList<String> cookies = new java.util.ArrayList<>();

		private FakeGateway(String view, String play) {
			this.view = view;
			this.play = play;
		}

		@Override
		public String resolveEntry(String url) {
			if (view.contains("BV1multi")) return "BV1multi";
			if (url.contains("/av")) return "av1001";
			return "BV1single";
		}

		@Override public String view(String entry) { return view; }

		@Override
		public String play(Map<String, String> part, String cookie) {
			cookies.add(cookie);
			return play;
		}

		@Override
		public Path download(WorkMediaResource source, Path destination, String cookie) throws IOException {
			cookies.add(cookie);
			Files.createDirectories(destination.getParent());
			return Files.writeString(destination, source.getType().name());
		}

		@Override
		public Path merge(Path video, Path audio, Path destination) throws IOException {
			return Files.writeString(destination, "merged");
		}

		@Override
		public Path concat(List<Path> segments, Path destination) throws IOException {
			return Files.writeString(destination, "concatenated");
		}
	}
}
