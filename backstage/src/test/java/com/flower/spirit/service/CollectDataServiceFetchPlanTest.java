package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import com.flower.spirit.dao.CollectdDataDao;
import com.flower.spirit.entity.CollectDataEntity;

class CollectDataServiceFetchPlanTest {

	private final CollectdDataDao taskDao = mock(CollectdDataDao.class);
	private final CollectRunQueryService queryService = mock(CollectRunQueryService.class);
	private final DouyinIncrementalFetchService fetchService = mock(DouyinIncrementalFetchService.class);
	private final CollectRunService runService = mock(CollectRunService.class);
	private final BlockedWorkService blockedWorkService = mock(BlockedWorkService.class);
	private final PlatformCookieService cookieService = mock(PlatformCookieService.class);
	private final VideoDataService videoDataService = mock(VideoDataService.class);
	private final HlsTranscodeService hlsTranscodeService = mock(HlsTranscodeService.class);
	private final RuntimeControlService runtimeControlService = mock(RuntimeControlService.class);
	private CollectDataService service;

	@BeforeEach
	void setUp() {
		service = new CollectDataService();
		ReflectionTestUtils.setField(service, "collectdDataDao", taskDao);
		ReflectionTestUtils.setField(service, "collectRunQueryService", queryService);
		ReflectionTestUtils.setField(service, "douyinIncrementalFetchService", fetchService);
		ReflectionTestUtils.setField(service, "collectRunService", runService);
		ReflectionTestUtils.setField(service, "blockedWorkService", blockedWorkService);
		ReflectionTestUtils.setField(service, "platformCookieService", cookieService);
		ReflectionTestUtils.setField(service, "videoDataService", videoDataService);
		ReflectionTestUtils.setField(service, "hlsTranscodeService", hlsTranscodeService);
		ReflectionTestUtils.setField(service, "runtimeControlService", runtimeControlService);
		ReflectionTestUtils.setField(service, "incrementalKnownBoundary", 20);
		ReflectionTestUtils.setField(service, "incrementalMinPages", 20);
		ReflectionTestUtils.setField(service, "backfillMaxPages", 500);
		ReflectionTestUtils.setField(service, "auditMaxPages", 500);
		ReflectionTestUtils.setField(service, "emptyPageLimit", 3);
		ReflectionTestUtils.setField(service, "initialFetchLimit", 80);
		when(cookieService.currentDouyinCookie(anyString())).thenReturn("cookie");
		when(runtimeControlService.mayRun(TaskCategory.COLLECT_FETCH)).thenReturn(PauseDecision.permit());
	}

	@Test
	void persistentRunStoresOnlySelectedUnknownItems() {
		CollectDataEntity task = postTask(null);
		when(taskDao.findById(7)).thenReturn(Optional.of(task));
		when(queryService.findKnownWorkIds(7)).thenReturn(Set.of("known-1"));
		when(fetchService.fetch(any())).thenReturn(envelope(
				List.of(work("new-1", "200", "video"), work("known-1", "100", "image")),
				Set.of("new-1"), "NO_MORE"));

		service.executeQueuedCollectTask(7, 90L, CollectTriggerType.SCHEDULED);

		ArgumentCaptor<List<CollectRunFetchedItem>> items = listCaptor();
		ArgumentCaptor<CollectRunFetchedItem.FetchWatermark> watermark =
				ArgumentCaptor.forClass(CollectRunFetchedItem.FetchWatermark.class);
		verify(runService).storeFetchPlan(org.mockito.ArgumentMatchers.eq(90L),
				org.mockito.ArgumentMatchers.eq(7), items.capture(), org.mockito.ArgumentMatchers.eq(2),
				org.mockito.ArgumentMatchers.eq("NO_MORE"), watermark.capture());
		assertThat(items.getValue()).extracting(CollectRunFetchedItem::workId,
				CollectRunFetchedItem::processState, CollectRunFetchedItem::decision)
				.containsExactly(tuple("new-1", "QUEUED", "NEW"));
		assertThat(watermark.getValue().publishTime()).isEqualTo("200");
		assertThat(watermark.getValue().workId()).isEqualTo("new-1");
		verifyNoInteractions(videoDataService, hlsTranscodeService);
	}

	@Test
	void postFetchModeUsesInitialAndAuditInputs() {
		CollectDataEntity task = postTask(null);
		task.setOmaxcur(35);
		when(taskDao.findById(7)).thenReturn(Optional.of(task));
		when(queryService.findKnownWorkIds(7)).thenReturn(Set.of());
		when(fetchService.fetch(any())).thenReturn(envelope(List.of(), Set.of(), "NO_PUBLIC_WORKS"));

		service.executeQueuedCollectTask(7, 91L, CollectTriggerType.SCHEDULED);
		ArgumentCaptor<DouyinFetchRequest> requests = ArgumentCaptor.forClass(DouyinFetchRequest.class);
		verify(fetchService).fetch(requests.capture());
		assertThat(requests.getValue().mode()).isEqualTo(DouyinFetchMode.INITIAL);
		assertThat(requests.getValue().maxItems()).isEqualTo(35);

		task.setLastSuccessfulFetchAt(new java.util.Date());
		service.executeQueuedCollectTask(7, 92L, CollectTriggerType.AUDIT);
		verify(fetchService, org.mockito.Mockito.times(2)).fetch(requests.capture());
		DouyinFetchRequest audit = requests.getAllValues().get(requests.getAllValues().size() - 1);
		assertThat(audit.mode()).isEqualTo(DouyinFetchMode.AUDIT);
		assertThat(audit.maxPages()).isEqualTo(500);
		assertThat(audit.maxItems()).isZero();
	}

	@Test
	void incrementalModeUsesMonitorLimitAndExpandsPageBudgetForKnownPrefix() {
		CollectDataEntity task = postTask(new java.util.Date());
		task.setMaxcur(20);
		when(taskDao.findById(7)).thenReturn(Optional.of(task));
		Set<String> knownIds = java.util.stream.IntStream.range(0, 1000)
				.mapToObj(index -> "known-" + index).collect(java.util.stream.Collectors.toSet());
		when(queryService.findKnownWorkIds(7)).thenReturn(knownIds);
		when(fetchService.fetch(any())).thenReturn(envelope(List.of(), Set.of(), "NO_MORE"));

		service.executeQueuedCollectTask(7, 921L, CollectTriggerType.SCHEDULED);

		ArgumentCaptor<DouyinFetchRequest> request = ArgumentCaptor.forClass(DouyinFetchRequest.class);
		verify(fetchService).fetch(request.capture());
		assertThat(request.getValue().mode()).isEqualTo(DouyinFetchMode.INCREMENTAL);
		assertThat(request.getValue().maxItems()).isEqualTo(20);
		assertThat(request.getValue().maxPages()).isEqualTo(52);
	}

	@Test
	void auditRequeuesKnownWorkWhenStoredMediaNeedsRepair() {
		CollectDataEntity task = postTask(new java.util.Date());
		when(taskDao.findById(7)).thenReturn(Optional.of(task));
		when(queryService.findKnownWorkIds(7)).thenReturn(Set.of("known-1"));
		when(queryService.needsAuditRequeue(7, "douyin", "known-1", "video")).thenReturn(true);
		when(fetchService.fetch(any())).thenReturn(envelope(
				List.of(work("known-1", "100", "video")), Set.of(), "NO_MORE"));

		service.executeQueuedCollectTask(7, 93L, CollectTriggerType.AUDIT);

		ArgumentCaptor<List<CollectRunFetchedItem>> items = listCaptor();
		verify(runService).storeFetchPlan(org.mockito.ArgumentMatchers.eq(93L),
				org.mockito.ArgumentMatchers.eq(7), items.capture(), org.mockito.ArgumentMatchers.eq(1),
				anyString(), any());
		assertThat(items.getValue()).singleElement().satisfies(item -> {
			assertThat(item.decision()).isEqualTo("AUDIT_REPAIR");
			assertThat(item.processState()).isEqualTo("QUEUED");
		});
	}

	@Test
	void blockedWorkIsObservedButNeverQueued() {
		CollectDataEntity task = postTask(null);
		when(taskDao.findById(7)).thenReturn(Optional.of(task));
		when(queryService.findKnownWorkIds(7)).thenReturn(Set.of());
		when(blockedWorkService.isBlocked("douyin", "blocked-1", "video")).thenReturn(true);
		when(fetchService.fetch(any())).thenReturn(envelope(
				List.of(work("blocked-1", "100", "video")), Set.of("blocked-1"), "NO_MORE"));

		service.executeQueuedCollectTask(7, 94L, CollectTriggerType.SCHEDULED);

		ArgumentCaptor<List<CollectRunFetchedItem>> items = listCaptor();
		verify(runService).storeFetchPlan(org.mockito.ArgumentMatchers.eq(94L),
				org.mockito.ArgumentMatchers.eq(7), items.capture(), org.mockito.ArgumentMatchers.eq(1),
				anyString(), any());
		assertThat(items.getValue()).singleElement().satisfies(item -> {
			assertThat(item.decision()).isEqualTo("BLOCKED");
			assertThat(item.processState()).isEqualTo("SKIPPED_BLOCKED");
		});
		verify(queryService, never()).needsAuditRequeue(anyInt(), anyString(), anyString(), anyString());
	}

	@Test
	void historicalDouyinGraphicBlockAliasIsHonored() {
		CollectDataEntity task = postTask(null);
		task.setPlatform("抖音");
		when(taskDao.findById(7)).thenReturn(Optional.of(task));
		when(queryService.findKnownWorkIds(7)).thenReturn(Set.of());
		when(blockedWorkService.isBlocked("抖音", "blocked-graphic", "graphic")).thenReturn(true);
		when(fetchService.fetch(any())).thenReturn(envelope(
				List.of(work("blocked-graphic", "100", "image")), Set.of("blocked-graphic"), "NO_MORE"));

		service.executeQueuedCollectTask(7, 941L, CollectTriggerType.SCHEDULED);

		ArgumentCaptor<List<CollectRunFetchedItem>> items = listCaptor();
		verify(runService).storeFetchPlan(org.mockito.ArgumentMatchers.eq(941L),
				org.mockito.ArgumentMatchers.eq(7), items.capture(), org.mockito.ArgumentMatchers.eq(1),
				anyString(), any());
		assertThat(items.getValue()).singleElement().satisfies(item ->
				assertThat(item.processState()).isEqualTo("SKIPPED_BLOCKED"));
	}

	@Test
	void observedItemWithoutWorkIdFailsInsteadOfDisappearingFromThePlan() {
		CollectDataEntity task = postTask(null);
		when(taskDao.findById(7)).thenReturn(Optional.of(task));
		when(queryService.findKnownWorkIds(7)).thenReturn(Set.of());
		when(fetchService.fetch(any())).thenReturn(envelope(List.of(work(null, "100", "video")), Set.of(), "NO_MORE"));

		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
				service.executeQueuedCollectTask(7, 942L, CollectTriggerType.SCHEDULED))
				.isInstanceOf(CollectFetchException.class)
				.hasMessageContaining("aweme_id");
		verify(runService, never()).storeFetchPlan(anyLong(), anyInt(), any(), anyInt(), anyString(), any());
	}

	@Test
	void duplicateSelectedIdsAreStoredOnlyOnce() {
		CollectDataEntity task = postTask(null);
		when(taskDao.findById(7)).thenReturn(Optional.of(task));
		when(queryService.findKnownWorkIds(7)).thenReturn(Set.of());
		when(fetchService.fetch(any())).thenReturn(envelope(
				List.of(work("same-work", "200", "video"), work("same-work", "200", "video")),
				Set.of("same-work"), "NO_MORE"));

		service.executeQueuedCollectTask(7, 943L, CollectTriggerType.SCHEDULED);

		ArgumentCaptor<List<CollectRunFetchedItem>> items = listCaptor();
		verify(runService).storeFetchPlan(org.mockito.ArgumentMatchers.eq(943L),
				org.mockito.ArgumentMatchers.eq(7), items.capture(), org.mockito.ArgumentMatchers.eq(2),
				anyString(), any());
		assertThat(items.getValue()).extracting(CollectRunFetchedItem::decision, CollectRunFetchedItem::processState)
				.containsExactly(tuple("NEW", "QUEUED"));
	}

	@Test
	void nonDouyinTaskNeverFallsBackToMediaDownloadInsideFetchWorker() {
		CollectDataEntity task = postTask(null);
		task.setPlatform("bilibili");
		task.setOriginaladdress("bili-fav-123");
		when(taskDao.findById(7)).thenReturn(Optional.of(task));

		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
				service.executeQueuedCollectTask(7, 944L, CollectTriggerType.SCHEDULED))
				.isInstanceOf(CollectFetchException.class)
				.hasMessageContaining("仅支持抖音");
		verifyNoInteractions(fetchService, videoDataService, hlsTranscodeService);
		verify(runService, never()).storeFetchedItems(anyLong(), any());
	}

	@Test
	void knownIdsIncludeAllHistoricalRunItemStates() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.queryForList(org.mockito.ArgumentMatchers.startsWith("SELECT videoid"),
				org.mockito.ArgumentMatchers.eq(String.class), org.mockito.ArgumentMatchers.eq(7)))
				.thenReturn(List.of("detail-1", "shared", ""));
		when(jdbc.queryForList(org.mockito.ArgumentMatchers.argThat(sql -> sql.startsWith("SELECT i.work_id")
				&& !sql.contains("queue_generation") && !sql.contains("process_state")),
				org.mockito.ArgumentMatchers.eq(String.class), org.mockito.ArgumentMatchers.eq(7)))
				.thenReturn(List.of("queued-1", "shared", "completed-1", "failed-1", "blocked-1"));
		CollectRunQueryService query = new CollectRunQueryService(jdbc, mock(SnapshotCodec.class));

		assertThat(query.findKnownWorkIds(7))
				.containsExactly("detail-1", "shared", "queued-1", "completed-1", "failed-1", "blocked-1");
	}

	@Test
	void auditRequiresRealNonEmptyVideoAndEveryGraphicFile(@TempDir Path mediaRoot) throws Exception {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.queryForObject(org.mockito.ArgumentMatchers.startsWith("SELECT COUNT(*) FROM biz_collect_data_detail"),
				org.mockito.ArgumentMatchers.eq(Integer.class), anyInt(), anyString())).thenReturn(0);
		Path video = mediaRoot.resolve("video.mp4");
		Files.writeString(video, "video");
		when(jdbc.queryForList(org.mockito.ArgumentMatchers.startsWith("SELECT videoaddr"),
				org.mockito.ArgumentMatchers.eq(String.class), anyString(), anyString(), anyString()))
				.thenReturn(List.of(video.toString()));
		CollectRunQueryService query = new CollectRunQueryService(jdbc, mock(SnapshotCodec.class));
		query.setMediaPathService(new MediaPathService(mediaRoot, "/cos"));

		assertThat(query.needsAuditRequeue(7, "douyin", "video-1", "video")).isFalse();
		Files.write(video, new byte[0]);
		assertThat(query.needsAuditRequeue(7, "douyin", "video-1", "video")).isTrue();

		Path first = mediaRoot.resolve("graphic/first.jpeg");
		Path second = mediaRoot.resolve("graphic/second.mp4");
		Files.createDirectories(first.getParent());
		Files.writeString(first, "image");
		when(jdbc.queryForList(org.mockito.ArgumentMatchers.startsWith("SELECT images"),
				org.mockito.ArgumentMatchers.eq(String.class), anyString(), anyString(), anyString()))
				.thenReturn(List.of("[\"/cos/graphic/first.jpeg\",\"/cos/graphic/second.mp4\"]"));
		assertThat(query.needsAuditRequeue(7, "douyin", "graphic-1", "image")).isTrue();
		Files.writeString(second, "video");
		assertThat(query.needsAuditRequeue(7, "douyin", "graphic-1", "image")).isFalse();
	}

	@Test
	void legacyLikeFetchStoresAPlanWithoutEnteringPerItemDownload() throws Exception {
		CollectDataEntity task = postTask(new java.util.Date());
		task.setOriginaladdress("likeMS4-author");
		when(taskDao.findById(7)).thenReturn(Optional.of(task));
		when(queryService.findKnownWorkIds(7)).thenReturn(Set.of("known-1"));
		JSONArray fetched = new JSONArray();
		fetched.add(work("new-1", "200", "video"));
		fetched.add(work("known-1", "100", "image"));
		service = spy(service);
		doReturn(fetched).when(service).getDYData(task, "Y", "collect-run-95");

		service.executeQueuedCollectTask(7, 95L, CollectTriggerType.SCHEDULED);

		ArgumentCaptor<List<CollectRunFetchedItem>> items = listCaptor();
		ArgumentCaptor<CollectRunFetchedItem.FetchWatermark> watermark =
				ArgumentCaptor.forClass(CollectRunFetchedItem.FetchWatermark.class);
		verify(runService).storeFetchPlan(org.mockito.ArgumentMatchers.eq(95L),
				org.mockito.ArgumentMatchers.eq(7), items.capture(), org.mockito.ArgumentMatchers.eq(2),
				org.mockito.ArgumentMatchers.eq("LEGACY_BOUNDED"), watermark.capture());
		assertThat(items.getValue()).extracting(CollectRunFetchedItem::workId,
				CollectRunFetchedItem::processState)
				.containsExactly(tuple("new-1", "QUEUED"), tuple("known-1", "SKIPPED_EXISTING"));
		assertThat(watermark.getValue().publishTime()).isNull();
		verify(fetchService, never()).fetch(any());
		verifyNoInteractions(videoDataService, hlsTranscodeService);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private ArgumentCaptor<List<CollectRunFetchedItem>> listCaptor() {
		return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
	}

	private CollectDataEntity postTask(java.util.Date lastSuccessfulFetchAt) {
		CollectDataEntity task = new CollectDataEntity();
		task.setId(7);
		task.setPlatform("douyin");
		task.setOriginaladdress("postMS4-author");
		task.setLastSuccessfulFetchAt(lastSuccessfulFetchAt);
		return task;
	}

	private DouyinFetchEnvelope envelope(List<JSONObject> items, Set<String> newIds, String outcome) {
		JSONObject diagnostics = new JSONObject();
		diagnostics.put("test", true);
		diagnostics.put("observedCount", items.size());
		return new DouyinFetchEnvelope(items, newIds, outcome, 2, 0, "cursor-2", diagnostics);
	}

	private JSONObject work(String id, String publishTime, String mediaType) {
		JSONObject item = new JSONObject();
		item.put("aweme_id", id);
		item.put("desc", "title-" + id);
		item.put("create_time", publishTime);
		item.put("nickname", "author");
		item.put("uid", "MS4-author");
		item.put("media_type", mediaType);
		return item;
	}
}
