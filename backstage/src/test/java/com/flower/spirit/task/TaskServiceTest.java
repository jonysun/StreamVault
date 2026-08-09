package com.flower.spirit.task;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.flower.spirit.service.CollectDownloadWorker;
import com.flower.spirit.service.CollectJobWorker;
import com.flower.spirit.service.DirectDownloadWorker;
import com.flower.spirit.service.PauseDecision;
import com.flower.spirit.service.RuntimeControlService;
import com.flower.spirit.service.TaskCategory;

class TaskServiceTest {

	@Test
	void pausedDownloadsDoNotPreventFetchWakeup() {
		TaskService service = new TaskService();
		CollectJobWorker fetchWorker = mock(CollectJobWorker.class);
		CollectDownloadWorker downloadWorker = mock(CollectDownloadWorker.class);
		DirectDownloadWorker directDownloadWorker = mock(DirectDownloadWorker.class);
		RuntimeControlService runtime = mock(RuntimeControlService.class);
		ReflectionTestUtils.setField(service, "collectJobWorker", fetchWorker);
		ReflectionTestUtils.setField(service, "collectDownloadWorker", downloadWorker);
		ReflectionTestUtils.setField(service, "directDownloadWorker", directDownloadWorker);
		ReflectionTestUtils.setField(service, "runtimeControlService", runtime);
		when(runtime.mayRun(TaskCategory.COLLECT_FETCH)).thenReturn(PauseDecision.permit());
		when(runtime.mayRun(TaskCategory.MEDIA_DOWNLOAD))
				.thenReturn(PauseDecision.paused("pause.download", "maintenance"));

		service.collectQueueTick();

		verify(fetchWorker).wakeUp();
		verify(downloadWorker, never()).wakeUp();
		verify(directDownloadWorker, never()).wakeUp();
	}

	@Test
	void pausedFetchDoesNotPreventDownloadWakeup() {
		TaskService service = new TaskService();
		CollectJobWorker fetchWorker = mock(CollectJobWorker.class);
		CollectDownloadWorker downloadWorker = mock(CollectDownloadWorker.class);
		DirectDownloadWorker directDownloadWorker = mock(DirectDownloadWorker.class);
		RuntimeControlService runtime = mock(RuntimeControlService.class);
		ReflectionTestUtils.setField(service, "collectJobWorker", fetchWorker);
		ReflectionTestUtils.setField(service, "collectDownloadWorker", downloadWorker);
		ReflectionTestUtils.setField(service, "directDownloadWorker", directDownloadWorker);
		ReflectionTestUtils.setField(service, "runtimeControlService", runtime);
		when(runtime.mayRun(TaskCategory.COLLECT_FETCH))
				.thenReturn(PauseDecision.paused("pause.collect", "maintenance"));
		when(runtime.mayRun(TaskCategory.MEDIA_DOWNLOAD)).thenReturn(PauseDecision.permit());

		service.collectQueueTick();

		verify(fetchWorker, never()).wakeUp();
		verify(downloadWorker).wakeUp();
		verify(directDownloadWorker).wakeUp();
	}
}
