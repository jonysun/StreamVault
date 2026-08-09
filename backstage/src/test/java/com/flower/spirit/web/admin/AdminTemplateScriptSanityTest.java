package com.flower.spirit.web.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class AdminTemplateScriptSanityTest {

	private static final Pattern TOP_LEVEL_FUNCTION = Pattern.compile(
			"(?m)^    function\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");

	@Test
	void topLevelFunctionNamesAreUnique() throws IOException {
		for (String template : List.of("index.html", "config.html")) {
			String source = template(template);
			Map<String, Integer> counts = new HashMap<>();
			Matcher matcher = TOP_LEVEL_FUNCTION.matcher(source);
			while (matcher.find()) {
				counts.merge(matcher.group(1), 1, Integer::sum);
			}
			List<String> duplicates = new ArrayList<>();
			counts.forEach((name, count) -> {
				if (count > 1) duplicates.add(name);
			});
			assertThat(duplicates)
					.as("duplicate top-level JavaScript functions in %s", template)
					.isEmpty();
		}
	}

	@Test
	void graphicVideoPreviewsContainAutoplayLifecycle() throws IOException {
		String index = template("index.html");
		String graphic = template("graphicContent.html");

		assertThat(index).contains("feed-graphic-video-host", "function mountGraphicSlideVideo(itemEl, host)",
				"setupVideoSource(video, src, true)", "goNextGraphicSlide(itemEl, true)");
		assertThat(index).contains("feed-playback-controller.js")
				.doesNotContain("feed-player-pool.js", "new window.AdminFeed.PlayerPool");
		assertThat(graphic).contains("playActivePreviewVideo(true)", "pauseActivePreviewVideo(true)",
				"id=\"previewVideo\" controls playsinline");
	}

	@Test
	void authorListContainsManualProfileRefreshAction() throws IOException {
		String authorList = template("authorList.html");

		assertThat(authorList).contains("refreshProfileBtn", "/admin/api/refreshDouyinAuthorProfile",
				"result.jobId", "result.state", "result.promoted");
	}

	@Test
	void indexGroupsInfrequentActionsInMoreMenu() throws IOException {
		String index = template("index.html");

		assertThat(index).contains("id=\"feedMoreActions\"", "id=\"feedMoreBtn\"",
				"id=\"feedMoreMenu\"", "id=\"feedEditBtn\"", "id=\"feedAutoNextBtn\"",
				"id=\"feedDeleteBtn\"", "function setFeedMoreMenuOpen(open)",
				"function closeFeedMoreMenu()", "click.feedMoreOutside", "keydown.feedMoreEscape");
	}

	@Test
	void indexPassesCanonicalPlatformKeyToAuthorProfileApis() throws IOException {
		String index = template("index.html");

		assertThat(index).contains("data-platformkey=\"", "platformkey: this.getAttribute('data-platformkey')",
				"/admin/api/authorProfileSummary", "/admin/api/authorProfileWorks");
	}

	@Test
	void indexUsesKeysetFeedWithoutBreakingLegacyFallbacks() throws IOException {
		String index = template("index.html");

		assertThat(index).contains("function shouldUseFeedCursor()", "function buildFeedCursorRequestOption(pageSize)",
				"$.get('/admin/api/media-feed', option)", "feedNextCursor = record.nextCursor || ''",
				"feedNextCursor = '';", "keyset media feed is disabled", "feedUseKeyset = false",
				"feedOrder !== 'random'", "feedAuthorUid && feedAuthorPlatformKey",
				"data-platformkey=\"");
	}

	@Test
	void collectionPageShowsSplitPipelineStatusAndIncrementalTables() throws IOException {
		String template = template("collectDataList.html");

		assertThat(template).contains("downloadQueued", "downloadRetryWait", "downloadFailed",
				"抓取：", "待下载 ", "latestStopReason", "latestFetchWarning");
		assertThat(template).contains("retryCollectItem", "retryFailedRun", "startFullAudit",
				"/admin/api/collectData/retryItem", "/admin/api/collectData/retryFailedItems",
				"/admin/api/collectData/audit");
		assertThat(template).contains("attemptCount", "availableAt", "errorDetail", "updatedAt",
				"function loadLatestRunItemPage", "afterId:afterId", "limit:200");
		assertThat(template).doesNotContain("JSON.stringify(record.items)");
	}

	@Test
	void douyinConfigContainsGlobalRiskCooldownInputAndSubmissionField() throws IOException {
		String config = template("config.html");

		assertThat(config).contains("tiktokriskcooldownminutes", "min=\"1\"", "max=\"1440\"",
				"option['riskCooldownMinutes']", "tiktok.riskCooldownMinutes");
	}

	@Test
	void homeShowsTaskProgressAndIndividualDownloadQueues() throws IOException {
		String home = template("home.html");

		assertThat(home).contains("downloadTaskProgress", "activeDownloadList", "waitingDownloadList",
				"loadDownloadQueueState", "runningItems", "waitingItems", "downloadTasks",
				"queueCount", "fetchQueue", "downloadQueue", "markDownloadQueueError",
				"markTaskStatusError");
	}

	@Test
	void downloadCenterShowsPersistentQueueHistoryAndControls() throws IOException {
		String center = template("downloadCenter.html");

		assertThat(center).contains("/admin/api/download-center/summary", "/admin/api/download-center/items",
				"/admin/api/download-center/retry-batch", "/admin/api/download-center/history/hide",
				"/admin/api/setBackgroundTaskPause", "YOUTUBE_COLLECTION", "SINGLE_LINK", "COLLECT",
				"当前任务", "历史记录", "重试所选失败任务", "清除所选记录");
	}

	private String template(String name) throws IOException {
		try (var input = getClass().getResourceAsStream("/templates/admin/" + name)) {
			if (input == null) throw new IOException(name + " template not found");
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
