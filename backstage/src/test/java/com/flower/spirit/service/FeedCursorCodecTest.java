package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.flower.spirit.dto.FeedCursor;

class FeedCursorCodecTest {

	private final FeedCursorCodec codec = new FeedCursorCodec("cursor-test-secret");

	@Test
	void roundTripPreservesStableSortTupleAndFilterBinding() {
		FeedCursor cursor = new FeedCursor(Instant.parse("2026-07-25T10:00:00Z"), "video", 42, "desc",
				"sha256:filter");

		assertThat(codec.decode(codec.encode(cursor))).isEqualTo(cursor);
	}

	@Test
	void tamperedCursorIsRejected() {
		String token = codec.encode(new FeedCursor(Instant.parse("2026-07-25T10:00:00Z"), "video", 42,
				"desc", "sha256:filter"));
		String tampered = (token.charAt(0) == 'A' ? "B" : "A") + token.substring(1);

		assertThatThrownBy(() -> codec.decode(tampered))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("signature");
	}
}
