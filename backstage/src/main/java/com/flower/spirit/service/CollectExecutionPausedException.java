package com.flower.spirit.service;

public class CollectExecutionPausedException extends RuntimeException {

	private static final long serialVersionUID = 1L;
	private final String controlKey;

	public CollectExecutionPausedException(String controlKey, String reason) {
		super(reason == null || reason.isBlank() ? "后台任务已暂停: " + controlKey : reason);
		this.controlKey = controlKey;
	}

	public String getControlKey() {
		return controlKey;
	}
}
