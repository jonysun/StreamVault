package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.config.PlatformAdapterProperties;
import com.flower.spirit.config.PlatformAdapterProperties.Mode;
import com.flower.spirit.entity.ProcessHistoryEntity;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;

class AnalysisServiceRoutingTest {

	private String appToken;
	private String readOnlyToken;
	private String frontend;
	private boolean pauseAll;
	private boolean pauseDownload;
	private AnalysisService service;
	private WorkIngestService workIngestService;
	private ProcessHistoryService processHistoryService;
	private PlatformAdapterProperties properties;

	@BeforeEach
	void setUp() {
		appToken = Global.apptoken;
		readOnlyToken = Global.readonlytoken;
		frontend = Global.frontend;
		pauseAll = Global.backgroundTaskPauseAll;
		pauseDownload = Global.backgroundTaskPauseDownload;
		Global.apptoken = "app-token";
		Global.readonlytoken = "read-token";
		Global.frontend = "video_standard";
		Global.backgroundTaskPauseAll = false;
		Global.backgroundTaskPauseDownload = false;

		service = spy(new AnalysisService());
		workIngestService = mock(WorkIngestService.class);
		processHistoryService = mock(ProcessHistoryService.class);
		properties = new PlatformAdapterProperties();
		ReflectionTestUtils.setField(service, "platformResolver", new PlatformResolver());
		ReflectionTestUtils.setField(service, "platformAdapterProperties", properties);
		ReflectionTestUtils.setField(service, "workIngestService", workIngestService);
		ReflectionTestUtils.setField(service, "processHistoryService", processHistoryService);
	}

	@AfterEach
	void tearDown() {
		service.shutdownExecutors();
		Global.apptoken = appToken;
		Global.readonlytoken = readOnlyToken;
		Global.frontend = frontend;
		Global.backgroundTaskPauseAll = pauseAll;
		Global.backgroundTaskPauseDownload = pauseDownload;
	}

	@Test
	void disabledPlatformExecutesExactlyOneLegacyPath() throws Exception {
		doNothing().when(service).processingVideosLegacy(anyString(), anyString());

		AnalysisService.SubmissionResult result = service.submitProcessingVideos("app-token",
				"https://www.youtube.com/watch?v=legacy");

		assertThat(result.mode()).isEqualTo("legacy");
		assertThat(result.platformKey()).isEqualTo("youtube");
		verify(service).processingVideosLegacy("app-token", "https://www.youtube.com/watch?v=legacy");
		verify(workIngestService, never()).ingest(anyString(), any(Function.class), anyBoolean(), any());
	}

	@Test
	@SuppressWarnings("unchecked")
	void enabledPlatformUsesBoundedIngestQueueAndReturnsHistoryId() throws Exception {
		properties.setAdapter(Map.of("youtube", Mode.NEW));
		doNothing().when(service).processingVideosLegacy(anyString(), anyString());
		ProcessHistoryEntity history = new ProcessHistoryEntity();
		history.setId(42);
		when(processHistoryService.beginPlatformProcess(anyString(), eq("YouTube"), eq("SUBMITTED")))
				.thenReturn(history);

		AnalysisService.SubmissionResult result = service.submitProcessingVideos("app-token",
				"https://www.youtube.com/watch?v=new-route");

		assertThat(result).isEqualTo(new AnalysisService.SubmissionResult(42, "youtube", "new", "submitted"));
		verify(workIngestService, org.mockito.Mockito.timeout(5000)).ingest(
				eq("https://www.youtube.com/watch?v=new-route"), any(Function.class), eq(false), eq(42));
		verify(service, never()).processingVideosLegacy(anyString(), anyString());
	}

	@Test
	@SuppressWarnings("unchecked")
	void newAdapterFailureNeverFallsBackToLegacy() throws Exception {
		properties.setAdapter(Map.of("youtube", Mode.NEW));
		doNothing().when(service).processingVideosLegacy(anyString(), anyString());
		ProcessHistoryEntity history = new ProcessHistoryEntity();
		history.setId(43);
		when(processHistoryService.beginPlatformProcess(anyString(), anyString(), anyString())).thenReturn(history);
		doThrow(new IllegalStateException("adapter failed")).when(workIngestService)
				.ingest(anyString(), any(Function.class), eq(false), eq(43));

		service.submitProcessingVideos("app-token", "https://www.youtube.com/watch?v=failure");

		verify(workIngestService, org.mockito.Mockito.timeout(5000)).ingest(
				anyString(), any(Function.class), eq(false), eq(43));
		verify(service, never()).processingVideosLegacy(anyString(), anyString());
	}

	@Test
	@SuppressWarnings("unchecked")
	void enabledDirectPreviewKeepsLegacyFieldsAndAddsCanonicalFields() {
		properties.setAdapter(Map.of("youtube", Mode.NEW));
		when(workIngestService.preview(anyString())).thenReturn(youtubeDash());

		AjaxEntity response = service.directData("read-token", "https://youtu.be/work-1", "local");

		assertThat(response.getResCode()).isEqualTo(Global.ajax_success);
		assertThat(response.getPlatformKey()).isEqualTo("youtube");
		assertThat(response.getMode()).isEqualTo("new");
		Map<String, Object> record = (Map<String, Object>) response.getRecord();
		assertThat(record).containsEntry("platform", "YouTube")
				.containsEntry("platformKey", "youtube")
				.containsEntry("workId", "work-1")
				.containsEntry("contentType", "video")
				.containsEntry("videoUrl", "https://media.example/video.m4s")
				.containsEntry("audioUrl", "https://media.example/audio.m4s")
				.containsEntry("isDash", true)
				.containsEntry("needReferer", true);
	}

	@Test
	void directPreviewFailureDoesNotInvokeLegacyParser() {
		properties.setAdapter(Map.of("youtube", Mode.NEW));
		doReturn(null).when(service).legacyDirectData(anyString(), anyString(), anyString());
		when(workIngestService.preview(anyString())).thenThrow(new IllegalStateException("preview failed"));

		AjaxEntity response = service.directData("app-token", "https://youtu.be/failure", "local");

		assertThat(response.getResCode()).isEqualTo(Global.ajax_uri_error);
		verify(service, never()).legacyDirectData(anyString(), anyString(), anyString());
	}

	private WorkMetadata youtubeDash() {
		return WorkMetadata.builder()
				.platform(PlatformCatalog.requireByKey("youtube"))
				.workId("work-1")
				.contentType(WorkContentType.VIDEO)
				.title("title")
				.authorName("author")
				.sourceUrl("https://youtu.be/work-1")
				.originalAddress("https://youtu.be/work-1")
				.mediaResources(List.of(
						new WorkMediaResource(0, WorkMediaResource.Type.VIDEO,
								"https://media.example/video.m4s", null, "m4s",
								Map.of("Referer", "https://youtu.be/work-1")),
						new WorkMediaResource(1, WorkMediaResource.Type.AUDIO,
								"https://media.example/audio.m4s", null, "m4s", Map.of())))
				.build();
	}
}
