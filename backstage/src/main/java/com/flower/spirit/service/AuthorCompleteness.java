package com.flower.spirit.service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public record AuthorCompleteness(Set<AuthorField> missingFields) {

	public AuthorCompleteness {
		missingFields = missingFields == null || missingFields.isEmpty()
				? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(missingFields));
	}

	public boolean isComplete() {
		return missingFields.isEmpty();
	}
}
