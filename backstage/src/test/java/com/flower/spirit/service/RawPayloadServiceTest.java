package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.flower.spirit.entity.VideoDataEntity;

class RawPayloadServiceTest {

	private final RawPayloadService service = new RawPayloadService();

	@Test
	void canonicalJsonDataWinsOverLegacyVideoinfo() {
		VideoDataEntity video = new VideoDataEntity();
		video.setJsonData("canonical");
		video.setVideoinfo("legacy");

		assertThat(service.loadVideoRawPayload(video)).isEqualTo("canonical");
	}

	@Test
	void legacyVideoinfoRemainsReadableWithoutBeingCopiedByEntity() {
		VideoDataEntity video = new VideoDataEntity();
		video.setVideoinfo("legacy");

		assertThat(service.loadVideoRawPayload(video)).isEqualTo("legacy");
		assertThat(video.getJsonData()).isNull();
	}

	@Test
	void storingPayloadWritesOnlyCanonicalColumn() {
		VideoDataEntity video = new VideoDataEntity();

		service.storeVideoRawPayload(video, "{\"cookie\":\"secret\",\"id\":\"1\"}");

		assertThat(video.getJsonData()).contains("\"id\":\"1\"").doesNotContain("secret");
		assertThat(video.getVideoinfo()).isNull();
	}

	@Test
	void invalidPayloadDoesNotEraseExistingCanonicalMetadata() {
		VideoDataEntity video = new VideoDataEntity();
		video.setJsonData("{\"id\":\"existing\"}");

		service.storeVideoRawPayload(video, "not-json");

		assertThat(video.getJsonData()).isEqualTo("{\"id\":\"existing\"}");
	}
}
