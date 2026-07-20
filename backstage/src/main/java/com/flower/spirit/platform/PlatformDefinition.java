package com.flower.spirit.platform;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class PlatformDefinition {

	private final String key;
	private final String displayName;
	private final PlatformSupportTier supportTier;
	private final Set<String> aliases;

	public PlatformDefinition(String key, String displayName, PlatformSupportTier supportTier, Set<String> aliases) {
		this.key = requireText(key, "key");
		this.displayName = requireText(displayName, "displayName");
		this.supportTier = Objects.requireNonNull(supportTier, "supportTier");
		LinkedHashSet<String> values = new LinkedHashSet<>();
		values.add(this.key);
		values.add(this.displayName);
		if (aliases != null) {
			aliases.stream().filter(PlatformDefinition::hasText).map(String::trim).forEach(values::add);
		}
		this.aliases = Collections.unmodifiableSet(values);
	}

	public String getKey() {
		return key;
	}

	public String getDisplayName() {
		return displayName;
	}

	public PlatformSupportTier getSupportTier() {
		return supportTier;
	}

	public Set<String> getAliases() {
		return aliases;
	}

	private static String requireText(String value, String field) {
		if (!hasText(value)) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.trim();
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
}
