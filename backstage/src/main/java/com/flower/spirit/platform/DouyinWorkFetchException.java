package com.flower.spirit.platform;

import java.io.IOException;

/** Safe, typed failure returned by the F2 single-work metadata command. */
public class DouyinWorkFetchException extends IOException {

	private static final long serialVersionUID = 1L;
	private final String errorCode;
	private final String faultDomain;
	private final boolean retryable;
	private final boolean cooldownApplied;
	private final Integer upstreamStatus;
	private final String exceptionType;
	private final DouyinF2Diagnostics diagnostics;

	public DouyinWorkFetchException(String errorCode, String message, String faultDomain,
			boolean retryable, boolean cooldownApplied, Integer upstreamStatus, String exceptionType) {
		this(errorCode, message, faultDomain, retryable, cooldownApplied, upstreamStatus, exceptionType,
				DouyinF2Diagnostics.from(null));
	}

	public DouyinWorkFetchException(String errorCode, String message, String faultDomain,
			boolean retryable, boolean cooldownApplied, Integer upstreamStatus, String exceptionType,
			DouyinF2Diagnostics diagnostics) {
		super(message);
		this.errorCode = errorCode;
		this.faultDomain = faultDomain;
		this.retryable = retryable;
		this.cooldownApplied = cooldownApplied;
		this.upstreamStatus = upstreamStatus;
		this.exceptionType = exceptionType;
		this.diagnostics = diagnostics == null ? DouyinF2Diagnostics.from(null) : diagnostics;
	}

	public DouyinWorkFetchException(String errorCode, String message, String faultDomain,
			boolean retryable, boolean cooldownApplied, Integer upstreamStatus, String exceptionType,
			Throwable cause) {
		this(errorCode, message, faultDomain, retryable, cooldownApplied, upstreamStatus, exceptionType);
		initCause(cause);
	}

	public String errorCode() { return errorCode; }
	public String faultDomain() { return faultDomain; }
	public boolean retryable() { return retryable; }
	public boolean cooldownApplied() { return cooldownApplied; }
	public Integer upstreamStatus() { return upstreamStatus; }
	public String exceptionType() { return exceptionType; }
	public DouyinF2Diagnostics diagnostics() { return diagnostics; }
}
