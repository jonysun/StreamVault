package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

class SnapshotCodecTest {

	@Test
	void byteBudgetProducesValidUtf8JsonAndPreservesTotals() {
		SnapshotCodec codec = new SnapshotCodec(1200, 2);
		JSONArray source = new JSONArray();
		for (int i = 0; i < 20; i++) {
			JSONObject item = new JSONObject();
			item.put("aweme_id", "作品-" + i);
			item.put("desc", "摘要".repeat(300));
			item.put("nickname", "作者");
			item.put("create_time", "2026-07-25 10:00:00");
			JSONArray urls = new JSONArray();
			if (i % 2 == 0) urls.add("https://example.test/video.mp4");
			item.put("video_play_addr", urls);
			source.add(item);
		}

		String raw = codec.encodeFetch(source, Map.of("taskId", 7));
		SnapshotReadResult result = codec.read(raw);

		assertThat(raw.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1200);
		assertThat(result.available()).isTrue();
		assertThat(result.version()).isEqualTo(2);
		assertThat(result.truncated()).isTrue();
		assertThat(result.totalCount()).isEqualTo(20);
		assertThat(result.videoTotal()).isEqualTo(10);
		assertThat(result.graphicTotal()).isEqualTo(10);
		assertThat(result.context()).containsEntry("taskId", 7);
	}

	@Test
	void malformedLegacyJsonReturnsStructuredWarning() {
		SnapshotReadResult result = new SnapshotCodec(1024, 2).read("[{\"aweme_id\":\"1\"}");

		assertThat(result.available()).isFalse();
		assertThat(result.warningCode()).isEqualTo("LEGACY_TRUNCATED_JSON");
		assertThat(result.items()).isEmpty();
	}

	@Test
	void legacyArrayRemainsReadable() {
		SnapshotReadResult result = new SnapshotCodec(1024, 2).read(
				"[{\"aweme_id\":\"1\",\"has_video_play_addr\":false,\"desc\":\"图文\"}]");

		assertThat(result.available()).isTrue();
		assertThat(result.version()).isEqualTo(1);
		assertThat(result.items()).singleElement().satisfies(item -> {
			assertThat(item.workId()).isEqualTo("1");
			assertThat(item.mediaType()).isEqualTo("graphic");
		});
	}
}
