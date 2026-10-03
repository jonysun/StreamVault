package com.flower.spirit.service;

import org.springframework.stereotype.Service;

import com.flower.spirit.config.Global;
import com.alibaba.fastjson.JSONObject;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class DouyinDataProviderService {
	private static final Logger logger = LoggerFactory.getLogger(DouyinDataProviderService.class);
	private final F2DouyinDataProvider f2;
	private final DtkDouyinDataProvider dtk;

	public DouyinDataProviderService(F2DouyinDataProvider f2, DtkDouyinDataProvider dtk) {
		this.f2 = f2;
		this.dtk = dtk;
	}

	public DouyinDataProvider current() {
		return isDtkOnly() ? dtk : f2;
	}

	public boolean isDtk() { return isDtkOnly(); }
	public boolean isDtkOnly() { return "DTK".equalsIgnoreCase(Global.douyinProvider); }
	public boolean isAuto() { return "AUTO".equalsIgnoreCase(Global.douyinProvider); }

	public DouyinFetchEnvelope fetchAuthorWorks(DouyinFetchRequest request) {
		if (!isAuto()) return current().fetchAuthorWorks(request);
		if (request.cookie() == null || request.cookie().isBlank()) {
			logger.info("[DouyinProvider] operation=AUTHOR_LIST provider=DTK reason=F2_COOKIE_MISSING");
			return dtk.fetchAuthorWorks(request);
		}
		try {
			return f2.fetchAuthorWorks(request);
		} catch (RuntimeException error) {
			if (!shouldFailover(error)) throw error;
			logger.warn("[DouyinProvider] failover operation=AUTHOR_LIST from=F2 to=DTK reason={}", error.getMessage());
			return dtk.fetchAuthorWorks(request);
		}
	}

	public Map<String, String> fetchDirect(String url, String cookie) {
		if (!isAuto()) return current().fetchDirect(url);
		try {
			Map<String, String> result = f2.fetchDirect(url, cookie);
			if (result != null && result.get("videoplay") != null && !result.get("videoplay").isBlank()) return result;
			logger.warn("[DouyinProvider] failover operation=DIRECT from=F2 to=DTK reason=F2_EMPTY_MEDIA_RESULT");
		} catch (RuntimeException error) {
			if (!shouldFailover(error)) throw error;
			logger.warn("[DouyinProvider] failover operation=DIRECT from=F2 to=DTK reason={}", error.getMessage());
		}
		return dtk.fetchDirect(url);
	}

	public JSONObject fetchAuthorProfile(String secUid) {
		if (!isAuto()) return current().fetchAuthorProfile(secUid);
		try {
			JSONObject result = f2.fetchAuthorProfile(secUid);
			if (result != null && !result.isEmpty()) return result;
		} catch (RuntimeException error) {
			if (!shouldFailover(error)) throw error;
			logger.warn("[DouyinProvider] failover operation=AUTHOR_PROFILE from=F2 to=DTK reason={}", error.getMessage());
		}
		logger.info("[DouyinProvider] operation=AUTHOR_PROFILE provider=DTK reason=F2_EMPTY_RESULT");
		return dtk.fetchAuthorProfile(secUid);
	}

	public String fetchDtkWorkData(String workId) {
		return dtk.fetchWorkData(workId);
	}

	public JSONObject fetchDtkAuthorProfile(String secUid) {
		return dtk.fetchAuthorProfile(secUid);
	}

	public boolean shouldFailover(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof com.flower.spirit.platform.DouyinGlobalCooldownException cooldown
					&& cooldown.actualUpstreamFailure()) return true;
			if (current instanceof com.flower.spirit.platform.DouyinWorkFetchException fetch
					&& (fetch.retryable() || fetch.cooldownApplied())) return true;
			if (current instanceof CollectFetchException fetch) {
				String code = fetch.getErrorCode();
				if (code != null && (code.contains("UPSTREAM") || code.contains("TIMEOUT")
						|| code.contains("NETWORK") || code.contains("SOFT_BLOCK")
						|| code.contains("RATE_LIMIT") || code.contains("COOKIE_COOLDOWN"))) return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
