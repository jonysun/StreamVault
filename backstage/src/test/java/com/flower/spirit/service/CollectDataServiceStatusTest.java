package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

class CollectDataServiceStatusTest {

	@Test
	void repeatedEmptyDouyinResponseUsesSoftBackoffRatherThanStrongRiskCooldown() {
		assertThat(CollectDataService.isDouyinRiskError("F2_UPSTREAM_SOFT_BLOCK")).isFalse();
		assertThat(CollectDataService.isDouyinRiskError("F2_UPSTREAM_UNAVAILABLE")).isFalse();
	}

	@Test
	void resolveDouyinCollectStatusDoesNotShowRiskWhenThisRunOnlySkippedExistingItems() {
		String status = CollectDataService.resolveDouyinCollectStatus("1", false, 0, 0, 57);

		assertThat(status).isEqualTo("处理完成");
	}

	@Test
	void resolveDouyinCollectStatusShowsRiskWhenDownloaderRiskHappenedWithFailures() {
		String status = CollectDataService.resolveDouyinCollectStatus("1", false, 0, 1, 0);

		assertThat(status).isEqualTo("可能触发风控本次已终止");
	}

	@Test
	void resolveDouyinCollectStatusKeepsFetchFailureHighestPriority() {
		String status = CollectDataService.resolveDouyinCollectStatus("1", true, 0, 0, 0);

		assertThat(status).isEqualTo("执行失败(抓取异常)");
	}

	@Test
	void analyzeF2FailureExtractsPythonRootCause() {
		String output = "Traceback (most recent call last):\n"
				+ "  File \"/opt/venv/lib/python3.12/site-packages/f2/apps/douyin/handler.py\", line 455, in fetch_user_post_videos\n"
				+ "    nickname_raw,\n"
				+ "UnboundLocalError: cannot access local variable 'nickname_raw' where it is not associated with a value\n";

		CollectDataService.F2FailureDiagnosis diagnosis = CollectDataService.analyzeF2Failure("post", "postMS4abc",
				"MS4abc", 36, "/app/lot/50.json", false, -1, 1, 902L, output);

		assertThat(diagnosis.errorCode()).isEqualTo("F2_INTERNAL_NICKNAME_RAW_UNBOUND");
		assertThat(diagnosis.exceptionType()).isEqualTo("UnboundLocalError");
		assertThat(diagnosis.rootCause()).contains("nickname_raw");
		assertThat(diagnosis.outputPreview()).contains("UnboundLocalError");
		assertThat(diagnosis.stackTop()).contains("handler.py:455");
		assertThat(diagnosis.toLogMessage()).contains("mode=post", "sourceId=MS4abc", "exitCode=1",
				"outFileExists=false", "outputPreview=");
	}

	@Test
	void analyzeF2FailureCarriesDiagnostics() {
		JSONObject diagnostics = new JSONObject();
		diagnostics.put("cookiePresent", true);
		JSONObject profileDiagnostic = new JSONObject();
		profileDiagnostic.put("success", false);
		profileDiagnostic.put("error", "empty response");
		diagnostics.put("profileDiagnostic", profileDiagnostic);

		CollectDataService.F2FailureDiagnosis diagnosis = CollectDataService.analyzeF2Failure("post", "postMS4abc",
				"MS4abc", 20, "/app/lot/79.json", false, -1, 1, 1515L,
				"UnboundLocalError: cannot access local variable 'nickname_raw' where it is not associated with a value",
				diagnostics);

		assertThat(diagnosis.diagnostics()).isSameAs(diagnostics);
		assertThat(diagnosis.toLogMessage()).contains("diagnostics=");
	}

	@Test
	void sortDouyinItemsByPublishTimeMovesNewestReturnedItemFirst() {
		CollectDataService service = new CollectDataService();
		JSONArray items = new JSONArray();
		items.add(douyinItem("old-pin", "2023-12-01 12-18-42"));
		items.add(douyinItem("new-work", "2026-07-05 23-28-46"));
		items.add(douyinItem("middle-work", "2026-06-29 12-13-06"));

		JSONArray sorted = service.sortDouyinItemsByPublishTime(items);

		assertThat(sorted.getJSONObject(0).getString("aweme_id")).isEqualTo("new-work");
		assertThat(sorted.getJSONObject(1).getString("aweme_id")).isEqualTo("middle-work");
		assertThat(sorted.getJSONObject(2).getString("aweme_id")).isEqualTo("old-pin");
	}

	@Test
	void buildFetchSnapshotIncludesRawAndNormalizedPublishTime() {
		CollectDataService service = new CollectDataService();
		JSONArray items = new JSONArray();
		items.add(douyinItem("new-work", "2026-07-05 23-28-46"));

		JSONObject envelope = JSONObject.parseObject(service.buildFetchSnapshot(items));
		JSONArray snapshot = envelope.getJSONArray("items");

		JSONObject item = snapshot.getJSONObject(0);
		assertThat(envelope.getInteger("version")).isEqualTo(2);
		assertThat(item.getInteger("ordinal")).isEqualTo(1);
		assertThat(item.getString("workId")).isEqualTo("new-work");
		assertThat(item.getString("publishTime")).isEqualTo("2026-07-05 23:28:46");
		assertThat(item.getString("mediaType")).isEqualTo("video");
	}

	@Test
	void buildFetchSnapshotIncludesFetchRunContextWhenPresent() {
		CollectDataService service = new CollectDataService();
		JSONArray items = new JSONArray();
		items.add(douyinItem("new-work", "2026-07-05 23-28-46"));
		CollectDataService.FetchRunContext context = new CollectDataService.FetchRunContext("collect-1-123", 1,
				"task", "postMS4abc", "Y", "post", "MS4abc", 120, 100, 80, 20, 20, "/tmp/out.json");

		JSONObject snapshot = JSONObject.parseObject(service.buildFetchSnapshot(items, context));
		JSONObject savedContext = snapshot.getJSONObject("context");
		assertThat(savedContext.getString("runId")).isEqualTo("collect-1-123");
		assertThat(savedContext.getString("fetchMode")).isEqualTo("post");
		assertThat(savedContext.getString("sourceId")).isEqualTo("MS4abc");
		assertThat(savedContext.getInteger("maxc")).isEqualTo(120);
		assertThat(savedContext.getLong("existingDetailCount")).isEqualTo(100L);
		assertThat(savedContext.getLong("successDetailCount")).isEqualTo(80L);
	}

	@Test
	void buildFetchSnapshotTruncatesAsValidJsonAtItemBoundary() {
		CollectDataService service = new CollectDataService();
		JSONArray items = new JSONArray();
		for (int i = 0; i < 400; i++) {
			JSONObject item = douyinItem("work-" + i, "2026-07-05 23-28-46");
			item.put("desc", "x".repeat(4000));
			if (i % 4 == 0) {
				item.put("video_play_addr", new JSONArray());
			}
			items.add(item);
		}

		String snapshot = service.buildFetchSnapshot(items);
		JSONObject parsed = JSONObject.parseObject(snapshot);

		assertThat(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1048576);
		assertThat(parsed.getBooleanValue("truncated")).isTrue();
		assertThat(parsed.getInteger("totalCount")).isEqualTo(400);
		assertThat(parsed.getInteger("videoTotal")).isEqualTo(300);
		assertThat(parsed.getInteger("graphicTotal")).isEqualTo(100);
	}

	@Test
	void parseSnapshotMediaStatsIgnoresTruncationMarker() {
		JSONArray snapshot = new JSONArray();
		JSONObject video = new JSONObject();
		video.put("has_video_play_addr", true);
		snapshot.add(video);
		JSONObject image = new JSONObject();
		image.put("has_video_play_addr", false);
		snapshot.add(image);
		JSONObject marker = new JSONObject();
		marker.put("snapshot_truncated", true);
		marker.put("omitted_count", 10);
		snapshot.add(marker);

		CollectDataService.SnapshotMediaStats stats = CollectDataService.parseSnapshotMediaStats(snapshot.toJSONString());

		assertThat(stats.videoCount()).isEqualTo(1);
		assertThat(stats.imageCount()).isEqualTo(1);
	}

	@Test
	void parseSnapshotMediaStatsUsesSummaryWhenSnapshotWasTruncated() {
		JSONArray snapshot = new JSONArray();
		JSONObject video = new JSONObject();
		video.put("has_video_play_addr", true);
		snapshot.add(video);
		JSONObject marker = new JSONObject();
		marker.put("snapshot_truncated", true);
		marker.put("video_total", 150);
		marker.put("image_total", 50);
		snapshot.add(marker);

		CollectDataService.SnapshotMediaStats stats = CollectDataService.parseSnapshotMediaStats(snapshot.toJSONString());

		assertThat(stats.videoCount()).isEqualTo(150);
		assertThat(stats.imageCount()).isEqualTo(50);
	}

	@Test
	void scanSnapshotMediaStatsReadsLegacyInvalidTruncatedSnapshot() {
		String snapshot = "[{\"has_video_play_addr\":true},{\"has_video_play_addr\":false}...(truncated)";

		CollectDataService.SnapshotMediaStats stats = CollectDataService.scanSnapshotMediaStats(snapshot);

		assertThat(stats.videoCount()).isEqualTo(1);
		assertThat(stats.imageCount()).isEqualTo(1);
	}

	@Test
	void resolveDouyinAuthorUsesHybridSecUidWhenListOnlyContainsNumericUid() {
		JSONObject listItem = JSONObject.parseObject("{\"uid\":\"100718677983\",\"nickname\":\"一个富贵\"}");
		JSONObject hybrid = JSONObject.parseObject("{\"data\":{\"author\":{"
				+ "\"sec_uid\":\"MS4wLjABAAAAhybrid\",\"uid\":\"100718677983\","
				+ "\"unique_id\":\"fuguifuguifugui\",\"nickname\":\"一个富贵\","
				+ "\"avatar_thumb\":\"https://img.example/avatar.jpg\"}}}");

		CollectDataService.DouyinAuthorSnapshot snapshot = CollectDataService.resolveDouyinAuthorSnapshot(
				listItem, hybrid, null, "postMS4wLjABAAAAtask");

		assertThat(snapshot.canonicalUid()).isEqualTo("MS4wLjABAAAAhybrid");
		assertThat(snapshot.uniqueId()).isEqualTo("fuguifuguifugui");
		assertThat(snapshot.numericUid()).isEqualTo("100718677983");
		assertThat(snapshot.avatar()).isEqualTo("https://img.example/avatar.jpg");
	}

	@Test
	void resolveDouyinAuthorFallsBackToCanonicalTaskSource() {
		JSONObject listItem = JSONObject.parseObject("{\"uid\":\"100718677983\",\"nickname\":\"作者\"}");

		CollectDataService.DouyinAuthorSnapshot snapshot = CollectDataService.resolveDouyinAuthorSnapshot(
				listItem, null, null, "postMS4wLjABAAAAtask");

		assertThat(snapshot.canonicalUid()).isEqualTo("MS4wLjABAAAAtask");
		assertThat(snapshot.needsProfileEnrichment()).isTrue();
	}

	@Test
	void resolveDouyinAuthorNeverPromotesNumericTaskSource() {
		JSONObject listItem = JSONObject.parseObject("{\"uid\":\"100718677983\",\"nickname\":\"作者\"}");

		CollectDataService.DouyinAuthorSnapshot snapshot = CollectDataService.resolveDouyinAuthorSnapshot(
				listItem, null, null, "post100718677983");

		assertThat(snapshot.canonicalUid()).isNull();
	}

	private static JSONObject douyinItem(String awemeId, String createTime) {
		JSONObject item = new JSONObject();
		item.put("aweme_id", awemeId);
		item.put("desc", awemeId + " desc");
		item.put("create_time", createTime);
		JSONArray playUrls = new JSONArray();
		playUrls.add("https://example.test/" + awemeId + ".mp4");
		item.put("video_play_addr", playUrls);
		return item;
	}
}
