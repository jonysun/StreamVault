package com.flower.spirit.platform;

import java.util.List;

public final class DownloadResult {

	public enum Status {
		COMPLETED,
		QUEUED,
		FAILED
	}

	private final Status status;
	private final List<WorkMediaResource> mediaResources;
	private final String message;

	private DownloadResult(Status status, List<WorkMediaResource> mediaResources, String message) {
		this.status = status;
		this.mediaResources = mediaResources == null ? List.of() : List.copyOf(mediaResources);
		this.message = message;
	}

	public static DownloadResult completed(List<WorkMediaResource> mediaResources) {
		return new DownloadResult(Status.COMPLETED, mediaResources, null);
	}

	public static DownloadResult queued(String message) {
		return new DownloadResult(Status.QUEUED, List.of(), message);
	}

	public static DownloadResult failed(String message) {
		return new DownloadResult(Status.FAILED, List.of(), message);
	}

	public Status getStatus() {
		return status;
	}

	public List<WorkMediaResource> getMediaResources() {
		return mediaResources;
	}

	public String getMessage() {
		return message;
	}
}
