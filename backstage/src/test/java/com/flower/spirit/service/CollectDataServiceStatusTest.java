package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

class CollectDataServiceStatusTest {

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

		JSONArray snapshot = JSONArray.parseArray(service.buildFetchSnapshot(items));

		JSONObject item = snapshot.getJSONObject(0);
		assertThat(item.getInteger("index")).isEqualTo(1);
		assertThat(item.getString("aweme_id")).isEqualTo("new-work");
		assertThat(item.getString("create_time")).isEqualTo("2026-07-05 23-28-46");
		assertThat(item.getString("publish_time")).isEqualTo("2026-07-05 23:28:46");
		assertThat(item.getBoolean("has_video_play_addr")).isTrue();
	}

	@Test
	void buildFetchSnapshotIncludesFetchRunContextWhenPresent() {
		CollectDataService service = new CollectDataService();
		JSONArray items = new JSONArray();
		items.add(douyinItem("new-work", "2026-07-05 23-28-46"));
		CollectDataService.FetchRunContext context = new CollectDataService.FetchRunContext("collect-1-123", 1,
				"task", "postMS4abc", "Y", "post", "MS4abc", 120, 100, 80, 20, 20, "/tmp/out.json");

		JSONArray snapshot = JSONArray.parseArray(service.buildFetchSnapshot(items, context));

		JSONObject item = snapshot.getJSONObject(0);
		assertThat(item.getString("runId")).isEqualTo("collect-1-123");
		assertThat(item.getString("fetchMode")).isEqualTo("post");
		assertThat(item.getString("sourceId")).isEqualTo("MS4abc");
		assertThat(item.getInteger("maxc")).isEqualTo(120);
		assertThat(item.getLong("existingDetailCount")).isEqualTo(100L);
		assertThat(item.getLong("successDetailCount")).isEqualTo(80L);
	}

	@Test
	void buildFetchSnapshotTruncatesAsValidJsonAtItemBoundary() {
		CollectDataService service = new CollectDataService();
		JSONArray items = new JSONArray();
		for (int i = 0; i < 200; i++) {
			JSONObject item = douyinItem("work-" + i, "2026-07-05 23-28-46");
			item.put("desc", "x".repeat(4000));
			if (i % 4 == 0) {
				item.put("video_play_addr", new JSONArray());
			}
			items.add(item);
		}

		String snapshot = service.buildFetchSnapshot(items);
		JSONArray parsed = JSONArray.parseArray(snapshot);
		JSONObject marker = parsed.getJSONObject(parsed.size() - 1);

		assertThat(snapshot.length()).isLessThanOrEqualTo(500000);
		assertThat(marker.getBooleanValue("snapshot_truncated")).isTrue();
		assertThat(marker.getInteger("total_count")).isEqualTo(200);
		assertThat(marker.getInteger("video_total")).isEqualTo(150);
		assertThat(marker.getInteger("image_total")).isEqualTo(50);
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
