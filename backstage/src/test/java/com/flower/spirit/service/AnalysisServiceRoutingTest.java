package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
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
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;

import jakarta.servlet.http.HttpServletRequest;

class AnalysisServiceRoutingTest {

	private String appToken;
	private String readOnlyToken;
	private String frontend;
	private boolean pauseAll;
	private boolean pauseDownload;
	private String downloadType;
	private AnalysisService service;
	private WorkIngestService workIngestService;
	private ProcessHistoryService processHistoryService;
	private DirectDownloadQueueService directDownloadQueueService;
	private PlatformAdapterProperties properties;

	@BeforeEach
	void setUp() {
		appToken = Global.apptoken;
		readOnlyToken = Global.readonlytoken;
		frontend = Global.frontend;
		pauseAll = Global.backgroundTaskPauseAll;
		pauseDownload = Global.backgroundTaskPauseDownload;
		downloadType = Global.downtype;
		Global.apptoken = "app-token";
		Global.readonlytoken = "read-token";
		Global.frontend = "video_standard";
		Global.backgroundTaskPauseAll = false;
		Global.backgroundTaskPauseDownload = false;
		Global.downtype = "http";

		service = spy(new AnalysisService());
		workIngestService = mock(WorkIngestService.class);
		processHistoryService = mock(ProcessHistoryService.class);
		directDownloadQueueService = mock(DirectDownloadQueueService.class);
		properties = new PlatformAdapterProperties();
		ReflectionTestUtils.setField(service, "platformResolver", new PlatformResolver());
		ReflectionTestUtils.setField(service, "platformAdapterProperties", properties);
		ReflectionTestUtils.setField(service, "workIngestService", workIngestService);
		ReflectionTestUtils.setField(service, "processHistoryService", processHistoryService);
		ReflectionTestUtils.setField(service, "directDownloadQueueService", directDownloadQueueService);
		when(directDownloadQueueService.enqueue(anyString(), any(), any(), any(), any()))
				.thenReturn(new DirectDownloadEnqueueResult(100L, 42, true, DirectDownloadSource.SINGLE_LINK));
	}

	@AfterEach
	void tearDown() {
		service.shutdownExecutors();
		Global.apptoken = appToken;
		Global.readonlytoken = readOnlyToken;
		Global.frontend = frontend;
		Global.backgroundTaskPauseAll = pauseAll;
		Global.backgroundTaskPauseDownload = pauseDownload;
		Global.downtype = downloadType;
	}

	@Test
	void disabledPlatformQueuesPersistentDownloadWithoutStartingLegacyThreadPool() throws Exception {
		doNothing().when(service).processingVideosLegacy(anyString(), anyString());

		AnalysisService.SubmissionResult result = service.submitProcessingVideos("app-token",
				"https://www.youtube.com/watch?v=legacy");

		assertThat(result.mode()).isEqualTo("persistent");
		assertThat(result.status()).isEqualTo("queued");
		assertThat(result.platformKey()).isEqualTo("youtube");
		verify(service, never()).processingVideosLegacy(anyString(), anyString());
		verify(directDownloadQueueService).enqueue("https://www.youtube.com/watch?v=legacy",
				DirectDownloadSource.SINGLE_LINK.name(), null, null, null);
		verify(workIngestService, never()).ingest(anyString(), any(Function.class), anyBoolean(), any());
	}

	@Test
	void aria2SubmissionStillUsesPersistentQueue() throws Exception {
		properties.setAdapter(Map.of("douyin", Mode.NEW));
		Global.downtype = "a2";
		doNothing().when(service).processingVideosLegacy(anyString(), anyString());

		AnalysisService.SubmissionResult result = service.submitProcessingVideos("app-token",
				"https://www.douyin.com/video/1234567890");

		assertThat(result.mode()).isEqualTo("persistent");
		verify(service, never()).processingVideosLegacy(anyString(), anyString());
		verify(workIngestService, never()).ingest(anyString(), any(Function.class), anyBoolean(), any());
	}

	@Test
	@SuppressWarnings("unchecked")
	void enabledPlatformUsesPersistentQueueAndReturnsHistoryId() throws Exception {
		properties.setAdapter(Map.of("youtube", Mode.NEW));
		doNothing().when(service).processingVideosLegacy(anyString(), anyString());
		AnalysisService.SubmissionResult result = service.submitProcessingVideos("app-token",
				"https://www.youtube.com/watch?v=new-route");

		assertThat(result).isEqualTo(new AnalysisService.SubmissionResult(42, "youtube", "persistent", "queued"));
		verify(workIngestService, never()).ingest(anyString(), any(Function.class), anyBoolean(), any());
		verify(service, never()).processingVideosLegacy(anyString(), anyString());
	}

	@Test
	@SuppressWarnings("unchecked")
	void persistentQueueFailureNeverFallsBackToLegacy() throws Exception {
		properties.setAdapter(Map.of("youtube", Mode.NEW));
		doNothing().when(service).processingVideosLegacy(anyString(), anyString());
		when(directDownloadQueueService.enqueue(anyString(), any(), any(), any(), any()))
				.thenThrow(new IllegalStateException("queue failed"));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.submitProcessingVideos("app-token",
				"https://www.youtube.com/watch?v=failure")).isInstanceOf(IllegalStateException.class);

		verify(workIngestService, never()).ingest(anyString(), any(Function.class), anyBoolean(), any());
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

	@Test
	void youtubePlaylistPreviewUsesCollectionAwareLegacyParserWhenNewAdapterIsEnabled() {
		properties.setAdapter(Map.of("youtube", Mode.NEW));
		String playlist = "https://www.youtube.com/playlist?list=PL-example";
		AjaxEntity expected = new AjaxEntity(Global.ajax_success, "parsed", Map.of(
				"type", "multiple", "videos", List.of()));
		doReturn(expected).when(service).legacyDirectData("read-token", playlist, "local");

		AjaxEntity response = service.directData("read-token", playlist, "local");

		assertThat(response).isSameAs(expected);
		verify(service).legacyDirectData("read-token", playlist, "local");
		verify(workIngestService, never()).preview(anyString());
	}

	@Test
	void adminPlaylistIngestReturnsSelectionPreviewBeforeSubmittingAnyWork() throws Exception {
		String playlist = "https://www.youtube.com/playlist?list=PL-example";
		VideoDataEntity requestBody = new VideoDataEntity();
		requestBody.setOriginaladdress(playlist);
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getParameter("type")).thenReturn("1");
		AjaxEntity expected = new AjaxEntity(Global.ajax_success, "parsed", Map.of(
				"type", "multiple", "videos", List.of()));
		doReturn(expected).when(service).directData(Global.apptoken, playlist, "local");

		AjaxEntity response = service.directData(requestBody, request);

		assertThat(response).isSameAs(expected);
		verify(service, never()).submitProcessingVideos(anyString(), anyString());
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
