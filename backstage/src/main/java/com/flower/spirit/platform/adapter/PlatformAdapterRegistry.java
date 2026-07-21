package com.flower.spirit.platform.adapter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.PlatformDefinition;

@Component
public class PlatformAdapterRegistry {

	private final Map<String, PlatformWorkAdapter> adaptersByPlatform;

	public PlatformAdapterRegistry(List<PlatformWorkAdapter> adapters) {
		Map<String, PlatformWorkAdapter> registered = new LinkedHashMap<>();
		if (adapters != null) {
			for (PlatformWorkAdapter adapter : adapters) {
				if (adapter == null) {
					continue;
				}
				String key = canonicalKey(adapter.platformKey());
				PlatformWorkAdapter existing = registered.putIfAbsent(key, adapter);
				if (existing != null) {
					throw new IllegalStateException("multiple platform adapters registered for: " + key);
				}
			}
		}
		this.adaptersByPlatform = Map.copyOf(registered);
	}

	public Optional<PlatformWorkAdapter> findByPlatformKey(String platformKey) {
		if (!hasText(platformKey)) {
			return Optional.empty();
		}
		PlatformWorkAdapter exact = adaptersByPlatform.get(canonicalKey(platformKey));
		if (exact != null) return Optional.of(exact);
		if (PlatformCatalog.findByAlias(platformKey).isEmpty()) {
			return Optional.ofNullable(adaptersByPlatform.get("generic"));
		}
		return Optional.empty();
	}

	public PlatformWorkAdapter requireByPlatformKey(String platformKey) {
		return findByPlatformKey(platformKey)
				.orElseThrow(() -> new IllegalArgumentException("no platform adapter registered for: " + platformKey));
	}

	public Optional<PlatformWorkAdapter> findSupporting(String input) {
		PlatformWorkAdapter match = null;
		for (PlatformWorkAdapter adapter : adaptersByPlatform.values()) {
			if (!adapter.supports(input)) {
				continue;
			}
			if (match != null) {
				throw new IllegalStateException("multiple platform adapters support the same input: "
						+ match.platformKey() + ", " + adapter.platformKey());
			}
			match = adapter;
		}
		return Optional.ofNullable(match);
	}

	public int size() {
		return adaptersByPlatform.size();
	}

	private static String canonicalKey(String value) {
		if (!hasText(value)) {
			throw new IllegalArgumentException("adapter platform key must not be blank");
		}
		return PlatformCatalog.findByAlias(value)
				.map(PlatformDefinition::getKey)
				.orElseGet(() -> value.trim().toLowerCase(Locale.ROOT));
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
}
