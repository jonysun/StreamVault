package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.flower.spirit.config.Global;
import com.sun.net.httpserver.HttpServer;

class DtkDouyinDataProviderTest {
	private HttpServer server;
	private String baseUrl;
	private String oldBaseUrl;
	private String oldApiKey;
	private ExecutorService executor;
	private AtomicReference<String> receivedApiKey;

	@BeforeEach
	void setUp() throws IOException {
		oldBaseUrl = Global.dtkBaseUrl;
		oldApiKey = Global.dtkApiKey;
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		executor = Executors.newSingleThreadExecutor();
		receivedApiKey = new AtomicReference<>();
		server.setExecutor(executor);
		server.createContext("/api/v1/douyin/video", exchange -> {
			receivedApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
			byte[] body = ("{\"success\":true,\"data\":{\"aweme_id\":\"123\",\"desc\":\"title\","
					+ "\"video\":{\"play_addr\":{\"url_list\":[\"https://media.example/video.mp4\"]},"
					+ "\"cover\":{\"url_list\":[\"https://media.example/cover.jpg\"]}},"
					+ "\"author\":{\"nickname\":\"author\"}},\"error\":null,\"meta\":{}}")
					.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (var output = exchange.getResponseBody()) { output.write(body); }
		});
		server.start();
		baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
		Global.dtkBaseUrl = baseUrl;
		Global.dtkApiKey = "test-key";
	}

	@AfterEach
	void tearDown() {
		Global.dtkBaseUrl = oldBaseUrl;
		Global.dtkApiKey = oldApiKey;
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
				.containsEntry("nickname", "author");
		assertThat(receivedApiKey).hasValue("test-key");
	}

	@Test
	void unwrapsOfficialEnvelopeForWorkData() {
		String raw = new DtkDouyinDataProvider(HttpClient.newHttpClient()).fetchWorkData("123");
		assertThat(raw).contains("\"aweme_detail\"", "\"aweme_id\":\"123\"");
	}
}
