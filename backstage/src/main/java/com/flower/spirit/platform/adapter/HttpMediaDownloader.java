package com.flower.spirit.platform.adapter;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flower.spirit.platform.WorkMediaResource;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

final class HttpMediaDownloader {

	private static final OkHttpClient CLIENT = new OkHttpClient();
	private static final Logger logger = LoggerFactory.getLogger(HttpMediaDownloader.class);

	private HttpMediaDownloader() {
	}

	static Path download(String url, Path destination, String cookie, Map<String, String> headers) throws IOException {
		return download(url, destination, cookie, headers, null);
	}

	static Path download(String url, Path destination, String cookie, Map<String, String> headers,
			WorkMediaResource.Type expectedType) throws IOException {
		URI uri = validateUrl(url);
		Path target = destination.toAbsolutePath().normalize();
		Files.createDirectories(target.getParent());
		Response response = execute(url, cookie, headers, true);
		if (response.code() == 431 && cookie != null && !cookie.trim().isEmpty()) {
			response.close();
			response = execute(url, cookie, headers, false);
		}
		try (Response finalResponse = response) {
			String contentType = finalResponse.body() == null || finalResponse.body().contentType() == null
					? null : finalResponse.body().contentType().toString();
			long contentLength = finalResponse.body() == null ? -1 : finalResponse.body().contentLength();
			logger.info("[MediaDownload] host={} status={} contentType={} contentLength={} expectedType={} queryPresent={}",
					host(uri), finalResponse.code(), contentType, contentLength, expectedType, uri.getQuery() != null);
			if (!finalResponse.isSuccessful() || finalResponse.body() == null) {
				throw new IOException("media request failed host=" + host(uri) + " with HTTP " + finalResponse.code());
			}
			if (isErrorContentType(contentType)) {
				throw new IOException("media request returned non-media content host=" + host(uri)
						+ " contentType=" + contentType);
			}
			if (isWrongMediaType(contentType, expectedType)) {
				throw new IOException("media request returned unexpected content host=" + host(uri)
						+ " contentType=" + contentType + " expectedType=" + expectedType);
			}
			try (InputStream input = new BufferedInputStream(finalResponse.body().byteStream())) {
				input.mark(4096);
				byte[] prefix = input.readNBytes(4096);
				if (prefix.length == 0) throw new IOException("media response was empty host=" + host(uri));
				if (looksLikeChallenge(prefix, expectedType)) {
					throw new IOException("media response was an upstream challenge host=" + host(uri));
				}
				input.reset();
				Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		return target;
	}

	private static URI validateUrl(String value) throws IOException {
		try {
			URI uri = URI.create(value);
			if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
					|| uri.getHost() == null || uri.getHost().isBlank()) {
				throw new IllegalArgumentException();
			}
			return uri;
		} catch (RuntimeException error) {
			throw new IOException("media URL is invalid", error);
		}
	}

	private static String host(URI uri) {
		return uri == null || uri.getHost() == null ? "unknown" : uri.getHost();
	}

	private static boolean isErrorContentType(String contentType) {
		if (contentType == null) return false;
		String normalized = contentType.toLowerCase(Locale.ROOT);
		return normalized.startsWith("text/html") || normalized.startsWith("application/json")
				|| normalized.startsWith("application/problem+json") || normalized.startsWith("text/xml");
	}

	private static boolean isWrongMediaType(String contentType, WorkMediaResource.Type expectedType) {
		if (contentType == null || expectedType == null) return false;
		String normalized = contentType.toLowerCase(Locale.ROOT);
		return (expectedType == WorkMediaResource.Type.VIDEO && normalized.startsWith("image/"))
				|| (expectedType == WorkMediaResource.Type.IMAGE
						&& (normalized.startsWith("video/") || normalized.startsWith("audio/")));
	}

	private static boolean looksLikeChallenge(byte[] prefix, WorkMediaResource.Type expectedType) {
		String text = new String(prefix, java.nio.charset.StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
		if (text.startsWith("<html") || text.startsWith("<!doctype") || text.startsWith("{")
				|| text.startsWith("[")) return true;
		if (expectedType != null && isMostlyPrintable(prefix)) return true;
		return text.startsWith("blocked by") || text.contains("argussecurityplugin")
				|| text.contains("uifid not found") || text.contains("access denied")
				|| text.contains("forbidden") || text.contains("captcha") || text.contains("challenge")
				|| text.contains("login required") || text.contains("unauthorized");
	}

	private static boolean isMostlyPrintable(byte[] prefix) {
		if (prefix.length == 0) return false;
		int printable = 0;
		for (byte value : prefix) {
			int unsigned = value & 0xff;
			if (unsigned == '\t' || unsigned == '\n' || unsigned == '\r'
					|| (unsigned >= 32 && unsigned <= 126)) printable++;
		}
		return printable >= prefix.length * 0.98;
	}

	private static Response execute(String url, String cookie, Map<String, String> headers,
			boolean includeCookie) throws IOException {
		Request.Builder request = new Request.Builder().url(url);
		if (headers != null) headers.forEach(request::header);
		if (includeCookie && cookie != null && !cookie.trim().isEmpty()) request.header("Cookie", cookie);
		return CLIENT.newCall(request.build()).execute();
	}
}
