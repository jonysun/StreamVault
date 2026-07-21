package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.flower.spirit.entity.ProcessHistoryEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataNormalizer;
import com.flower.spirit.platform.WorkParseRequest;
import com.flower.spirit.platform.adapter.PlatformAdapterRegistry;
import com.flower.spirit.platform.adapter.PlatformWorkAdapter;
import com.flower.spirit.service.MediaDownloadService.DownloadOutcome;
import com.flower.spirit.service.WorkPersistenceService.PersistenceResult;

@ExtendWith(MockitoExtension.class)
class WorkIngestServiceTest {

	@Mock private PlatformAdapterRegistry adapterRegistry;
	@Mock private MediaDownloadService mediaDownloadService;
	@Mock private WorkPersistenceService persistenceService;
	@Mock private WorkPostProcessingService postProcessingService;
	@Mock private ProcessHistoryService processHistoryService;
	@Mock private PlatformWorkAdapter adapter;

	private WorkIngestService service;
	private WorkMetadata metadata;

	@BeforeEach
	void setUp() {
		metadata = metadata();
		service = new WorkIngestService(new PlatformResolver(), adapterRegistry,
				new WorkMetadataNormalizer(ZoneId.of("UTC")), mediaDownloadService, persistenceService,
				postProcessingService, processHistoryService);
	}

	@Test
	void previewStopsBeforeHistoryDownloadPersistenceAndPostProcessing() {
		stubAdapterPipeline();
		WorkMetadata preview = service.preview("watch https://youtu.be/work-1");

		assertThat(preview.getWorkId()).isEqualTo("work-1");
		verify(processHistoryService, never()).beginPlatformProcess(anyString(), anyString(), anyString());
		verify(mediaDownloadService, never()).download(any(), any(), any());
		verify(persistenceService, never()).persist(any());
		verify(postProcessingService, never()).complete(any(), any(), any());
	}

	@Test
	void completedIngestPersistsAndRunsPostProcessingOnce() {
		stubAdapterPipeline();
		ProcessHistoryEntity history = new ProcessHistoryEntity();
		history.setId(10);
		when(processHistoryService.beginPlatformProcess(anyString(), anyString(), anyString())).thenReturn(history);
		WorkMediaResource local = new WorkMediaResource(0, WorkMediaResource.Type.VIDEO, null,
				Path.of("C:/media/work-1/video.mp4"), "mp4", Map.of());
		when(mediaDownloadService.download(any(), any(), any()))
				.thenReturn(DownloadOutcome.completed(List.of(local), Path.of("C:/media/work-1")));
		VideoDataEntity video = new VideoDataEntity();
		video.setId(20);
		PersistenceResult persistence = PersistenceResult.video(true, video);
		when(persistenceService.persist(any())).thenReturn(persistence);

		WorkIngestService.IngestResult result = service.ingest("https://youtu.be/work-1",
				Path.of("C:/media/work-1"), false);

		assertThat(result.status()).isEqualTo(DownloadResult.Status.COMPLETED);
		verify(persistenceService).persist(any(WorkMetadata.class));
		verify(postProcessingService).complete(any(), any(WorkMetadata.class), any(PersistenceResult.class));
		verify(processHistoryService).recordPlatformStage(10, "PERSISTING");
		verify(processHistoryService).recordPlatformStage(10, "POST_PROCESSING");
	}

	@Test
	void queuedIngestDoesNotPersistOrPostProcess() {
		stubAdapterPipeline();
		ProcessHistoryEntity history = new ProcessHistoryEntity();
		history.setId(11);
		when(processHistoryService.beginPlatformProcess(anyString(), anyString(), anyString())).thenReturn(history);
		when(mediaDownloadService.download(any(), any(), any()))
				.thenReturn(DownloadOutcome.queued("aria2 accepted", Path.of("C:/media/.staging")));

		WorkIngestService.IngestResult result = service.ingest("https://youtu.be/work-1",
				Path.of("C:/media/work-1"), false);

		assertThat(result.status()).isEqualTo(DownloadResult.Status.QUEUED);
		verify(processHistoryService).recordPlatformStage(11, "QUEUED");
		verify(persistenceService, never()).persist(any());
		verify(postProcessingService, never()).complete(any(), any(), any());
	}

	@Test
	void postProcessingEnqueuesHlsCompletesHistoryAndNotifiesOnce() {
		HlsTranscodeService hls = org.mockito.Mockito.mock(HlsTranscodeService.class);
		WorkNotificationService notifications = org.mockito.Mockito.mock(WorkNotificationService.class);
		WorkPostProcessingService post = new WorkPostProcessingService(hls, processHistoryService, notifications);
		VideoDataEntity video = new VideoDataEntity();
		video.setId(30);

		post.complete(12, metadata, PersistenceResult.video(true, video));

		verify(hls).enqueueVideo(30);
		verify(notifications).notifyCompleted(metadata);
		verify(processHistoryService).completePlatformProcess(12);
	}

	private WorkMetadata metadata() {
		return WorkMetadata.builder()
				.platform(PlatformCatalog.requireByKey("youtube"))
				.workId("work-1")
				.contentType(WorkContentType.VIDEO)
				.title("title")
				.sourceUrl("https://youtube.com/watch?v=work-1")
				.mediaResources(List.of(new WorkMediaResource(0, WorkMediaResource.Type.VIDEO,
						"https://cdn.example/video.mp4", null, "mp4", Map.of())))
				.build();
	}

	private void stubAdapterPipeline() {
		when(adapterRegistry.requireByPlatformKey("youtube")).thenReturn(adapter);
		when(adapter.parse(any())).thenReturn(metadata);
	}
}
