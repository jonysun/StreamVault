package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.flower.spirit.config.Global;
import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpServer;

class DtkDouyinDataProviderTest {
	private HttpServer server;
	private String baseUrl;
	private String oldBaseUrl;
	private String oldApiKey;
	private String oldApiPool;
	private ExecutorService executor;
	private AtomicReference<String> receivedApiKey;

	@BeforeEach
	void setUp() throws IOException {
		oldBaseUrl = Global.dtkBaseUrl;
		oldApiKey = Global.dtkApiKey;
		oldApiPool = Global.dtkApiPool;
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		executor = Executors.newSingleThreadExecutor();
		receivedApiKey = new AtomicReference<>();
		server.setExecutor(executor);
		server.createContext("/api/v1/douyin/video", exchange -> {
			receivedApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
			String query = exchange.getRequestURI().getRawQuery();
			if (query != null && query.contains("aweme_id=async-work")) {
				byte[] body = "{\"success\":true,\"data\":{\"task_id\":\"task-1\",\"status\":\"queued\"}}"
						.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(202, body.length);
				try (var output = exchange.getResponseBody()) { output.write(body); }
				return;
			}
			String bodyText = query != null && query.contains("aweme_id=top-level-cover")
			? "{\"success\":true,\"data\":{\"aweme_id\":\"top-level-cover\",\"desc\":\"title\","
					+ "\"video\":{\"play_addr\":{\"url_list\":[\"https://media.example/video.mp4\"]}},"
					+ "\"cover\":[\"https://media.example/top-level-cover.jpg\"],"
					+ "\"author\":{\"id\":\"author-1\",\"username\":\"author\"}},\"error\":null,\"meta\":{}}"
			: query != null && query.contains("aweme_id=urls-only")
					? "{\"success\":true,\"data\":{\"aweme_id\":\"urls-only\",\"desc\":\"title\","
							+ "\"video\":{\"urls\":[\"https://media.example/cdn-video\",\"https://www.douyin.com/aweme/v1/play/?signed=1\"]},"
							+ "\"author\":{\"id\":\"author-1\",\"username\":\"author\"}},\"error\":null,\"meta\":{}}"
					: query != null && query.contains("aweme_id=no-watermark")
					? "{\"success\":true,\"data\":{\"aweme_id\":\"no-watermark\",\"desc\":\"title\","
							+ "\"video\":{\"no_watermark\":\"https://media.example/video-no-extension\"},"
							+ "\"author\":{\"id\":\"author-1\",\"username\":\"author\"}},\"error\":null,\"meta\":{}}"
					: "{\"success\":true,\"data\":{\"aweme_id\":\"123\",\"desc\":\"title\","
							+ "\"video\":{\"play_addr\":{\"url_list\":[\"https://media.example/video.mp4\"]},"
							+ "\"cover\":{\"url_list\":[\"https://media.example/cover.jpg\"]}},"
							+ "\"author\":{\"id\":\"author-1\",\"username\":\"author\"}},\"error\":null,\"meta\":{}}";
			byte[] body = bodyText
					.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (var output = exchange.getResponseBody()) { output.write(body); }
		});
		server.createContext("/api/v1/tasks/task-1", exchange -> {
			receivedApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
			byte[] body = ("{\"success\":true,\"data\":{\"state\":\"done\",\"result\":{" +
					"\"success\":true,\"data\":{\"aweme_id\":\"async-work\",\"desc\":\"done\","
					+ "\"video\":{\"url\":\"https://media.example/async.mp4\"}},\"error\":null,\"meta\":{}}}}")
					.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (var output = exchange.getResponseBody()) { output.write(body); }
		});
		server.createContext("/api/v1/douyin/user/posts", exchange -> {
			byte[] body = ("{\"code\":200,\"message\":\"success\",\"data\":{"
					+ "\"items\":[{\"content_id\":\"456\",\"kind\":\"video\",\"description\":\"post\","
					+ "\"created_at\":\"1710000000\",\"web_url\":\"https://www.douyin.com/video/456\","
					+ "\"author\":{\"id\":\"author-1\",\"username\":\"author\"},"
					+ "\"media\":{\"type\":\"video\",\"url\":\"https://media.example/post.mp4\"}}],"
					+ "\"max_cursor\":\"20\",\"has_more\":0}}")
					.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (var output = exchange.getResponseBody()) { output.write(body); }
		});
		server.createContext("/api/v1/douyin/user", exchange -> {
			receivedApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
			String query = exchange.getRequestURI().getRawQuery();
			String bodyText = "{\"success\":true,\"data\":{\"user\":{\"sec_uid\":\"MS4-profile\"," +
					"\"unique_id\":\"profile-user\",\"nickname\":\"Profile User\"}}}";
			if (query == null || !query.contains("url=https%3A%2F%2Fwww.douyin.com%2Fuser%2Fprofile-user")) {
				exchange.sendResponseHeaders(400, -1);
				exchange.close();
				return;
			}
			byte[] body = bodyText.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (var output = exchange.getResponseBody()) { output.write(body); }
		});
		server.start();
		baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
		Global.dtkBaseUrl = baseUrl;
		Global.dtkApiKey = "test-key";
		Global.dtkApiPool = baseUrl + "|test-key";
	}

	@AfterEach
	void tearDown() {
		Global.dtkBaseUrl = oldBaseUrl;
		Global.dtkApiKey = oldApiKey;
		Global.dtkApiPool = oldApiPool;
		if (server != null) server.stop(0);
		if (executor != null) executor.shutdownNow();
	}

	@Test
	void unwrapsOfficialEnvelopeAndMapsDirectMedia() {
		DtkDouyinDataProvider provider = new DtkDouyinDataProvider(HttpClient.newHttpClient());

		assertThat(provider.fetchDirect("https://www.douyin.com/video/123"))
				.containsEntry("awemeid", "123")
				.containsEntry("videoplay", "https://media.example/video.mp4")
				.containsEntry("cover", "https://media.example/cover.jpg")
				.containsEntry("nickname", "author")
				.containsEntry("uid", "author-1");
		assertThat(receivedApiKey).hasValue("test-key");
	}

	@Test
	void unwrapsOfficialEnvelopeForWorkData() {
		String raw = new DtkDouyinDataProvider(HttpClient.newHttpClient()).fetchWorkData("123");
		assertThat(raw).contains("\"aweme_detail\"", "\"aweme_id\":\"123\"");
	}

	@Test
	void pollsAsyncTaskOnTheSameDtkNodeUntilWorkDataIsReady() {
		String raw = new DtkDouyinDataProvider(HttpClient.newHttpClient()).fetchWorkData("async-work");
		assertThat(raw).contains("\"aweme_id\":\"async-work\"").contains("https://media.example/async.mp4");
		assertThat(receivedApiKey).hasValue("test-key");
	}

	@Test
	void preservesTopLevelCoverForUnifiedDouyinDownloadPath() {
		String raw = new DtkDouyinDataProvider(HttpClient.newHttpClient()).fetchWorkData("top-level-cover");
		assertThat(raw).contains("https://media.example/top-level-cover.jpg");
	}

	@Test
	void normalizesNoWatermarkVideoWithoutFileExtension() {
		String raw = new DtkDouyinDataProvider(HttpClient.newHttpClient()).fetchWorkData("no-watermark");
		assertThat(raw).contains("video-no-extension").contains("video_play_addr");
	}

	@Test
	void prefersCdnCandidateFromUrlsOverDouyinPlayEndpoint() {
		String raw = new DtkDouyinDataProvider(HttpClient.newHttpClient()).fetchWorkData("urls-only");
		JSONObject detail = JSONObject.parseObject(raw).getJSONObject("aweme_detail");
		assertThat(detail.getJSONObject("video").getJSONObject("play_addr").getJSONArray("url_list").getString(0))
				.isEqualTo("https://media.example/cdn-video");
	}

	@Test
	void acceptsAwemeListAuthorEnvelopeAndNumericHasMore() {
		DtkDouyinDataProvider provider = new DtkDouyinDataProvider(HttpClient.newHttpClient());
		DouyinFetchEnvelope result = provider.fetchAuthorWorks(new DouyinFetchRequest(
				"sec-user", Set.of(), null, 0, 1, 1, DouyinFetchMode.INITIAL, 10, ""));

		assertThat(result.items()).singleElement().extracting(item -> item.getString("aweme_id"))
				.isEqualTo("456");
		assertThat(result.items().get(0).getString("desc")).isEqualTo("post");
		assertThat(result.items().get(0).getJSONArray("video_play_addr").getString(0))
				.isEqualTo("https://media.example/post.mp4");
		assertThat(result.backfillComplete()).isTrue();
	}

	@Test
	void fetchesAuthorProfileByUniqueIdThroughConfiguredDtkNode() {
		JSONObject profile = new DtkDouyinDataProvider(HttpClient.newHttpClient())
				.fetchAuthorProfileByUniqueId("profile-user");

		assertThat(profile).containsEntry("sec_uid", "MS4-profile")
				.containsEntry("unique_id", "profile-user")
				.containsEntry("nickname", "Profile User");
		assertThat(receivedApiKey).hasValue("test-key");
	}

	@Test
	void doesNotFallBackToLegacyBaseUrlWhenTheNodePoolIsEmpty() {
		Global.dtkApiPool = "";
		Global.dtkBaseUrl = baseUrl;
		Global.dtkApiKey = "legacy-key";

		assertThatThrownBy(() -> new DtkDouyinDataProvider(HttpClient.newHttpClient())
				.fetchAuthorProfile("sec-user"))
				.isInstanceOf(CollectFetchException.class)
				.hasMessageContaining("DTK 节点池未配置");
	}
}
