package com.flower.spirit.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PlatformCatalogTest {

	@Test
	void resolvesConfirmedAliasesToStableFormalDefinitions() {
		Map<String, String> aliases = new LinkedHashMap<>();
		aliases.put("抖音", "douyin");
		aliases.put("douyin", "douyin");
		aliases.put("哔哩哔哩", "bilibili");
		aliases.put("YouTube", "youtube");
		aliases.put("快手", "kuaishou");
		aliases.put("rednote", "xiaohongshu");
		aliases.put("小红书", "xiaohongshu");
		aliases.put("微博", "weibo");
		aliases.put("x", "twitter");
		aliases.put("Instagram", "instagram");
		aliases.put("TikTok", "tiktok");

		aliases.forEach((alias, expectedKey) -> {
			PlatformDefinition definition = PlatformCatalog.findByAlias(alias).orElseThrow();
			assertThat(definition.getKey()).isEqualTo(expectedKey);
			assertThat(definition.getSupportTier()).isEqualTo(PlatformSupportTier.FORMAL);
		});
	}

	@Test
	void exposesStableDisplayNamesForFormalPlatforms() {
		assertThat(PlatformCatalog.requireByKey("douyin").getDisplayName()).isEqualTo("抖音");
		assertThat(PlatformCatalog.requireByKey("bilibili").getDisplayName()).isEqualTo("哔哩");
		assertThat(PlatformCatalog.requireByKey("youtube").getDisplayName()).isEqualTo("YouTube");
		assertThat(PlatformCatalog.requireByKey("xiaohongshu").getDisplayName()).isEqualTo("小红书");
		assertThat(PlatformCatalog.requireByKey("twitter").getDisplayName()).isEqualTo("Twitter");
	}

	@Test
	void resolvesCanonicalKeyAndAllLegacyAliases() {
		assertThat(PlatformCatalog.canonicalKey("DOUYIN", "wrong-display")).isEqualTo("douyin");
		assertThat(PlatformCatalog.canonicalKey(null, "抖音")).isEqualTo("douyin");

		List<String> aliases = PlatformCatalog.aliases("douyin", "抖音");
		assertThat(aliases).contains("douyin", "抖音");
		assertThat(PlatformCatalog.aliases("douyin", "wrong-display")).doesNotContain("wrong-display");
	}

	@Test
	void createsGenericDefinitionForUnknownYtDlpExtractor() {
		PlatformDefinition definition = PlatformCatalog.definitionForExtractor("Vimeo:Review");

		assertThat(definition.getKey()).isEqualTo("vimeo_review");
		assertThat(definition.getDisplayName()).isEqualTo("Vimeo:Review");
		assertThat(definition.getSupportTier()).isEqualTo(PlatformSupportTier.GENERIC);
		assertThat(definition.getAliases()).contains("Vimeo:Review", "vimeo_review");
	}

	@Test
	void rejectsBlankExtractorNames() {
		assertThatThrownBy(() -> PlatformCatalog.definitionForExtractor("  "))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("extractor");
	}
}
