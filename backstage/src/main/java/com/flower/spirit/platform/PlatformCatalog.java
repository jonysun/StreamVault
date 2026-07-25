package com.flower.spirit.platform;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class PlatformCatalog {

	private static final Map<String, PlatformDefinition> BY_KEY = new LinkedHashMap<>();
	private static final Map<String, PlatformDefinition> BY_ALIAS = new LinkedHashMap<>();

	static {
		register("douyin", "抖音", "抖音");
		register("bilibili", "哔哩", "bili", "哔哩哔哩", "B站");
		register("youtube", "YouTube", "youtu.be");
		register("kuaishou", "快手", "快手");
		register("xiaohongshu", "小红书", "rednote", "xhs", "小红书");
		register("weibo", "微博", "微博");
		register("twitter", "Twitter", "x", "X");
		register("instagram", "Instagram");
		register("tiktok", "TikTok");
	}

	private PlatformCatalog() {
	}

	public static Optional<PlatformDefinition> findByAlias(String value) {
		if (!hasText(value)) {
			return Optional.empty();
		}
		return Optional.ofNullable(BY_ALIAS.get(normalize(value)));
	}

	public static PlatformDefinition requireByKey(String key) {
		if (!hasText(key)) {
			throw new IllegalArgumentException("platform key must not be blank");
		}
		PlatformDefinition definition = BY_KEY.get(normalize(key));
		if (definition == null) {
			throw new IllegalArgumentException("unknown platform key: " + key);
		}
		return definition;
	}

	public static String canonicalKey(String explicitKey, String legacyPlatform) {
		Optional<PlatformDefinition> definition = findByAlias(explicitKey);
		if (definition.isEmpty()) {
			definition = findByAlias(legacyPlatform);
		}
		if (definition.isPresent()) {
			return definition.get().getKey();
		}
		if (hasText(explicitKey)) {
			return normalize(explicitKey);
		}
		return hasText(legacyPlatform) ? normalize(legacyPlatform) : null;
	}

	public static List<String> aliases(String platformKey, String legacyPlatform) {
		LinkedHashSet<String> aliases = new LinkedHashSet<>();
		Optional<PlatformDefinition> definition = findByAlias(platformKey);
		if (definition.isEmpty()) {
			definition = findByAlias(legacyPlatform);
		}
		definition.ifPresent(value -> aliases.addAll(value.getAliases()));
		String resolvedKey = definition.map(PlatformDefinition::getKey).orElse(null);
		boolean compatibleLegacy = resolvedKey == null
				|| findByAlias(legacyPlatform)
						.map(value -> value.getKey().equals(resolvedKey))
						.orElse(false);
		if (hasText(legacyPlatform) && compatibleLegacy) {
			aliases.add(legacyPlatform.trim());
		}
		if (aliases.isEmpty() && hasText(platformKey)) {
			aliases.add(platformKey.trim());
		}
		return List.copyOf(aliases);
	}

	public static PlatformDefinition definitionForExtractor(String extractor) {
		if (!hasText(extractor)) {
			throw new IllegalArgumentException("extractor must not be blank");
		}
		Optional<PlatformDefinition> known = findByAlias(extractor);
		if (known.isPresent()) {
			return known.get();
		}
		String displayName = extractor.trim();
		String key = sanitizeExtractorKey(displayName);
		return new PlatformDefinition(key, displayName, PlatformSupportTier.GENERIC, Set.of(displayName, key));
	}

	static String sanitizeExtractorKey(String extractor) {
		String key = normalize(extractor).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
		return key.isEmpty() ? "generic" : key;
	}

	private static void register(String key, String displayName, String... aliases) {
		PlatformDefinition definition = new PlatformDefinition(key, displayName, PlatformSupportTier.FORMAL,
				aliases == null ? Set.of() : Set.of(aliases));
		BY_KEY.put(normalize(key), definition);
		for (String alias : definition.getAliases()) {
			BY_ALIAS.put(normalize(alias), definition);
		}
	}

	private static String normalize(String value) {
		return value.trim().toLowerCase(Locale.ROOT);
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
}
