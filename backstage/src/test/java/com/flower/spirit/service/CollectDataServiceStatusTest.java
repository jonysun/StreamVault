package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;

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
		assertThat(diagnosis.stackTop()).contains("handler.py:455");
		assertThat(diagnosis.toLogMessage()).contains("mode=post", "sourceId=MS4abc", "exitCode=1",
				"outFileExists=false");
	}
}
