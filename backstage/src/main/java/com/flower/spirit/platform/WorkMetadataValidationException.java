package com.flower.spirit.platform;

public class WorkMetadataValidationException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	public WorkMetadataValidationException(String message) {
		super(message);
	}

	public WorkMetadataValidationException(String message, Throwable cause) {
		super(message, cause);
	}
}
