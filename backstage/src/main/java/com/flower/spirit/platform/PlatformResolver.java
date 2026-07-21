package com.flower.spirit.platform;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class PlatformResolver {

	private static final Pattern HTTP_URL = Pattern.compile("(?i)https?://[^\\s<>\\\"'，。；！？）》】]+");
	private static final String TRAILING_PUNCTUATION = "),.;!?]}，。；！？）】》";
	private static final Map<String, List<String>> DOMAINS = domains();

	public Optional<Resolution> resolve(String input) {
		if (input == null || input.trim().isEmpty()) {
			return Optional.empty();
		}
		Resolution firstGeneric = null;
		Matcher matcher = HTTP_URL.matcher(input);
		while (matcher.find()) {
			String url = trimTrailingPunctuation(matcher.group());
			String host = host(url);
			if (host == null) {
				continue;
			}
			Optional<PlatformDefinition> formal = formalPlatform(host);
			if (formal.isPresent()) {
				return Optional.of(new Resolution(input, url, formal.get()));
			}
			if (firstGeneric == null) {
				PlatformDefinition generic = new PlatformDefinition("generic", host,
						PlatformSupportTier.GENERIC, Set.of(host));
				firstGeneric = new Resolution(input, url, generic);
			}
		}
		return Optional.ofNullable(firstGeneric);
	}

	public Resolution resolveRequired(String input) {
		return resolve(input).orElseThrow(() -> new IllegalArgumentException("input does not contain a valid HTTP URL"));
	}

	private Optional<PlatformDefinition> formalPlatform(String host) {
		for (Map.Entry<String, List<String>> entry : DOMAINS.entrySet()) {
			for (String domain : entry.getValue()) {
				if (host.equals(domain) || host.endsWith("." + domain)) {
					return Optional.of(PlatformCatalog.requireByKey(entry.getKey()));
				}
			}
		}
		return Optional.empty();
	}

	private static String host(String url) {
		try {
			String host = URI.create(url).getHost();
			return host == null ? null : host.toLowerCase(Locale.ROOT);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String trimTrailingPunctuation(String value) {
		int end = value.length();
		while (end > 0 && TRAILING_PUNCTUATION.indexOf(value.charAt(end - 1)) >= 0) {
			end--;
		}
		return value.substring(0, end);
	}

	private static Map<String, List<String>> domains() {
		Map<String, List<String>> values = new LinkedHashMap<>();
		values.put("douyin", List.of("douyin.com", "iesdouyin.com"));
		values.put("bilibili", List.of("bilibili.com", "b23.tv"));
		values.put("youtube", List.of("youtube.com", "youtu.be"));
		values.put("kuaishou", List.of("kuaishou.com", "gifshow.com"));
		values.put("xiaohongshu", List.of("xiaohongshu.com", "xhslink.com"));
		values.put("weibo", List.of("weibo.com", "weibo.cn"));
		values.put("twitter", List.of("twitter.com", "x.com"));
		values.put("instagram", List.of("instagram.com", "instagr.am"));
		values.put("tiktok", List.of("tiktok.com"));
		return Map.copyOf(values);
	}

	public record Resolution(String originalInput, String url, PlatformDefinition platform) {
	}
}
