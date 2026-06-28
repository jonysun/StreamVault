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
}
