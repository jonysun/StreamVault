package com.flower.spirit.service;

import com.alibaba.fastjson.JSONObject;
import java.util.Map;

/** Provider boundary for author lists and work metadata. Implementations must not fall back to another provider. */
public interface DouyinDataProvider {

	String name();

	DouyinFetchEnvelope fetchAuthorWorks(DouyinFetchRequest request);

	String fetchWorkData(String workId);

	default Map<String, String> fetchDirect(String url) {
		throw new UnsupportedOperationException("Direct URL parsing is not supported by this provider");
	}

	default JSONObject fetchAuthorProfile(String secUid) {
		return null;
	}

	default JSONObject fetchAuthorProfileByUniqueId(String uniqueId) {
		return null;
	}
}
