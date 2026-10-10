package com.flower.spirit.service;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

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
	private final AtomicInteger autoProviderTurn = new AtomicInteger();

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
		if (!isAuto()) return markProvider(current().fetchAuthorWorks(request), isDtkOnly() ? "DTK" : "F2", null);
		String first = nextAutoProvider();
		String second = "F2".equals(first) ? "DTK" : "F2";
		RuntimeException firstFailure = null;
		boolean attemptedFirst = false;
		if ("DTK".equals(first) || request.cookie() != null && !request.cookie().isBlank()) {
			attemptedFirst = true;
			try {
				return markProvider(fetchAuthorWorks(first, request), first, null);
			} catch (RuntimeException error) {
				if (!shouldFailover(error)) throw error;
				firstFailure = error;
			}
		} else {
			firstFailure = new CollectFetchException("F2_COOKIE_MISSING", "F2 cookie is missing");
		}
		if ("F2".equals(second) && (request.cookie() == null || request.cookie().isBlank())) throw firstFailure;
		logger.warn("[DouyinProvider] failover operation=AUTHOR_LIST from={} to={} reason={}", first, second,
				errorCode(firstFailure));
		try {
			return markProvider(fetchAuthorWorks(second, request), attemptedFirst ? first + "->" + second : second,
					errorCode(firstFailure));
		} catch (RuntimeException fallbackError) {
			fallbackError.addSuppressed(firstFailure);
			throw fallbackError;
		}
	}

	private DouyinFetchEnvelope fetchAuthorWorks(String provider, DouyinFetchRequest request) {
		return "F2".equals(provider) ? f2.fetchAuthorWorks(request) : dtk.fetchAuthorWorks(request);
	}

	private String nextAutoProvider() {
		return (autoProviderTurn.getAndIncrement() & 1) == 0 ? "F2" : "DTK";
	}

	private DouyinFetchEnvelope markProvider(DouyinFetchEnvelope envelope, String path, String reason) {
		if (envelope == null) return null;
		JSONObject diagnostics = envelope.diagnostics();
		if (diagnostics == null) {
			diagnostics = new JSONObject(true);
		}
		diagnostics.put("providerPath", path);
		if (reason != null && !reason.isBlank()) diagnostics.put("providerReason", reason);
		if (envelope.diagnostics() != null) return envelope;
		return new DouyinFetchEnvelope(envelope.items(), envelope.newWorkIds(), envelope.outcome(),
				envelope.pagesFetched(), envelope.emptyPages(), envelope.lastCursor(), envelope.backfillCursor(),
			envelope.backfillComplete(), envelope.backfillVerifying(), envelope.backfillCleanPasses(), diagnostics);
	}

	private String errorCode(Throwable error) {
		if (error instanceof CollectFetchException fetch && fetch.getErrorCode() != null) return fetch.getErrorCode();
		return error == null ? "F2_FAILOVER" : error.getClass().getSimpleName();
	}

	public Map<String, String> fetchDirect(String url, String cookie) {
		if (isDtkOnly()) return dtk.fetchDirect(url);
		if (!isAuto()) return f2.fetchDirect(url, cookie);
		String first = nextAutoProvider();
		String second = "F2".equals(first) ? "DTK" : "F2";
		RuntimeException firstFailure = null;
		if ("DTK".equals(first) || cookie != null && !cookie.isBlank()) {
			try {
				Map<String, String> result = fetchDirect(first, url, cookie);
				if (result != null && result.get("videoplay") != null && !result.get("videoplay").isBlank()) return result;
				firstFailure = new CollectFetchException("EMPTY_MEDIA_RESULT", first + " returned no media URL");
			} catch (RuntimeException error) {
				if (!shouldFailover(error)) throw error;
				firstFailure = error;
			}
		} else {
			firstFailure = new CollectFetchException("F2_COOKIE_MISSING", "F2 cookie is missing");
		}
		if ("F2".equals(second) && (cookie == null || cookie.isBlank())) throw firstFailure;
		logger.warn("[DouyinProvider] failover operation=DIRECT from={} to={} reason={}", first, second,
				errorCode(firstFailure));
		try {
			return fetchDirect(second, url, cookie);
		} catch (RuntimeException fallbackError) {
			fallbackError.addSuppressed(firstFailure);
			throw fallbackError;
		}
	}

	private Map<String, String> fetchDirect(String provider, String url, String cookie) {
		return "F2".equals(provider) ? f2.fetchDirect(url, cookie) : dtk.fetchDirect(url);
	}

	public JSONObject fetchAuthorProfile(String secUid) {
		return dtk.fetchAuthorProfile(secUid);
	}

	public JSONObject fetchAuthorProfileByUniqueId(String uniqueId) {
		return dtk.fetchAuthorProfileByUniqueId(uniqueId);
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
			if (current instanceof IOException) {
				String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(java.util.Locale.ROOT);
				if (message.contains("http ") || message.contains("media response")
						|| message.contains("connection") || message.contains("timed out")
						|| message.contains("timeout") || message.contains("stream")) return true;
			}
			if (current instanceof com.flower.spirit.platform.DouyinGlobalCooldownException cooldown
					&& cooldown.actualUpstreamFailure()) return true;
			if (current instanceof com.flower.spirit.platform.DouyinWorkFetchException fetch
					&& (fetch.retryable() || fetch.cooldownApplied())) return true;
			if (current instanceof CollectFetchException fetch) {
				String code = fetch.getErrorCode();
				if (code != null && (code.contains("UPSTREAM") || code.contains("TIMEOUT")
						|| code.contains("NETWORK") || code.contains("SOFT_BLOCK")
						|| code.contains("RATE_LIMIT") || code.contains("COOKIE_COOLDOWN")
						|| code.contains("PAGINATION") || code.contains("NODE_COOLDOWN"))) return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
