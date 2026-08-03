package com.flower.spirit.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class HttpMediaDownloaderTest {

	@TempDir Path tempDir;

	@Test
	void retriesHttp431OnceWithoutCookieAndPreservesOtherHeaders() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		HttpServer server = server(exchange -> {
			int attempt = requests.incrementAndGet();
			assertThat(exchange.getRequestHeaders().getFirst("Referer")).isEqualTo("https://www.douyin.com/");
			if (attempt == 1) {
				assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isEqualTo("large-cookie");
				exchange.sendResponseHeaders(431, -1);
			} else {
				assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
				byte[] body = "media".getBytes(java.nio.charset.StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			}
			exchange.close();
		});

		try {
			Path result = HttpMediaDownloader.download(url(server), tempDir.resolve("video.mp4"), "large-cookie",
					Map.of("Referer", "https://www.douyin.com/"));
			assertThat(requests).hasValue(2);
			assertThat(Files.readString(result)).isEqualTo("media");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void doesNotRetryOtherHttpFailuresWithoutCookie() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		HttpServer server = server(exchange -> {
			requests.incrementAndGet();
			exchange.sendResponseHeaders(403, -1);
			exchange.close();
		});

		try {
			assertThatThrownBy(() -> HttpMediaDownloader.download(url(server), tempDir.resolve("video.mp4"),
					"cookie", Map.of())).isInstanceOf(IOException.class).hasMessageContaining("HTTP 403");
			assertThat(requests).hasValue(1);
		} finally {
			server.stop(0);
		}
	}

	private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/media", handler);
		server.start();
		return server;
	}

	private String url(HttpServer server) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/media";
	}
}
