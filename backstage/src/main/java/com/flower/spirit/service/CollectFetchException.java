package com.flower.spirit.service;

public class CollectFetchException extends RuntimeException {

	private static final long serialVersionUID = 1L;
	private final String errorCode;

	public CollectFetchException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public CollectFetchException(String errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public String getErrorCode() {
		return errorCode;
	}
}
