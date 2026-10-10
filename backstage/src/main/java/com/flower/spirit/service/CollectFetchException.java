package com.flower.spirit.service;

public class CollectFetchException extends RuntimeException {

	private static final long serialVersionUID = 1L;
	private final String errorCode;
	private final Long retryAfterSeconds;

	public CollectFetchException(String errorCode, String message) {
		this(errorCode, message, null, null);
	}

	public CollectFetchException(String errorCode, String message, Throwable cause) {
		this(errorCode, message, cause, null);
	}

	public CollectFetchException(String errorCode, String message, Long retryAfterSeconds) {
		this(errorCode, message, null, retryAfterSeconds);
	}

	public CollectFetchException(String errorCode, String message, Throwable cause, Long retryAfterSeconds) {
		super(message, cause);
		this.errorCode = errorCode;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public String getErrorCode() {
		return errorCode;
	}

	public Long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}
}
