package com.flower.spirit.service;

public record PauseDecision(boolean allowed, String controlKey, String reason) {

	public static PauseDecision permit() {
		return new PauseDecision(true, null, null);
	}

	public static PauseDecision paused(String controlKey, String reason) {
		return new PauseDecision(false, controlKey, reason);
	}
}
