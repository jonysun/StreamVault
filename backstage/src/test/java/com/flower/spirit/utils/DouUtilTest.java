package com.flower.spirit.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DouUtilTest {

	@Test
	void extractsTheFinalJsonObjectAfterSafeF2Diagnostics() {
		var parsed = DouUtil.parseF2WorkJson("diagnostic line\n{\"aweme_detail\":{\"aweme_id\":\"1\"}}");

		assertThat(parsed.getJSONObject("aweme_detail").getString("aweme_id")).isEqualTo("1");
	}

	@Test
	void classifiesNonJsonResponsesWithoutReturningRawSecrets() {
		String rateLimit = DouUtil.f2WorkResponseDiagnostic(
				"HTTP 429 Too Many Requests sessionid=do-not-log");
		String authentication = DouUtil.f2WorkResponseDiagnostic("HTTP 403 verification required");
		String html = DouUtil.f2WorkResponseDiagnostic("<!doctype html><title>upstream error</title>");

		assertThat(rateLimit).contains("HTTP 429").doesNotContain("do-not-log", "sessionid");
		assertThat(authentication).contains("authentication or verification");
		assertThat(html).contains("non-JSON HTML");
	}
}
