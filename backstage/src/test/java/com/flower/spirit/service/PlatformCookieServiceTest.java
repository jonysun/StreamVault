package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlatformCookieServiceTest {

	@Test
	void roundRobinAlternatesAcrossCookiePool() {
		PlatformCookieService service = new PlatformCookieService();

		String first = service.selectCookie("抖音", "round_robin", "a=1\nb=2", "fallback", "fetch");
		String second = service.selectCookie("抖音", "round_robin", "a=1\nb=2", "fallback", "fetch");
		String third = service.selectCookie("抖音", "round_robin", "a=1\nb=2", "fallback", "fetch");

		assertThat(first).isEqualTo("a=1");
		assertThat(second).isEqualTo("b=2");
		assertThat(third).isEqualTo("a=1");
	}

	@Test
	void riskShiftSkipsRiskyCookieDuringCooldown() {
		PlatformCookieService service = new PlatformCookieService();

		String first = service.selectCookie("抖音", "risk_shift", "a=1\nb=2", "fallback", "fetch");
		service.reportRisk("抖音", first, "verify");
		String second = service.selectCookie("抖音", "risk_shift", "a=1\nb=2", "fallback", "fetch");

		assertThat(first).isEqualTo("a=1");
		assertThat(second).isEqualTo("b=2");
	}

	@Test
	void roundRobinAlsoSkipsRiskyCookieDuringCooldown() {
		PlatformCookieService service = new PlatformCookieService();

		String first = service.selectCookie("抖音", "round_robin", "a=1\nb=2", "fallback", "fetch");
		service.reportRisk("抖音", first, "rate-limit");
		String second = service.selectCookie("抖音", "round_robin", "a=1\nb=2", "fallback", "fetch");

		assertThat(first).isEqualTo("a=1");
		assertThat(second).isEqualTo("b=2");
	}

	@Test
	void riskReportedWithLegacyDisplayNameIsSharedWithCanonicalPlatformKey() {
		PlatformCookieService service = new PlatformCookieService();

		String first = service.selectCookie("douyin", "round_robin", "a=1\nb=2", "fallback", "fetch");
		service.reportRisk("抖音", first, "rate-limit");

		assertThat(service.selectCookie("douyin", "round_robin", "a=1\nb=2", "fallback", "fetch"))
				.isEqualTo("b=2");
	}

	@Test
	void successDoesNotClearRiskCooldown() {
		PlatformCookieService service = new PlatformCookieService();

		String first = service.selectCookie("抖音", "risk_shift", "a=1\nb=2", "fallback", "fetch");
		service.reportRisk("抖音", first, "verify");
		service.reportSuccess("抖音", first);
		String second = service.selectCookie("抖音", "risk_shift", "a=1\nb=2", "fallback", "fetch");

		assertThat(second).isEqualTo("b=2");
	}

	@Test
	void riskShiftReturnsEmptyWhenAllPooledCookiesAreCooling() {
		PlatformCookieService service = new PlatformCookieService();

		service.reportRisk("抖音", "a=1", "verify");
		service.reportRisk("抖音", "b=2", "verify");
		String selected = service.selectCookie("抖音", "risk_shift", "a=1\nb=2", "fallback", "fetch");

		assertThat(selected).isEmpty();
	}

	@Test
	void roundRobinReturnsEmptyWhenAllPooledCookiesAreCooling() {
		PlatformCookieService service = new PlatformCookieService();

		service.reportRisk("抖音", "a=1", "rate-limit");
		service.reportRisk("抖音", "b=2", "rate-limit");

		assertThat(service.selectCookie("抖音", "round_robin", "a=1\nb=2", "fallback", "fetch"))
				.isEmpty();
	}

	@Test
	void poolFallsBackToLegacySingleCookie() {
		PlatformCookieService service = new PlatformCookieService();

		String cookie = service.selectCookie("快手", "round_robin", "", "legacy=1", "parse");

		assertThat(cookie).isEqualTo("legacy=1");
	}
}
