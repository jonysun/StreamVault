package com.flower.spirit.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthorIdentityUtilTest {

	@Test
	void douyinUsesSecUidAndRejectsNumericUid() {
		assertThat(AuthorIdentityUtil.canonicalAuthorUid("抖音", "84583932458", "MS4wLjABAAAAstable"))
				.isEqualTo("MS4wLjABAAAAstable");
		assertThat(AuthorIdentityUtil.canonicalAuthorUid("douyin", "84583932458", null)).isNull();
	}

	@Test
	void otherPlatformsKeepTheirStableAuthorId() {
		assertThat(AuthorIdentityUtil.canonicalAuthorUid("哔哩哔哩", "12345", null)).isEqualTo("12345");
	}

	@Test
	void usernameIsIndependentFromCanonicalUid() {
		assertThat(AuthorIdentityUtil.canonicalUsername(" public_handle ", "raw_handle"))
				.isEqualTo("public_handle");
		assertThat(AuthorIdentityUtil.canonicalUsername(null, "raw_handle")).isEqualTo("raw_handle");
	}

	@Test
	void douyinHomepageRequiresCanonicalUid() {
		assertThat(AuthorIdentityUtil.douyinHomepage("84583932458")).isNull();
		assertThat(AuthorIdentityUtil.douyinHomepage("MS4wLjABAAAAstable"))
				.isEqualTo("https://www.douyin.com/user/MS4wLjABAAAAstable");
		assertThat(AuthorIdentityUtil.sanitizeHomepage("抖音", "84583932458",
				"https://www.douyin.com/user/84583932458")).isNull();
	}

	@Test
	void authorKeyUsesPlatformKeyAndSecUidInsteadOfDisplayMetadata() {
		AuthorIdentityUtil.AuthorKey key = AuthorIdentityUtil.authorKey(
				"DOUYIN", "unexpected-display", "84583932458", "MS4wLjABAAAAstable");

		assertThat(key.platformKey()).isEqualTo("douyin");
		assertThat(key.authorUid()).isEqualTo("MS4wLjABAAAAstable");
		assertThat(AuthorIdentityUtil.authorKey("douyin", "抖音", "84583932458", null)).isNull();
	}
}
