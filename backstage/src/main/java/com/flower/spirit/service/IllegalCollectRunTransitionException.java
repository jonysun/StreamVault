package com.flower.spirit.service;

public class IllegalCollectRunTransitionException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	public IllegalCollectRunTransitionException(long runId, CollectRunState expected, CollectRunState next) {
		super("Collect run " + runId + " cannot transition from " + expected + " to " + next);
	}
}
