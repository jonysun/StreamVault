package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
		DouyinCookieHealthService service = new DouyinCookieHealthService();
		ReflectionTestUtils.setField(service, "tikTokConfigService", configService);
		ReflectionTestUtils.setField(service, "platformCookieService", cookieService);

		Map<String, Object> result = service.checkDouyinCookies(false);

		assertThat(result).containsEntry("cooling", 1).containsEntry("invalid", 0);
		List<?> items = (List<?>) result.get("items");
		assertThat(items).hasSize(1);
		assertThat((Map<String, Object>) items.get(0))
				.containsEntry("status", "COOLDOWN")
				.containsEntry("remainingMs", 120_000L);
	}
}
