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
import com.flower.spirit.executor.HongShuExecutor;
import com.flower.spirit.executor.HongShuExecutor.ParsedNote;
import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkParseRequest;

class XiaohongshuPlatformAdapterTest {

	@TempDir Path tempDir;
	private CookiesConfigEntity originalCookies;

	@BeforeEach
	void setUp() {
		originalCookies = Global.cookie_manage;
		CookiesConfigEntity cookies = new CookiesConfigEntity();
		cookies.setRednotecookie("red-cookie");
		Global.cookie_manage = cookies;
	}

	@AfterEach
	void tearDown() {
		Global.cookie_manage = originalCookies;
	}

	@Test
	void mapsGraphicVideoAndMixedNotesToCompatibleContentTypes() throws Exception {
		WorkMetadata graphic = parse("graphic.html");
		WorkMetadata video = parse("video.html");
		WorkMetadata mixed = parse("mixed.html");

		assertThat(graphic.getContentType()).isEqualTo(WorkContentType.GRAPHIC);
		assertThat(graphic.getMediaResources()).extracting(WorkMediaResource::getType)
				.containsExactly(WorkMediaResource.Type.IMAGE, WorkMediaResource.Type.IMAGE);
		assertThat(video.getContentType()).isEqualTo(WorkContentType.VIDEO);
		assertThat(video.getMediaResources()).extracting(WorkMediaResource::getType)
				.containsExactly(WorkMediaResource.Type.VIDEO);
		assertThat(mixed.getContentType()).isEqualTo(WorkContentType.MIXED);
		assertThat(mixed.getMediaResources()).extracting(WorkMediaResource::getType)
				.containsExactly(WorkMediaResource.Type.IMAGE, WorkMediaResource.Type.IMAGE,
						WorkMediaResource.Type.VIDEO);
		assertThat(mixed.getAuthorHomepage()).isEqualTo(
				"https://www.xiaohongshu.com/user/profile/red-author-1");
		assertThat(mixed.getMediaResources()).allSatisfy(resource ->
				assertThat(resource.getRequestHeaders()).doesNotContainKeys("Cookie", "cookie"));
	}

	@Test
	void downloadsAllMixedResourcesInPlatformOrderWithCookieOutOfBand() throws Exception {
		ParsedNote note = note("mixed.html");
		FakeGateway gateway = new FakeGateway(note);
		XiaohongshuPlatformAdapter adapter = new XiaohongshuPlatformAdapter(new PlatformResolver(), gateway);
		WorkMetadata metadata = adapter.parse(new WorkParseRequest("shared text",
				"https://www.xiaohongshu.com/explore/xhs-mixed-1", false));

		DownloadResult result = adapter.download(metadata, new WorkDownloadRequest(tempDir, false));

		assertThat(result.getMediaResources()).hasSize(3);
		assertThat(result.getMediaResources()).extracting(resource -> resource.getLocalPath().getFileName().toString())
				.containsExactly("xhs-mixed-1-index-0.jpeg", "xhs-mixed-1-index-1.jpeg",
						"xhs-mixed-1-index-2.mp4");
		assertThat(gateway.lastCookie).isEqualTo("red-cookie");
		assertThat(gateway.lastHeaders).doesNotContainKeys("Cookie", "cookie");
	}

	private WorkMetadata parse(String fixture) throws Exception {
		ParsedNote note = note(fixture);
		XiaohongshuPlatformAdapter adapter = new XiaohongshuPlatformAdapter(new PlatformResolver(),
				new FakeGateway(note));
		return adapter.parse(new WorkParseRequest("input",
				"https://www.xiaohongshu.com/explore/" + note.workId(), true));
	}

	private ParsedNote note(String fixture) throws IOException {
		return HongShuExecutor.parsePage(resource(fixture),
				"https://www.xiaohongshu.com/explore/fixture");
	}

	private String resource(String fixture) throws IOException {
		try (var stream = getClass().getResourceAsStream("/platform/xiaohongshu/" + fixture)) {
			if (stream == null) throw new IOException("fixture not found: " + fixture);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static class FakeGateway implements XiaohongshuPlatformAdapter.Gateway {
		private final ParsedNote note;
		private String lastCookie;
		private Map<String, String> lastHeaders;

		private FakeGateway(ParsedNote note) { this.note = note; }
		@Override public ParsedNote parse(String url, String cookie) { lastCookie = cookie; return note; }
		@Override public Path download(String url, Path destination, String cookie,
				Map<String, String> headers) throws IOException {
			lastCookie = cookie;
			lastHeaders = headers;
			Files.createDirectories(destination.getParent());
			return Files.writeString(destination, "media");
		}
	}
}
