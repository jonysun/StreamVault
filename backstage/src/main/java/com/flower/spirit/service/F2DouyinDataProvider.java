package com.flower.spirit.service;

import org.springframework.stereotype.Service;

import com.flower.spirit.utils.DouUtil;

@Service
public class F2DouyinDataProvider implements DouyinDataProvider {
	private final DouyinIncrementalFetchService incrementalFetchService;

	public F2DouyinDataProvider(DouyinIncrementalFetchService incrementalFetchService) {
		this.incrementalFetchService = incrementalFetchService;
	}

	@Override
	public String name() { return "F2"; }

	@Override
	public DouyinFetchEnvelope fetchAuthorWorks(DouyinFetchRequest request) {
		return incrementalFetchService.fetch(request);
	}

	@Override
	public String fetchWorkData(String workId) {
		throw new UnsupportedOperationException("F2 work detail is owned by DouyinPlatformAdapter");
	}

	@Override
	public java.util.Map<String, String> fetchDirect(String url) {
		return fetchDirect(url, null);
	}

	public java.util.Map<String, String> fetchDirect(String url, String cookie) {
		return DouUtil.downVideoWithoutHybridSupplement(url, null, cookie);
	}

}
