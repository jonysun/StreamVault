package com.flower.spirit.platform.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

final class HttpMediaDownloader {

	private static final OkHttpClient CLIENT = new OkHttpClient();

	private HttpMediaDownloader() {
	}

	static Path download(String url, Path destination, String cookie, Map<String, String> headers) throws IOException {
		Path target = destination.toAbsolutePath().normalize();
		Files.createDirectories(target.getParent());
		Request.Builder request = new Request.Builder().url(url);
		if (headers != null) headers.forEach(request::header);
		if (cookie != null && !cookie.trim().isEmpty()) request.header("Cookie", cookie);
		try (Response response = CLIENT.newCall(request.build()).execute()) {
			if (!response.isSuccessful() || response.body() == null) {
				throw new IOException("media request failed with HTTP " + response.code());
			}
			try (InputStream input = response.body().byteStream()) {
				Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		return target;
	}
}
