package com.flower.spirit.service;

public enum CollectRunState {
	QUEUED,
	FETCHING,
	PROCESSING,
	COMPLETED,
	FETCH_FAILED,
	DB_FAILED,
	INTERRUPTED,
	SKIPPED_PAUSED,
	CANCELLED
}
