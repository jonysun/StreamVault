package com.flower.spirit.platform;

import java.time.Instant;

public class DouyinGlobalCooldownException extends WorkMetadataValidationException {

	private static final long serialVersionUID = 1L;
	private final Instant retryAt;
	private final boolean actualUpstreamFailure;

	public DouyinGlobalCooldownException(String message, Instant retryAt) {
		this(message, retryAt, false, null);
	}

	public DouyinGlobalCooldownException(String message, Instant retryAt, boolean actualUpstreamFailure,
			Throwable cause) {
		super(message);
		this.retryAt = retryAt;
		this.actualUpstreamFailure = actualUpstreamFailure;
		if (cause != null) initCause(cause);
	}

	public Instant retryAt() {
		return retryAt;
	}

	public boolean actualUpstreamFailure() { return actualUpstreamFailure; }
}
