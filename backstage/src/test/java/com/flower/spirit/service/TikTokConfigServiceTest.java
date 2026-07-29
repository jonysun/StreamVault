package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.flower.spirit.config.Global;
import com.flower.spirit.dao.TikTokConfigDao;
import com.flower.spirit.entity.TikTokConfigEntity;

class TikTokConfigServiceTest {

	@Test
	void nullStoredCooldownUsesTenMinuteDefault() {
		TikTokConfigDao dao = mock(TikTokConfigDao.class);
		TikTokConfigEntity entity = new TikTokConfigEntity();
		when(dao.findAll()).thenReturn(List.of(entity));
		TikTokConfigService service = service(dao);

		assertThat(service.getRiskCooldownMinutes()).isEqualTo(10);
	}

	@Test
	void acceptedCooldownBoundsArePersisted() {
		for (int minutes : List.of(1, 1440)) {
			TikTokConfigDao dao = mock(TikTokConfigDao.class);
			TikTokConfigService service = service(dao);
			TikTokConfigEntity entity = new TikTokConfigEntity();
			entity.setRiskCooldownMinutes(minutes);

			assertThat(service.updateTikTokConfig(entity).getResCode()).isEqualTo(Global.ajax_success);
			verify(dao).save(entity);
		}
	}

	@Test
	void cooldownOutsidePortableIntegerRangeIsRejectedWithoutSaving() {
		for (int minutes : List.of(0, 1441)) {
			TikTokConfigDao dao = mock(TikTokConfigDao.class);
			TikTokConfigService service = service(dao);
			TikTokConfigEntity entity = new TikTokConfigEntity();
			entity.setRiskCooldownMinutes(minutes);

			assertThat(service.updateTikTokConfig(entity).getResCode()).isEqualTo(Global.ajax_uri_error);
			verify(dao, never()).save(entity);
		}
	}

	private TikTokConfigService service(TikTokConfigDao dao) {
		TikTokConfigService service = new TikTokConfigService();
		ReflectionTestUtils.setField(service, "tikTokConfigDao", dao);
		return service;
	}
}
