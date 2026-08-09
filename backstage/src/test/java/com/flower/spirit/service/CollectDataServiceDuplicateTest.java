package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.CollectdDataDao;
import com.flower.spirit.entity.CollectDataEntity;
import com.flower.spirit.task.QuartzTaskService;

@ExtendWith(MockitoExtension.class)
class CollectDataServiceDuplicateTest {

	@Mock
	private CollectdDataDao collectdDataDao;

	@Mock
	private QuartzTaskService quartzTaskService;

	private CollectDataService service;

	@BeforeEach
	void setUp() {
		service = new CollectDataService();
		ReflectionTestUtils.setField(service, "collectdDataDao", collectdDataDao);
		ReflectionTestUtils.setField(service, "quartzTaskService", quartzTaskService);
	}

	@Test
	void rejectsSameCanonicalPlatformAndNormalizedAddress() {
		CollectDataEntity existing = task("douyin", "postMS4wLjAB-existing", "已有任务");
		existing.setId(12);
		existing.setTaskstatus("运行中");
		when(collectdDataDao.findByNormalizedOriginalAddress("postMS4wLjAB-existing"))
				.thenReturn(List.of(existing));
		CollectDataEntity requested = task("抖音", " POSTMS4wLjAB-existing ", "重复任务");

		AjaxEntity result = service.saveCollectData(requested);

		assertThat(result.getResCode()).isEqualTo(Global.ajax_uri_error);
		assertThat(result.getMessage()).contains("ID 12", "已有任务", "运行中");
		assertThat(result.getRecord()).isSameAs(existing);
		verify(collectdDataDao, never()).save(any());
		verify(quartzTaskService, never()).scheduleTask(any());
	}

	@Test
	void savesWhenAddressExistsOnlyOnAnotherPlatform() {
		CollectDataEntity otherPlatform = task("哔哩", "postMS4wLjAB-existing", "其他平台");
		when(collectdDataDao.findByNormalizedOriginalAddress("postMS4wLjAB-existing"))
				.thenReturn(List.of(otherPlatform));
		when(collectdDataDao.save(any())).thenAnswer(invocation -> {
			CollectDataEntity saved = invocation.getArgument(0);
			saved.setId(13);
			return saved;
		});
		CollectDataEntity requested = task(" 抖音 ", " POSTMS4wLjAB-existing ", " 新任务 ");
		requested.setBackfillComplete(null);
		requested.setBackfillVerifying(null);
		requested.setBackfillCleanPasses(null);

		AjaxEntity result = service.saveCollectData(requested);

		assertThat(result.getResCode()).isEqualTo(Global.ajax_success);
		assertThat(requested.getOriginaladdress()).isEqualTo("postMS4wLjAB-existing");
		assertThat(requested.getPlatform()).isEqualTo("抖音");
		assertThat(requested.getTaskname()).isEqualTo("新任务");
		assertThat(requested.getTaskenabled()).isEqualTo("Y");
		assertThat(requested.getBackfillComplete()).isZero();
		assertThat(requested.getBackfillVerifying()).isZero();
		assertThat(requested.getBackfillCleanPasses()).isZero();
		verify(collectdDataDao).save(requested);
		verify(quartzTaskService).scheduleTask(requested);
	}

	private CollectDataEntity task(String platform, String address, String name) {
		CollectDataEntity task = new CollectDataEntity();
		task.setPlatform(platform);
		task.setOriginaladdress(address);
		task.setTaskname(name);
		return task;
	}
}
