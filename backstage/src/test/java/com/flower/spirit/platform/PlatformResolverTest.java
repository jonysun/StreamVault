package com.flower.spirit.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PlatformResolverTest {

	private final PlatformResolver resolver = new PlatformResolver();

	@Test
	void extractsFirstFormalUrlFromSharedText() {
		PlatformResolver.Resolution result = resolver.resolveRequired(
				"介绍 https://example.com/unsupported 然后看 https://youtu.be/video-1。谢谢");

		assertThat(result.url()).isEqualTo("https://youtu.be/video-1");
		assertThat(result.platform().getKey()).isEqualTo("youtube");
		assertThat(result.platform().getSupportTier()).isEqualTo(PlatformSupportTier.FORMAL);
	}

	@Test
	void recognizesFormalDesktopMobileAndShortDomains() {
		Map<String, String> urls = new LinkedHashMap<>();
		urls.put("https://v.douyin.com/abc", "douyin");
		urls.put("https://b23.tv/abc", "bilibili");
		urls.put("https://m.youtube.com/shorts/abc", "youtube");
		urls.put("https://v.kuaishou.com/abc", "kuaishou");
		urls.put("https://xhslink.com/a/abc", "xiaohongshu");
		urls.put("https://m.weibo.cn/detail/abc", "weibo");
		urls.put("https://x.com/user/status/1", "twitter");
		urls.put("https://www.instagram.com/reel/abc", "instagram");
		urls.put("https://vm.tiktok.com/abc", "tiktok");

		urls.forEach((url, key) -> assertThat(resolver.resolveRequired(url).platform().getKey()).isEqualTo(key));
	}

	@Test
	void treatsUnknownHttpUrlAsGenericWithoutTrustingLookalikeHosts() {
		PlatformResolver.Resolution unknown = resolver.resolveRequired("https://vimeo.com/123");
		PlatformResolver.Resolution lookalike = resolver.resolveRequired("https://youtube.com.example.org/watch/1");

		assertThat(unknown.platform().getKey()).isEqualTo("generic");
		assertThat(unknown.platform().getSupportTier()).isEqualTo(PlatformSupportTier.GENERIC);
		assertThat(lookalike.platform().getSupportTier()).isEqualTo(PlatformSupportTier.GENERIC);
	}

	@Test
	void rejectsInputWithoutAnHttpUrl() {
		assertThatThrownBy(() -> resolver.resolveRequired("not a link"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("HTTP URL");
	}
}
