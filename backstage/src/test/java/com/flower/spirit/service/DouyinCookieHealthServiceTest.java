package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import com.flower.spirit.entity.TikTokConfigEntity;

class DouyinCookieHealthServiceTest {

	@Test
	@SuppressWarnings("unchecked")
	void globalCooldownSkipsTheHealthProbeRequest() {
		TikTokConfigEntity config = new TikTokConfigEntity();
		config.setCookiepool("odin_tt=a; sessionid=b; ttwid=c; passport_csrf_token=d");
		TikTokConfigService configService = mock(TikTokConfigService.class);
		when(configService.getData()).thenReturn(config);
		PlatformCookieService cookieService = mock(PlatformCookieService.class);
		when(cookieService.isDouyinGlobalCooldownActive()).thenReturn(true);
		when(cookieService.douyinGlobalCooldownRemainingMillis()).thenReturn(120_000L);
		DouyinCookieHealthService service = new DouyinCookieHealthService(configService, cookieService,
				(cookie, secUserId) -> { throw new AssertionError("probe must not run during cooldown"); });

		Map<String, Object> result = service.checkDouyinCookies(false);

		assertThat(result).containsEntry("cooling", 1).containsEntry("invalid", 0);
		List<?> items = (List<?>) result.get("items");
		assertThat(items).hasSize(1);
		assertThat((Map<String, Object>) items.get(0))
				.containsEntry("status", "COOLDOWN")
				.containsEntry("remainingMs", 120_000L);
	}

	@Test
	@SuppressWarnings("unchecked")
	void nullCollectionProbeIsIndeterminateAndDoesNotReportRisk() {
		String cookie = "odin_tt=a; sessionid=b; ttwid=c; passport_csrf_token=d";
		TikTokConfigEntity config = new TikTokConfigEntity();
		config.setCookiepool(cookie);
		TikTokConfigService configService = mock(TikTokConfigService.class);
		when(configService.getData()).thenReturn(config);
		PlatformCookieService cookieService = mock(PlatformCookieService.class);
		String output = "stream-vault-start-cookie-probe {\"probeStatus\":\"INDETERMINATE\","
				+ "\"upstreamStatus\":\"0\",\"listState\":\"NULL\",\"collectCount\":0,"
				+ "\"errorCategory\":\"UPSTREAM_SCHEMA\"} stream-vault-end-cookie-probe";
		DouyinCookieHealthService service = new DouyinCookieHealthService(configService, cookieService,
				(ignored, secUserId) -> new DouyinCookieHealthService.ProbeExecution(output, 0, 15L));

		Map<String, Object> result = service.checkDouyinCookies(false);

		assertThat(result).containsEntry("indeterminate", 1).containsEntry("invalid", 0);
		Map<String, Object> item = (Map<String, Object>) ((List<?>) result.get("items")).get(0);
		assertThat(item).containsEntry("status", "INDETERMINATE")
				.containsEntry("listState", "NULL")
				.containsEntry("evidence", "PROBE");
		verify(cookieService, never()).reportRisk(org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	@SuppressWarnings("unchecked")
	void recentRealSuccessSkipsProbeAndMarksCookieValid() {
		String cookie = "odin_tt=a; sessionid=b; ttwid=c; passport_csrf_token=d";
		TikTokConfigEntity config = new TikTokConfigEntity();
		config.setCookiepool(cookie);
		TikTokConfigService configService = mock(TikTokConfigService.class);
		when(configService.getData()).thenReturn(config);
		PlatformCookieService cookieService = mock(PlatformCookieService.class);
		when(cookieService.hasRecentSuccess("douyin", cookie, java.time.Duration.ofMinutes(15))).thenReturn(true);
		DouyinCookieHealthService service = new DouyinCookieHealthService(configService, cookieService,
				(ignored, secUserId) -> { throw new AssertionError("recent success should skip probe"); });

		Map<String, Object> result = service.checkDouyinCookies(false);

		Map<String, Object> item = (Map<String, Object>) ((List<?>) result.get("items")).get(0);
		assertThat(item).containsEntry("status", "VALID").containsEntry("evidence", "RECENT_SUCCESS");
	}

	@Test
	@SuppressWarnings("unchecked")
	void authorListProbeMarksAnEmptyButValidAuthorAsValid() {
		String cookie = "odin_tt=a; sessionid=b; ttwid=c; passport_csrf_token=d";
		TikTokConfigEntity config = new TikTokConfigEntity();
		config.setCookiepool(cookie);
		config.setDouyinProbeSecUserId("MS4-probe");
		TikTokConfigService configService = mock(TikTokConfigService.class);
		when(configService.getData()).thenReturn(config);
		PlatformCookieService cookieService = mock(PlatformCookieService.class);
		String output = "stream-vault-start-author-probe {\"probeStatus\":\"VALID\","
				+ "\"secUserId\":\"MS4-probe\",\"upstreamStatus\":\"0\","
				+ "\"listState\":\"EMPTY\",\"collectCount\":0,"
				+ "\"errorCategory\":\"NONE\"} stream-vault-end-author-probe";
		DouyinCookieHealthService service = new DouyinCookieHealthService(configService, cookieService,
				(cookieValue, secUserId) -> new DouyinCookieHealthService.ProbeExecution(output, 0, 20L));

		Map<String, Object> result = service.checkDouyinCookies(false);

		assertThat(result).containsEntry("valid", 1).containsEntry("indeterminate", 0);
		Map<String, Object> item = (Map<String, Object>) ((List<?>) result.get("items")).get(0);
		assertThat(item).containsEntry("status", "VALID")
				.containsEntry("listState", "EMPTY")
				.containsEntry("probeSecUserId", "MS4-probe");
	}

	@Test
	@SuppressWarnings("unchecked")
	void checksEachCookieIndependentlyInsteadOfReusingOneSuccessfulResult() {
		String validCookie = "odin_tt=a; sessionid=valid; ttwid=c; passport_csrf_token=d";
		String expiredCookie = "odin_tt=a; sessionid=expired; ttwid=c; passport_csrf_token=d";
		TikTokConfigEntity config = new TikTokConfigEntity();
		config.setCookiepool(validCookie + "\n" + expiredCookie);
		config.setDouyinProbeSecUserId("MS4-probe");
		TikTokConfigService configService = mock(TikTokConfigService.class);
		when(configService.getData()).thenReturn(config);
		PlatformCookieService cookieService = mock(PlatformCookieService.class);
		AtomicInteger probeCount = new AtomicInteger();
		DouyinCookieHealthService service = new DouyinCookieHealthService(configService, cookieService,
				(cookie, secUserId) -> {
					probeCount.incrementAndGet();
					String status = cookie.contains("sessionid=valid") ? "VALID" : "EXPIRED";
					String error = "EXPIRED".equals(status) ? ",\"errorCategory\":\"AUTHENTICATION\"" : "";
					String output = "stream-vault-start-author-probe {\"probeStatus\":\"" + status
							+ "\",\"secUserId\":\"MS4-probe\"" + error
							+ "} stream-vault-end-author-probe";
					return new DouyinCookieHealthService.ProbeExecution(output, 0, 20L);
				});

		Map<String, Object> result = service.checkDouyinCookies(false);
		List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");

		assertThat(probeCount).hasValue(2);
		assertThat(items).hasSize(2);
		assertThat(items.get(0)).containsEntry("status", "VALID");
		assertThat(items.get(1)).containsEntry("status", "EXPIRED");
	}
}
