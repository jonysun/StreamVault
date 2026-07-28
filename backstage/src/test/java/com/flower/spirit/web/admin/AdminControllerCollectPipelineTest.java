package com.flower.spirit.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.service.CollectEnqueueResult;
import com.flower.spirit.service.CollectEnqueueService;
import com.flower.spirit.service.CollectRunQueryService;
import com.flower.spirit.service.CollectRunService;
import com.flower.spirit.service.CollectRunState;

@ExtendWith(MockitoExtension.class)
class AdminControllerCollectPipelineTest {

	@Mock private CollectEnqueueService enqueueService;
	@Mock private CollectRunQueryService queryService;
	@Mock private CollectRunService runService;

	private AdminController controller;

	@BeforeEach
	void setUp() {
		controller = new AdminController();
		ReflectionTestUtils.setField(controller, "collectEnqueueService", enqueueService);
		ReflectionTestUtils.setField(controller, "collectRunQueryService", queryService);
		ReflectionTestUtils.setField(controller, "collectRunService", runService);
	}

	@Test
	void retriesOneFailedDownloadItem() {
		when(runService.retryDownloadItem(41L)).thenReturn(Map.of("itemId", 41L, "processState", "QUEUED"));

		AjaxEntity response = controller.retryCollectDownloadItem(41L);

		assertThat(response.getResCode()).isEqualTo(Global.ajax_success);
		assertThat(response.getRecord()).isEqualTo(Map.of("itemId", 41L, "processState", "QUEUED"));
		verify(runService).retryDownloadItem(41L);
	}

	@Test
	void retriesAllFailedItemsInRun() {
		when(runService.retryFailedDownloads(9L)).thenReturn(3);

		AjaxEntity response = controller.retryCollectDownloadItems(9L);

		assertThat(response.getResCode()).isEqualTo(Global.ajax_success);
		assertThat(response.getRecord()).isEqualTo(Map.of("requeued", 3));
		verify(runService).retryFailedDownloads(9L);
	}

	@Test
	void enqueuesFullAuditAndExposesDownloadQueue() {
		CollectEnqueueResult queued = new CollectEnqueueResult(9L, 11L, CollectRunState.QUEUED, true, false);
		when(enqueueService.enqueueAudit(7)).thenReturn(queued);
		Map<String, Object> queue = Map.of("counts", Map.of("QUEUED", 2L), "items", java.util.List.of());
		when(queryService.downloadQueue(eq(7), eq(80))).thenReturn(queue);

		AjaxEntity audit = controller.auditCollectTask(7);
		AjaxEntity status = controller.collectDownloadQueue(7, 80);

		assertThat(audit.getResCode()).isEqualTo(Global.ajax_success);
		assertThat(audit.getRecord()).isSameAs(queued);
		assertThat(status.getResCode()).isEqualTo(Global.ajax_success);
		assertThat(status.getRecord()).isSameAs(queue);
		verify(enqueueService).enqueueAudit(7);
		verify(queryService).downloadQueue(7, 80);
	}
}
