package com.flower.spirit.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.flower.spirit.config.Global;
import com.flower.spirit.dao.CookiesConfigDao;
import com.flower.spirit.entity.CookiesConfigEntity;
import com.flower.spirit.entity.TikTokConfigEntity;

@Service
public class PlatformCookieService {

	public static final String STRATEGY_ROUND_ROBIN = "round_robin";
	public static final String STRATEGY_RISK_SHIFT = "risk_shift";
	private static final long RISK_COOLDOWN_MS = 10 * 60 * 1000L;
	private static final Logger logger = LoggerFactory.getLogger(PlatformCookieService.class);

	private final Map<String, AtomicInteger> cursors = new ConcurrentHashMap<>();
	private final Map<String, Long> riskUntil = new ConcurrentHashMap<>();

	@Autowired(required = false)
	private TikTokConfigService tikTokConfigService;

	@Autowired(required = false)
	private CookiesConfigDao cookiesConfigDao;

	public String currentDouyinCookie(String purpose) {
		TikTokConfigEntity config = tikTokConfigService == null ? null : tikTokConfigService.getData();
		String pool = config == null ? null : config.getCookiepool();
		String legacy = firstNotBlank(config == null ? null : config.getCookies(), Global.tiktokCookie);
		String strategy = config == null ? null : config.getCookiestrategy();
		String cookie = selectCookie("抖音", strategy, pool, legacy, purpose);
		if (cookie != null && !cookie.trim().isEmpty()) {
			Global.tiktokCookie = cookie;
		}
		return cookie;
	}

	public String currentKuaishouCookie(String purpose) {
		CookiesConfigEntity config = loadCookiesConfig();
		String pool = config == null ? null : config.getKuaishouCookiePool();
		String legacy = config == null ? null : config.getKuaishouCookie();
		String strategy = config == null ? null : config.getKuaishouCookieStrategy();
		return selectCookie("快手", strategy, pool, legacy, purpose);
	}

	private CookiesConfigEntity loadCookiesConfig() {
		if (cookiesConfigDao == null) {
			return Global.cookie_manage;
		}
		List<CookiesConfigEntity> configs = cookiesConfigDao.findAll();
		if (configs.isEmpty()) {
			return Global.cookie_manage;
		}
		return configs.get(0);
	}

	public String selectCookie(String platform, String strategy, String cookiePool, String legacyCookie, String purpose) {
		List<String> cookies = parseCookiePool(cookiePool, legacyCookie);
		if (cookies.isEmpty()) {
			return "";
		}
		String safePlatform = platform == null ? "unknown" : platform;
		String safeStrategy = isBlank(strategy) ? STRATEGY_ROUND_ROBIN : strategy.trim();
		if (STRATEGY_RISK_SHIFT.equals(safeStrategy)) {
			return selectRiskShift(safePlatform, cookies);
		}
		AtomicInteger cursor = cursors.computeIfAbsent(safePlatform + ":" + safeStrategy, key -> new AtomicInteger(0));
		int index = Math.floorMod(cursor.getAndIncrement(), cookies.size());
		return cookies.get(index);
	}

	public void reportRisk(String platform, String cookie, String reason) {
		if (isBlank(platform) || isBlank(cookie)) {
			return;
		}
		long now = System.currentTimeMillis();
		purgeExpiredRisks(now);
		riskUntil.put(riskKey(platform, cookie), now + RISK_COOLDOWN_MS);
		logger.warn("platform cookie risk platform={} reason={} cooldownMs={}", platform, reason, RISK_COOLDOWN_MS);
	}

	public void reportSuccess(String platform, String cookie) {
		// A successful in-flight request must not cancel a newer risk cooldown.
	}

	public boolean isRiskSignal(String text) {
		if (text == null) {
			return false;
		}
		String lower = text.toLowerCase();
		return lower.contains("risk") || lower.contains("verify") || lower.contains("login")
				|| lower.contains("403") || lower.contains("401") || text.contains("验证码") || text.contains("风控");
	}

	public Map<String, Object> cookieStatus(String platform) {
		Map<String, Object> status = new HashMap<>();
		long now = System.currentTimeMillis();
		purgeExpiredRisks(now);
		int cooling = 0;
		for (Map.Entry<String, Long> entry : riskUntil.entrySet()) {
			if (entry.getKey().startsWith(platform + ":") && entry.getValue() > now) {
				cooling++;
			}
		}
		status.put("cooling", cooling);
		status.put("cooldownMinutes", 10);
		return status;
	}

	private String selectRiskShift(String platform, List<String> cookies) {
		long now = System.currentTimeMillis();
		purgeExpiredRisks(now);
		AtomicInteger cursor = cursors.computeIfAbsent(platform + ":" + STRATEGY_RISK_SHIFT, key -> new AtomicInteger(0));
		int start = Math.floorMod(cursor.getAndIncrement(), cookies.size());
		long earliestUntil = Long.MAX_VALUE;
		for (int i = 0; i < cookies.size(); i++) {
			String cookie = cookies.get((start + i) % cookies.size());
			long until = riskUntil.getOrDefault(riskKey(platform, cookie), 0L);
			if (until <= now) {
				return cookie;
			}
			if (until < earliestUntil) {
				earliestUntil = until;
			}
		}
		logger.warn("all platform cookies are cooling platform={} earliestAvailableInMs={}", platform, Math.max(0, earliestUntil - now));
		return "";
	}

	private List<String> parseCookiePool(String cookiePool, String legacyCookie) {
		List<String> cookies = new ArrayList<>();
		if (!isBlank(cookiePool)) {
			String[] lines = cookiePool.split("\\r?\\n");
			for (String line : lines) {
				if (!isBlank(line)) {
					cookies.add(line.trim());
				}
			}
		}
		if (cookies.isEmpty() && !isBlank(legacyCookie)) {
			cookies.add(legacyCookie.trim());
		}
		return cookies;
	}

	private String riskKey(String platform, String cookie) {
		return platform + ":" + cookie.hashCode();
	}

	private void purgeExpiredRisks(long now) {
		riskUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
	}

	private String firstNotBlank(String first, String second) {
		return !isBlank(first) ? first.trim() : (!isBlank(second) ? second.trim() : "");
	}

	private boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
