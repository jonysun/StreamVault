package com.flower.spirit.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.DouyinGlobalCooldownException;
import com.flower.spirit.platform.DouyinWorkFetchException;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.platform.WorkParseRequest;
import com.flower.spirit.service.PlatformCookieService;

class DouyinPlatformAdapterTest {

	@TempDir Path tempDir;

	@Test
	void parsesVideoWithStableAuthorIdentityAndNoPreviewSideEffects() throws Exception {
		TestContext context = context(resource("video.json"),
				"https://www.douyin.com/video/7300000000000000001");

		WorkMetadata metadata = context.adapter.parse(new WorkParseRequest("shared video",
				"https://v.douyin.com/video-share/", true));

		assertThat(context.adapter.supports("https://www.douyin.com/video/1")).isTrue();
		assertThat(metadata.getPlatformKey()).isEqualTo("douyin");
		assertThat(metadata.getWorkId()).isEqualTo("7300000000000000001");
		assertThat(metadata.getContentType()).isEqualTo(WorkContentType.VIDEO);
		assertThat(metadata.getAuthorId()).isEqualTo("MS4wLjABAAAAstable-video");
		assertThat(metadata.getAuthorUsername()).isEqualTo("video_author");
		assertThat(metadata.getAuthorHomepage())
				.isEqualTo("https://www.douyin.com/user/MS4wLjABAAAAstable-video");
		assertThat(metadata.getAuthorSignature()).isEqualTo("Video signature");
		assertThat(metadata.getSourceUrl())
				.isEqualTo("https://www.douyin.com/video/7300000000000000001");
		assertThat(metadata.getMediaResources()).singleElement()
				.extracting(WorkMediaResource::getSourceUrl)
				.isEqualTo("https://media.example/douyin-video.mp4");
		assertThat(context.gateway.downloadCalls).isZero();
		assertThat(context.gateway.fetchCalls).isEqualTo(1);
		verify(context.cookieService).reportSuccess("抖音", "cookie-value");
	}

	@Test
	void parsesValidatedListSnapshotWithoutResolvingOrFetchingSingleWorkDetail() throws Exception {
		PlatformCookieService cookies = mock(PlatformCookieService.class);
		FakeGateway gateway = new FakeGateway("remote response must not be used",
				"https://www.douyin.com/video/7300000000000000001");
		DouyinPlatformAdapter adapter = new DouyinPlatformAdapter(new PlatformResolver(), cookies, gateway);

		WorkMetadata metadata = adapter.parse(new WorkParseRequest("collection item",
				"https://www.douyin.com/video/7300000000000000001", false, resource("video.json")));

		assertThat(metadata.getWorkId()).isEqualTo("7300000000000000001");
		assertThat(metadata.getMediaResources()).singleElement()
				.extracting(WorkMediaResource::getSourceUrl)
				.isEqualTo("https://media.example/douyin-video.mp4");
		assertThat(gateway.fetchCalls).isZero();
		assertThat(gateway.resolveCalls).isZero();
		verify(cookies, never()).currentDouyinCookie(any());
		verify(cookies, never()).reportSuccess(any(), any());
	}

	@Test
	void mismatchedListSnapshotFallsBackToCurrentSingleWorkDetailFlow() throws Exception {
		TestContext context = context(resource("graphic.json"),
				"https://www.douyin.com/note/7300000000000000002");

		WorkMetadata metadata = context.adapter.parse(new WorkParseRequest("collection item",
				"https://www.douyin.com/note/7300000000000000002", false, resource("video.json")));

		assertThat(metadata.getWorkId()).isEqualTo("7300000000000000002");
		assertThat(context.gateway.fetchCalls).isEqualTo(1);
		verify(context.cookieService).currentDouyinCookie("single_work_parse");
	}

	@Test
	void parsesGraphicCarouselAndMixedInResourceOrder() throws Exception {
		WorkMetadata graphic = parseFixture("graphic.json", "7300000000000000002", "/note/");
		WorkMetadata carousel = parseFixture("carousel.json", "7300000000000000003", "/note/");
		WorkMetadata mixed = parseFixture("mixed.json", "7300000000000000004", "/note/");

		assertThat(graphic.getContentType()).isEqualTo(WorkContentType.GRAPHIC);
		assertThat(graphic.getMediaResources()).extracting(WorkMediaResource::getType)
				.containsExactly(WorkMediaResource.Type.IMAGE, WorkMediaResource.Type.IMAGE);
		assertThat(carousel.getContentType()).isEqualTo(WorkContentType.GRAPHIC);
		assertThat(carousel.getMediaResources()).hasSize(3);
		assertThat(mixed.getContentType()).isEqualTo(WorkContentType.MIXED);
		assertThat(mixed.getMediaResources()).extracting(WorkMediaResource::getType)
				.containsExactly(WorkMediaResource.Type.IMAGE, WorkMediaResource.Type.VIDEO);
		assertThat(mixed.getSourceUrl())
				.isEqualTo("https://www.douyin.com/user/MS4wLjABAAAAstable-mixed?modal_id=7300000000000000004");
	}

	@Test
	void downloadsEveryVisualResourceInsideRequestedDirectoryWithoutPersistingCookie() throws Exception {
		TestContext context = context(resource("mixed.json"),
				"https://www.douyin.com/note/7300000000000000004");
		WorkMetadata metadata = context.adapter.parse(new WorkParseRequest("input",
				"https://www.douyin.com/note/7300000000000000004", false));

		DownloadResult result = context.adapter.download(metadata, new WorkDownloadRequest(tempDir, false));

		assertThat(result.getStatus()).isEqualTo(DownloadResult.Status.COMPLETED);
		assertThat(result.getMediaResources()).extracting(item -> item.getLocalPath().getFileName().toString())
				.containsExactly("7300000000000000004-index-0.jpg", "7300000000000000004-index-1.mp4");
		assertThat(result.getMediaResources()).allSatisfy(resource -> {
			assertThat(resource.getLocalPath()).startsWith(tempDir);
			assertThat(resource.getRequestHeaders()).doesNotContainKeys("cookie", "Cookie");
		});
		assertThat(context.gateway.downloadCalls).isEqualTo(2);
		assertThat(context.gateway.lastCookie).isEqualTo("cookie-value");
	}

	@Test
	void operationScopeReusesOneCookieForParseAndEveryDownloadRequest() throws Exception {
		PlatformCookieService cookies = mock(PlatformCookieService.class);
		when(cookies.hasConfiguredDouyinCookie()).thenReturn(true);
		when(cookies.currentDouyinCookie("work_ingest")).thenReturn("leased-cookie");
		when(cookies.currentDouyinCookie("single_work_parse")).thenReturn("rotated-parse-cookie");
		when(cookies.currentDouyinCookie("single_work_download")).thenReturn("rotated-download-cookie");
		FakeGateway gateway = new FakeGateway(resource("mixed.json"),
				"https://www.douyin.com/note/7300000000000000004");
		DouyinPlatformAdapter adapter = new DouyinPlatformAdapter(new PlatformResolver(), cookies, gateway);

		try (PlatformWorkAdapter.OperationScope ignored = adapter.openOperationScope("work_ingest")) {
			WorkMetadata metadata = adapter.parse(new WorkParseRequest("input",
					"https://www.douyin.com/note/7300000000000000004", false));
			adapter.download(metadata, new WorkDownloadRequest(tempDir, false));
		}

		assertThat(gateway.cookies).isNotEmpty().containsOnly("leased-cookie");
		verify(cookies).currentDouyinCookie("work_ingest");
		verify(cookies, never()).currentDouyinCookie("single_work_parse");
		verify(cookies, never()).currentDouyinCookie("single_work_download");
	}

	@Test
	void reportsRiskAndRejectsMissingCookieOrMalformedWorks() throws Exception {
		PlatformCookieService noCookie = mock(PlatformCookieService.class);
		when(noCookie.currentDouyinCookie("single_work_parse")).thenReturn("");
		DouyinPlatformAdapter noCookieAdapter = new DouyinPlatformAdapter(new PlatformResolver(), noCookie,
				new FakeGateway("{}", "https://www.douyin.com/video/1"));
		assertThatThrownBy(() -> noCookieAdapter.parse(new WorkParseRequest("input",
				"https://www.douyin.com/video/1", true)))
				.isInstanceOf(WorkMetadataValidationException.class).hasMessageContaining("cookie");

		PlatformCookieService risky = mock(PlatformCookieService.class);
		when(risky.hasConfiguredDouyinCookie()).thenReturn(true);
		when(risky.currentDouyinCookie("single_work_parse")).thenReturn("risky-cookie");
		when(risky.isRiskSignal("Douyin returned no metadata")).thenReturn(true);
		DouyinPlatformAdapter riskyAdapter = new DouyinPlatformAdapter(new PlatformResolver(), risky,
				new FakeGateway("", "https://www.douyin.com/video/1"));
		assertThatThrownBy(() -> riskyAdapter.parse(new WorkParseRequest("input",
				"https://www.douyin.com/video/1", true)))
				.isInstanceOf(WorkMetadataValidationException.class).hasMessageContaining("no metadata");
		verify(risky).reportRisk("抖音", "risky-cookie", "parse response rejected");
		verify(noCookie, never()).reportSuccess("抖音", "");
	}

	@Test
	void configuredCookieDuringGlobalCooldownRaisesDedicatedSignal() {
		PlatformCookieService cookies = mock(PlatformCookieService.class);
		Instant retryAt = Instant.parse("2026-08-03T01:00:05Z");
		when(cookies.hasConfiguredDouyinCookie()).thenReturn(true);
		when(cookies.currentDouyinCookie("single_work_parse")).thenReturn("");
		when(cookies.isDouyinGlobalRiskCooldownActive()).thenReturn(true);
		when(cookies.douyinGlobalRiskCooldownRetryAt(any())).thenReturn(retryAt);
		DouyinPlatformAdapter adapter = new DouyinPlatformAdapter(new PlatformResolver(), cookies,
				new FakeGateway("{}", "https://www.douyin.com/video/1"));

		assertThatThrownBy(() -> adapter.parse(new WorkParseRequest("input",
				"https://www.douyin.com/video/1", true)))
				.isInstanceOf(DouyinGlobalCooldownException.class)
				.extracting(error -> ((DouyinGlobalCooldownException) error).retryAt())
				.isEqualTo(retryAt);
	}

	@Test
	void explicitAuthenticationFailureDuringDownloadRaisesCooldownSignal() throws Exception {
		PlatformCookieService cookies = mock(PlatformCookieService.class);
		Instant retryAt = Instant.parse("2026-08-03T01:00:05Z");
		when(cookies.hasConfiguredDouyinCookie()).thenReturn(true);
		when(cookies.currentDouyinCookie("single_work_parse")).thenReturn("cookie-value");
		when(cookies.currentDouyinCookie("single_work_download")).thenReturn("cookie-value");
		when(cookies.isRiskSignal("HTTP 403")).thenReturn(true);
		when(cookies.douyinGlobalRiskCooldownRetryAt(any())).thenReturn(retryAt);
		FakeGateway gateway = new FakeGateway(resource("video.json"),
				"https://www.douyin.com/video/7300000000000000001") {
			@Override public Path download(WorkMediaResource source, Path destination, String cookie) throws IOException {
				throw new IOException("HTTP 403");
			}
		};
		DouyinPlatformAdapter adapter = new DouyinPlatformAdapter(new PlatformResolver(), cookies, gateway);
		WorkMetadata metadata = adapter.parse(new WorkParseRequest("input",
				"https://www.douyin.com/video/7300000000000000001", false));

		assertThatThrownBy(() -> adapter.download(metadata, new WorkDownloadRequest(tempDir, false)))
				.isInstanceOf(DouyinGlobalCooldownException.class)
				.extracting(error -> ((DouyinGlobalCooldownException) error).retryAt())
				.isEqualTo(retryAt);
		verify(cookies).reportRisk("抖音", "cookie-value", "download request failed");
	}

	@Test
	void typedF2RateLimitIsReportedAndConvertedToGlobalCooldown() {
		PlatformCookieService cookies = mock(PlatformCookieService.class);
		Instant retryAt = Instant.parse("2026-08-10T08:00:05Z");
		when(cookies.hasConfiguredDouyinCookie()).thenReturn(true);
		when(cookies.currentDouyinCookie("single_work_parse")).thenReturn("cookie-value");
		when(cookies.douyinGlobalRiskCooldownRetryAt(any())).thenReturn(retryAt);
		FakeGateway gateway = new FakeGateway("{}", "https://www.douyin.com/video/7300000000000000001");
		gateway.fetchError = new DouyinWorkFetchException("F2_UPSTREAM_RATE_LIMIT", "rate limited",
				"REMOTE_API", true, true, 429, "HTTPStatusError");
		DouyinPlatformAdapter adapter = new DouyinPlatformAdapter(new PlatformResolver(), cookies, gateway);

		assertThatThrownBy(() -> adapter.parse(new WorkParseRequest("input",
				"https://www.douyin.com/video/7300000000000000001", false)))
				.isInstanceOf(DouyinGlobalCooldownException.class)
				.extracting(error -> ((DouyinGlobalCooldownException) error).retryAt())
				.isEqualTo(retryAt);
		verify(cookies, times(1)).reportRisk("抖音", "cookie-value", "F2_UPSTREAM_RATE_LIMIT");
	}

	@Test
	void detailSoftBackoffRejectsOnlyDetailFetchBeforeCallingGateway() {
		PlatformCookieService cookies = mock(PlatformCookieService.class);
		Instant retryAt = Instant.parse("2026-08-16T01:00:05Z");
		when(cookies.hasConfiguredDouyinCookie()).thenReturn(true);
		when(cookies.currentDouyinCookie("single_work_parse")).thenReturn("cookie-value");
		when(cookies.isDouyinDetailSoftBackoffActive()).thenReturn(true);
		when(cookies.douyinDetailSoftBackoffRetryAt(any())).thenReturn(retryAt);
		FakeGateway gateway = new FakeGateway(resourceUnchecked("video.json"),
				"https://www.douyin.com/video/7300000000000000001");
		DouyinPlatformAdapter adapter = new DouyinPlatformAdapter(new PlatformResolver(), cookies, gateway);

		assertThatThrownBy(() -> adapter.parse(new WorkParseRequest("input",
				"https://www.douyin.com/video/7300000000000000001", false)))
				.isInstanceOf(DouyinGlobalCooldownException.class)
				.extracting(error -> ((DouyinGlobalCooldownException) error).retryAt())
				.isEqualTo(retryAt);
		assertThat(gateway.fetchCalls).isZero();
	}

	@Test
	void explicitRateLimitDuringDownloadRaisesCooldownSignal() throws Exception {
		PlatformCookieService cookies = mock(PlatformCookieService.class);
		Instant retryAt = Instant.parse("2026-08-10T08:00:05Z");
		when(cookies.hasConfiguredDouyinCookie()).thenReturn(true);
		when(cookies.currentDouyinCookie("single_work_parse")).thenReturn("cookie-value");
		when(cookies.currentDouyinCookie("single_work_download")).thenReturn("cookie-value");
		when(cookies.isRiskSignal("media request failed with HTTP 429")).thenReturn(true);
		when(cookies.douyinGlobalRiskCooldownRetryAt(any())).thenReturn(retryAt);
		FakeGateway gateway = new FakeGateway(resource("video.json"),
				"https://www.douyin.com/video/7300000000000000001") {
			@Override public Path download(WorkMediaResource source, Path destination, String cookie) throws IOException {
				throw new IOException("media request failed with HTTP 429");
			}
		};
		DouyinPlatformAdapter adapter = new DouyinPlatformAdapter(new PlatformResolver(), cookies, gateway);
		WorkMetadata metadata = adapter.parse(new WorkParseRequest("input",
				"https://www.douyin.com/video/7300000000000000001", false));

		assertThatThrownBy(() -> adapter.download(metadata, new WorkDownloadRequest(tempDir, false)))
				.isInstanceOf(DouyinGlobalCooldownException.class)
				.hasMessageContaining("HTTP 429")
				.extracting(error -> ((DouyinGlobalCooldownException) error).retryAt())
				.isEqualTo(retryAt);
		verify(cookies).reportRisk(org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.eq("cookie-value"),
				org.mockito.ArgumentMatchers.eq("download request failed"));
	}

	private WorkMetadata parseFixture(String fixture, String id, String path) throws Exception {
		TestContext context = context(resource(fixture), "https://www.douyin.com" + path + id);
		return context.adapter.parse(new WorkParseRequest("input", "https://www.douyin.com" + path + id, true));
	}

	private TestContext context(String raw, String resolvedUrl) {
		PlatformCookieService cookies = mock(PlatformCookieService.class);
		when(cookies.hasConfiguredDouyinCookie()).thenReturn(true);
		when(cookies.currentDouyinCookie("single_work_parse")).thenReturn("cookie-value");
		when(cookies.currentDouyinCookie("single_work_download")).thenReturn("cookie-value");
		FakeGateway gateway = new FakeGateway(raw, resolvedUrl);
		return new TestContext(new DouyinPlatformAdapter(new PlatformResolver(), cookies, gateway), cookies, gateway);
	}

	private String resource(String name) throws IOException {
		try (var stream = getClass().getResourceAsStream("/platform/douyin/" + name)) {
			if (stream == null) throw new IOException("fixture not found: " + name);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private String resourceUnchecked(String name) {
		try {
			return resource(name);
		} catch (IOException error) {
			throw new IllegalStateException(error);
		}
	}

	private record TestContext(DouyinPlatformAdapter adapter, PlatformCookieService cookieService,
			FakeGateway gateway) {
	}

	private static class FakeGateway implements DouyinPlatformAdapter.Gateway {
		private final String raw;
		private final String resolvedUrl;
		private int fetchCalls;
		private int resolveCalls;
		private int downloadCalls;
		private String lastCookie;
		private IOException fetchError;
		private final List<String> cookies = new ArrayList<>();

		private FakeGateway(String raw, String resolvedUrl) {
			this.raw = raw;
			this.resolvedUrl = resolvedUrl;
		}

		@Override public String resolve(String url) {
			resolveCalls++;
			return resolvedUrl;
		}
		@Override public String fetch(String workId, String cookie) throws IOException {
			fetchCalls++;
			lastCookie = cookie;
			cookies.add(cookie);
			if (fetchError != null) throw fetchError;
			return raw;
		}
		@Override public Path download(WorkMediaResource source, Path destination, String cookie) throws IOException {
			downloadCalls++;
			lastCookie = cookie;
			cookies.add(cookie);
			Files.createDirectories(destination.getParent());
			return Files.writeString(destination, source.getType().name());
		}
	}
}
