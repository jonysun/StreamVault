package com.flower.spirit.service;

public enum JobState {
	QUEUED,
	RUNNING,
	RETRY_WAIT,
	COMPLETED,
	FAILED,
	CANCELLED
}
