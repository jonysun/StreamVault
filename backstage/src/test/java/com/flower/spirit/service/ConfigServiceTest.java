package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.flower.spirit.dao.ConfigDao;
import com.flower.spirit.entity.ConfigEntity;

class ConfigServiceTest {

	@Test
	void getDataCreatesDefaultConfigurationWhenDatabaseIsEmpty() {
		ConfigDao dao = mock(ConfigDao.class);
		when(dao.findAll()).thenReturn(List.of());
		when(dao.save(any(ConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
		ConfigService service = new ConfigService();
		ReflectionTestUtils.setField(service, "configDao", dao);

		ConfigEntity config = service.getData();

		assertThat(config.getF2logfullonerror()).isEqualTo("1");
		assertThat(config.getCollecttaskintervalms()).isEqualTo("3000");
		assertThat(config.getHlssegmentseconds()).isEqualTo("4");
		verify(dao).save(config);
	}
}
