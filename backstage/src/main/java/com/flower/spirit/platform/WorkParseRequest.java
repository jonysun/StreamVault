package com.flower.spirit.platform;

public final class WorkParseRequest {

	private final String input;
	private final String url;
	private final boolean preview;
	private final String rawMetadata;

	public WorkParseRequest(String input, String url, boolean preview) {
		this(input, url, preview, null);
	}

	public WorkParseRequest(String input, String url, boolean preview, String rawMetadata) {
		if (url == null || url.trim().isEmpty()) {
			throw new IllegalArgumentException("url must not be blank");
		}
		this.input = input;
		this.url = url.trim();
		this.preview = preview;
		this.rawMetadata = rawMetadata;
	}

	public String getInput() {
		return input;
	}

	public String getUrl() {
		return url;
	}

	public boolean isPreview() {
		return preview;
	}

	public String getRawMetadata() {
		return rawMetadata;
	}
}
