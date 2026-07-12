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
				36, "/app/lot/50.json", false, -1, 1, 902L, output);

		assertThat(diagnosis.errorCode()).isEqualTo("F2_INTERNAL_NICKNAME_RAW_UNBOUND");
		assertThat(diagnosis.exceptionType()).isEqualTo("UnboundLocalError");
		assertThat(diagnosis.rootCause()).contains("nickname_raw");
		assertThat(diagnosis.stackTop()).contains("handler.py:455");
		assertThat(diagnosis.toLogMessage()).contains("mode=post", "exitCode=1", "outFileExists=false");
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
