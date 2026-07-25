package com.flower.spirit.service;

public enum AuthorEnrichmentState {
	QUEUED,
	RUNNING,
	RETRY_WAIT,
	COMPLETED,
	FAILED
}
