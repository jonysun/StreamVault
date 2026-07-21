package com.flower.spirit.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flower.spirit.config.Global;
import com.flower.spirit.entity.CookiesConfigEntity;
import com.flower.spirit.executor.WeiBoExecutor;
import com.flower.spirit.executor.WeiBoExecutor.ParsedPost;
import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkParseRequest;

class WeiboPlatformAdapterTest {

	@TempDir Path tempDir;
	private CookiesConfigEntity originalCookies;

	@BeforeEach
	void setUp() {
		originalCookies = Global.cookie_manage;
		CookiesConfigEntity cookies = new CookiesConfigEntity();
		cookies.setWeibocookie("weibo-cookie");
		Global.cookie_manage = cookies;
	}

	@AfterEach
	void tearDown() {
		Global.cookie_manage = originalCookies;
	}

	@Test
	void mapsImageVideoAndMixedPostsWithCanonicalAuthorAndPublishTime() throws Exception {
		WorkMetadata image = parse("image.json");
		WorkMetadata video = parse("video.json");
		WorkMetadata mixed = parse("mixed.json");

		assertThat(image.getContentType()).isEqualTo(WorkContentType.GRAPHIC);
		assertThat(video.getContentType()).isEqualTo(WorkContentType.VIDEO);
		assertThat(video.getMediaResources()).singleElement().satisfies(resource ->
				assertThat(resource.getSourceUrl()).isEqualTo("https://media.example/wb-video-high.mp4"));
		assertThat(mixed.getContentType()).isEqualTo(WorkContentType.MIXED);
		assertThat(mixed.getMediaResources()).extracting(WorkMediaResource::getType)
				.containsExactly(WorkMediaResource.Type.IMAGE, WorkMediaResource.Type.VIDEO);
		assertThat(mixed.getAuthorHomepage()).isEqualTo("https://weibo.com/u/wb-author-1");
		assertThat(mixed.getPublishTime()).isEqualTo("2024-03-10T04:00:00Z");
		assertThat(mixed.getMediaResources()).allSatisfy(resource ->
				assertThat(resource.getRequestHeaders()).doesNotContainKeys("Cookie", "cookie"));
		assertThat(WeiBoExecutor.extractWeiboId("https://m.weibo.cn/detail/O8DM0BLLm"))
				.isEqualTo("O8DM0BLLm");
	}

	@Test
	void downloadsMixedPostAsOneOrderedResourceSet() throws Exception {
		ParsedPost post = post("mixed.json");
		FakeGateway gateway = new FakeGateway(post);
		WeiboPlatformAdapter adapter = new WeiboPlatformAdapter(new PlatformResolver(), gateway);
		WorkMetadata metadata = adapter.parse(new WorkParseRequest("input",
				"https://m.weibo.cn/detail/O8DM0BLLm", false));

		DownloadResult result = adapter.download(metadata, new WorkDownloadRequest(tempDir, false));

		assertThat(result.getMediaResources()).extracting(resource -> resource.getLocalPath().getFileName().toString())
				.containsExactly("O8DM0BLLm-index-0.jpeg", "O8DM0BLLm-index-1.mp4");
		assertThat(gateway.lastCookie).isEqualTo("weibo-cookie");
		assertThat(gateway.lastHeaders).doesNotContainKeys("Cookie", "cookie");
	}

	private WorkMetadata parse(String fixture) throws Exception {
		ParsedPost post = post(fixture);
		WeiboPlatformAdapter adapter = new WeiboPlatformAdapter(new PlatformResolver(), new FakeGateway(post));
		return adapter.parse(new WorkParseRequest("input", "https://m.weibo.cn/detail/O8DM0BLLm", true));
	}

	private ParsedPost post(String fixture) throws IOException {
		return WeiBoExecutor.parseDetailJson(resource(fixture),
				"https://m.weibo.cn/detail/O8DM0BLLm", "O8DM0BLLm");
	}

	private String resource(String fixture) throws IOException {
		try (var stream = getClass().getResourceAsStream("/platform/weibo/" + fixture)) {
			if (stream == null) throw new IOException("fixture not found: " + fixture);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static class FakeGateway implements WeiboPlatformAdapter.Gateway {
		private final ParsedPost post;
		private String lastCookie;
		private Map<String, String> lastHeaders;
		private FakeGateway(ParsedPost post) { this.post = post; }
		@Override public ParsedPost parse(String workId, String sourceUrl, String cookie) {
			lastCookie = cookie;
			return post;
		}
		@Override public Path download(String url, Path destination, String cookie,
				Map<String, String> headers) throws IOException {
			lastCookie = cookie;
			lastHeaders = headers;
			Files.createDirectories(destination.getParent());
			return Files.writeString(destination, "media");
		}
	}
}
