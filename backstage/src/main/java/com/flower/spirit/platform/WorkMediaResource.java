package com.flower.spirit.platform;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public final class WorkMediaResource {

	public enum Type {
		VIDEO,
		AUDIO,
		IMAGE
	}

	private final int order;
	private final Type type;
	private final String sourceUrl;
	private final Path localPath;
	private final String expectedExtension;
	private final Map<String, String> requestHeaders;

	public WorkMediaResource(int order, Type type, String sourceUrl, Path localPath, String expectedExtension,
			Map<String, String> requestHeaders) {
		if (order < 0) {
			throw new IllegalArgumentException("order must not be negative");
		}
		if (!hasText(sourceUrl) && localPath == null) {
			throw new IllegalArgumentException("sourceUrl or localPath is required");
		}
		this.order = order;
		this.type = Objects.requireNonNull(type, "type");
		this.sourceUrl = trimToNull(sourceUrl);
		this.localPath = localPath;
		this.expectedExtension = normalizeExtension(expectedExtension);
		this.requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
	}

	public int getOrder() {
		return order;
	}

	public Type getType() {
		return type;
	}

	public String getSourceUrl() {
		return sourceUrl;
	}

	public Path getLocalPath() {
		return localPath;
	}

	public String getExpectedExtension() {
		return expectedExtension;
	}

	public Map<String, String> getRequestHeaders() {
		return requestHeaders;
	}

	private static String normalizeExtension(String value) {
		String extension = trimToNull(value);
		if (extension != null && extension.startsWith(".")) {
			return extension.substring(1);
		}
		return extension;
	}

	private static String trimToNull(String value) {
		return hasText(value) ? value.trim() : null;
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
}
