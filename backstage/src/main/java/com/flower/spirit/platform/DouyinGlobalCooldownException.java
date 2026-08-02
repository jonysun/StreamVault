package com.flower.spirit.platform;

import java.time.Instant;

public class DouyinGlobalCooldownException extends WorkMetadataValidationException {

	private static final long serialVersionUID = 1L;
	private final Instant retryAt;

	public DouyinGlobalCooldownException(String message, Instant retryAt) {
		super(message);
		this.retryAt = retryAt;
	}

	public Instant retryAt() {
		return retryAt;
	}
}
