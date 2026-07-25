package com.flower.spirit.service;

public record AuthorEnrichmentEnqueueResult(int jobId, String state, boolean created, boolean promoted) {
}
