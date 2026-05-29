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
}
