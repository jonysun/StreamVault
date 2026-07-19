package com.flower.spirit.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import jakarta.persistence.Column;

class VideoDataEntityTest {

	@Test
	void videoinfoIsReadOnlyLegacyMirror() throws Exception {
		Field field = VideoDataEntity.class.getDeclaredField("videoinfo");
		Column column = field.getAnnotation(Column.class);

		assertThat(column).isNotNull();
		assertThat(column.insertable()).isFalse();
		assertThat(column.updatable()).isFalse();
	}

	@Test
	void setVideoinfoBackfillsCanonicalJsonData() {
		VideoDataEntity video = new VideoDataEntity();

		video.setVideoinfo("{\"aid\":\"1\"}");

		assertThat(video.getJsonData()).isEqualTo("{\"aid\":\"1\"}");
		assertThat(video.getVideoinfo()).isEqualTo("{\"aid\":\"1\"}");
	}

	@Test
	void getVideoinfoFallsBackToJsonData() {
		VideoDataEntity video = new VideoDataEntity();

		video.setJsonData("{\"aid\":\"2\"}");

		assertThat(video.getVideoinfo()).isEqualTo("{\"aid\":\"2\"}");
	}
}
