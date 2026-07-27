package com.flower.spirit.service;

public class CollectDownloadException extends RuntimeException {

	private static final long serialVersionUID = 1L;
	private final String errorCode;
	private final boolean retryable;

	public CollectDownloadException(String errorCode, boolean retryable, String message) {
		super(message);
		this.errorCode = errorCode;
		this.retryable = retryable;
	}

	public CollectDownloadException(String errorCode, boolean retryable, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
		this.retryable = retryable;
	}

	public String errorCode() {
		return errorCode;
	}

	public boolean retryable() {
		return retryable;
	}
}
