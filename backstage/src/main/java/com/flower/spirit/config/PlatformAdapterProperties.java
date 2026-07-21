package com.flower.spirit.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "streamvault")
public class PlatformAdapterProperties {

	public enum Mode {
		LEGACY,
		NEW
	}

	private Map<String, Mode> adapter = new LinkedHashMap<>();

	public Map<String, Mode> getAdapter() {
		return adapter;
	}

	public void setAdapter(Map<String, Mode> adapter) {
		this.adapter = adapter == null ? new LinkedHashMap<>() : new LinkedHashMap<>(adapter);
	}

	public Mode modeFor(String platformKey) {
		if (platformKey == null || platformKey.trim().isEmpty()) {
			return Mode.LEGACY;
		}
		String key = platformKey.trim().toLowerCase(Locale.ROOT);
		return adapter.entrySet().stream()
				.filter(entry -> entry.getKey() != null && entry.getKey().trim().toLowerCase(Locale.ROOT).equals(key))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(Mode.LEGACY);
	}

	public boolean useNewAdapter(String platformKey) {
		return modeFor(platformKey) == Mode.NEW;
	}
}
