package com.flower.spirit.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RawMetadataSanitizerTest {

	@Test
	void removesNestedCredentialsAndRedactsSignedUrlValues() {
		String raw = "{\"http_headers\":{\"Cookie\":\"secret\"},"
				+ "\"nested\":{\"authorization\":\"Bearer secret\",\"title\":\"safe\"},"
				+ "\"url\":\"https://cdn.example/video.mp4?token=abc&signature=def&id=1\"}";

		String sanitized = RawMetadataSanitizer.sanitize(raw);

		assertThat(sanitized).contains("safe", "id=1", "[redacted]")
				.doesNotContain("Cookie", "Bearer secret", "abc", "def", "http_headers");
	}

	@Test
	void rejectsUnstructuredDiagnosticOutput() {
		assertThat(RawMetadataSanitizer.sanitize("cookie=secret")).isNull();
	}

	@Test
	void removesTokenVariantsHeadersAndCommonCdnSignatures() {
		String raw = "{\"refresh_token\":\"refresh-secret\",\"api-key\":\"api-secret\","
				+ "\"headers\":{\"Referer\":\"safe\",\"Cookie\":\"cookie-secret\"},"
				+ "\"url\":\"https://cdn.example/video.mp4?X-Goog-Signature=goog-secret"
				+ "&Policy=policy-secret&Key-Pair-Id=pair-secret&wsSecret=ws-secret&id=1\"}";

		String sanitized = RawMetadataSanitizer.sanitize(raw);

		assertThat(sanitized).contains("id=1", "[redacted]")
				.doesNotContain("refresh-secret", "api-secret", "cookie-secret", "goog-secret",
						"policy-secret", "pair-secret", "ws-secret", "headers");
	}
}
