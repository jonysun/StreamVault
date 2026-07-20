package com.flower.spirit.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DouyinSourceUrlUtilTest {

	@Test
	void buildsVideoSourceUrlFromVideoId() {
		assertThat(DouyinSourceUrlUtil.video("7601115281875897640"))
				.isEqualTo("https://www.douyin.com/video/7601115281875897640");
	}

	@Test
	void buildsNoteSourceUrlFromNoteId() {
		assertThat(DouyinSourceUrlUtil.note("7601115281875897640"))
				.isEqualTo("https://www.douyin.com/note/7601115281875897640");
	}

	@Test
	void buildsGraphicSourceUrlFromAuthorUidAndAwemeId() {
		assertThat(DouyinSourceUrlUtil.graphic("MS4wLjABAAAACj7u2tUNMyME9DcICOy5cf_heN69M6_xArnEEywXvgw", "7622696915705286079"))
				.isEqualTo("https://www.douyin.com/user/MS4wLjABAAAACj7u2tUNMyME9DcICOy5cf_heN69M6_xArnEEywXvgw?modal_id=7622696915705286079");
	}

	@Test
	void graphicSourceUrlRequiresAuthorUidAndAwemeId() {
		assertThat(DouyinSourceUrlUtil.graphic(null, "7622696915705286079")).isNull();
		assertThat(DouyinSourceUrlUtil.graphic("84583932458", "7622696915705286079")).isNull();
		assertThat(DouyinSourceUrlUtil.graphic("MS4w", null)).isNull();
	}
}
