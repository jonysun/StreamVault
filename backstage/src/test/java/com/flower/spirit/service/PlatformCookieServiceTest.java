package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PlatformCookieServiceTest {

	@Test
	void explicitRateLimitSignalsAreRecognizedWithoutTreatingOrdinaryHttpFailuresAsRisk() {
		PlatformCookieService service = new PlatformCookieService();

		assertThat(service.isRiskSignal("media request failed with HTTP 429")).isTrue();
		assertThat(service.isRiskSignal("Too Many Requests")).isTrue();
		assertThat(service.isRiskSignal("upstream rate limit reached")).isTrue();
		assertThat(service.isRiskSignal("media request failed with HTTP 404")).isFalse();
		assertThat(service.isRiskSignal("connection timeout")).isFalse();
	}

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
	void douyinRiskBlocksEveryCookieDuringGlobalCooldown() {
		PlatformCookieService service = new PlatformCookieService();

		String first = service.selectCookie("douyin", "risk_shift", "a=1\nb=2", "fallback", "fetch");
		service.reportRisk("douyin", first, "F2_COOKIE_OR_VERIFY_REQUIRED");

		assertThat(first).isEqualTo("a=1");
		assertThat(service.selectCookie("douyin", "risk_shift", "a=1\nb=2", "fallback", "fetch"))
				.isEmpty();
	}

	@Test
	void kuaishouStillSkipsOnlyTheRiskyCookie() {
		PlatformCookieService service = new PlatformCookieService();

		String first = service.selectCookie("kuaishou", "round_robin", "a=1\nb=2", "fallback", "fetch");
		service.reportRisk("kuaishou", first, "rate-limit");
		String second = service.selectCookie("kuaishou", "round_robin", "a=1\nb=2", "fallback", "fetch");

		assertThat(first).isEqualTo("a=1");
		assertThat(second).isEqualTo("b=2");
	}

	@Test
	void latestDouyinRiskSignalExtendsTheGlobalCooldown() {
		PlatformCookieService service = new PlatformCookieService();
		AtomicLong startedAt = (AtomicLong) ReflectionTestUtils.getField(service,
				"douyinGlobalRiskStartedAtMs");

		startedAt.set(System.currentTimeMillis() - 9 * 60 * 1000L);
		long before = service.douyinGlobalCooldownRemainingMillis();
		service.reportRisk("douyin", "a=1", "F2_UPSTREAM_RATE_LIMIT");
		long after = service.douyinGlobalCooldownRemainingMillis();

		assertThat(before).isBetween(1L, 61_000L);
		assertThat(after).isGreaterThan(9 * 60 * 1000L);
	}

	@Test
	void successDoesNotClearRiskCooldown() {
		PlatformCookieService service = new PlatformCookieService();

		String first = service.selectCookie("douyin", "risk_shift", "a=1\nb=2", "fallback", "fetch");
		service.reportRisk("douyin", first, "F2_COOKIE_OR_VERIFY_REQUIRED");
		service.reportSuccess("douyin", first);

		assertThat(service.selectCookie("douyin", "risk_shift", "a=1\nb=2", "fallback", "fetch"))
				.isEmpty();
	}

	@Test
	void successIsRecordedAsRecentEvidenceWithoutClearingCooldown() {
		PlatformCookieService service = new PlatformCookieService();

		service.reportSuccess("douyin", "a=1");

		assertThat(service.hasRecentSuccess("douyin", "a=1", Duration.ofMinutes(15))).isTrue();
		assertThat(service.hasRecentSuccess("douyin", "b=2", Duration.ofMinutes(15))).isFalse();
	}

	@Test
	void activeCooldownUsesTheCurrentConfiguredDuration() {
		PlatformCookieService service = new PlatformCookieService();
		TikTokConfigService configService = mock(TikTokConfigService.class);
		AtomicInteger minutes = new AtomicInteger(10);
		when(configService.getRiskCooldownMinutes()).thenAnswer(invocation -> minutes.get());
		ReflectionTestUtils.setField(service, "tikTokConfigService", configService);
		AtomicLong startedAt = (AtomicLong) ReflectionTestUtils.getField(service,
				"douyinGlobalRiskStartedAtMs");
		startedAt.set(System.currentTimeMillis() - 5 * 60 * 1000L);

		assertThat(service.isDouyinGlobalCooldownActive()).isTrue();
		minutes.set(4);
		assertThat(service.isDouyinGlobalCooldownActive()).isFalse();
		minutes.set(20);
		assertThat(service.isDouyinGlobalCooldownActive()).isTrue();
	}

	@Test
	void douyinStatusReportsConfiguredGlobalCooldown() {
		PlatformCookieService service = new PlatformCookieService();
		TikTokConfigService configService = mock(TikTokConfigService.class);
		when(configService.getRiskCooldownMinutes()).thenReturn(25);
		ReflectionTestUtils.setField(service, "tikTokConfigService", configService);
		service.reportRisk("douyin", "a=1", "F2_COOKIE_OR_VERIFY_REQUIRED");

		assertThat(service.cookieStatus("douyin"))
				.containsEntry("cooling", 1)
				.containsEntry("cooldownMinutes", 25);
	}

	@Test
	void detailSoftBlockDoesNotGateCookiesAndStrongRiskRemainsGlobal() {
		PlatformCookieService service = new PlatformCookieService();
		service.reportDetailSoftBlock("douyin", "F2_UPSTREAM_SOFT_BLOCK");

		assertThat(service.isDouyinGlobalRiskCooldownActive()).isFalse();
		assertThat(service.isDouyinDetailSoftBackoffActive()).isTrue();
		assertThat(service.douyinDetailSoftBackoffRemainingMillis())
				.isBetween(4 * 60 * 1000L, 5 * 60 * 1000L);
		assertThat(service.selectCookie("douyin", "round_robin", "a=1", "fallback", "collect_worker"))
				.isEqualTo("a=1");
		assertThat(service.cookieStatus("douyin"))
				.containsEntry("cooling", 0)
				.containsEntry("detailCooling", 1);

		service.reportRisk("douyin", "a=1", "F2_COOKIE_OR_VERIFY_REQUIRED");
		assertThat(service.isDouyinGlobalRiskCooldownActive()).isTrue();
		assertThat(service.douyinGlobalRiskCooldownRemainingMillis()).isGreaterThan(9 * 60 * 1000L);
		assertThat(service.selectCookie("douyin", "round_robin", "a=1", "fallback", "collect_worker"))
				.isEmpty();
	}

	@Test
	void unconfirmedDouyinSignalDoesNotStartGlobalCooldown() {
		PlatformCookieService service = new PlatformCookieService();

		assertThat(service.reportRisk("douyin", "a=1", "login expired without HTTP evidence")).isFalse();
		assertThat(service.isDouyinGlobalRiskCooldownActive()).isFalse();

		assertThat(service.reportRisk("douyin", "a=1", "request failed status=403")).isTrue();
		assertThat(service.isDouyinGlobalRiskCooldownActive()).isTrue();
	}

	@Test
	void poolFallsBackToLegacySingleCookie() {
		PlatformCookieService service = new PlatformCookieService();

		String cookie = service.selectCookie("快手", "round_robin", "", "legacy=1", "parse");

		assertThat(cookie).isEqualTo("legacy=1");
	}
}
