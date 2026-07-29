package com.flower.spirit.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.flower.spirit.config.Global;
import com.flower.spirit.dao.CookiesConfigDao;
import com.flower.spirit.entity.CookiesConfigEntity;
import com.flower.spirit.entity.TikTokConfigEntity;
import com.flower.spirit.platform.PlatformCatalog;

@Service
public class PlatformCookieService {

	public static final String STRATEGY_ROUND_ROBIN = "round_robin";
	public static final String STRATEGY_RISK_SHIFT = "risk_shift";
	private static final long COOKIE_RISK_COOLDOWN_MS = 10 * 60 * 1000L;
	private static final String DOUYIN_PLATFORM_KEY = "douyin";
	private static final Logger logger = LoggerFactory.getLogger(PlatformCookieService.class);

	private final Map<String, AtomicInteger> cursors = new ConcurrentHashMap<>();
	private final Map<String, Long> riskUntil = new ConcurrentHashMap<>();
	private final AtomicLong douyinGlobalRiskStartedAtMs = new AtomicLong(0);

	@Autowired(required = false)
	private TikTokConfigService tikTokConfigService;

	@Autowired(required = false)
	private CookiesConfigDao cookiesConfigDao;

	public String currentDouyinCookie(String purpose) {
		TikTokConfigEntity config = tikTokConfigService == null ? null : tikTokConfigService.getData();
		String pool = config == null ? null : config.getCookiepool();
		String legacy = firstNotBlank(config == null ? null : config.getCookies(), Global.tiktokCookie);
		String strategy = config == null ? null : config.getCookiestrategy();
		String cookie = selectCookie(Global.platform.douyin.name(), strategy, pool, legacy, purpose);
		if (cookie != null && !cookie.trim().isEmpty()) {
			Global.tiktokCookie = cookie;
		}
		return cookie;
	}

	public boolean hasConfiguredDouyinCookie() {
		TikTokConfigEntity config = tikTokConfigService == null ? null : tikTokConfigService.getData();
		String pool = config == null ? null : config.getCookiepool();
		String legacy = firstNotBlank(config == null ? null : config.getCookies(), Global.tiktokCookie);
		return !parseCookiePool(pool, legacy).isEmpty();
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
		String safePlatform = canonicalPlatform(platform);
		if (DOUYIN_PLATFORM_KEY.equals(safePlatform) && isDouyinGlobalCooldownActive()) {
			return "";
		}
		List<String> cookies = parseCookiePool(cookiePool, legacyCookie);
		if (cookies.isEmpty()) {
			return "";
		}
		String safeStrategy = isBlank(strategy) ? STRATEGY_ROUND_ROBIN : strategy.trim();
		return selectAvailable(safePlatform, safeStrategy, cookies);
	}

	public void reportRisk(String platform, String cookie, String reason) {
		if (isBlank(platform) || isBlank(cookie)) {
			return;
		}
		String safePlatform = canonicalPlatform(platform);
		long now = System.currentTimeMillis();
		if (DOUYIN_PLATFORM_KEY.equals(safePlatform)) {
			douyinGlobalRiskStartedAtMs.accumulateAndGet(now, Math::max);
			long cooldownMs = douyinRiskCooldownMillis();
			logger.warn("platform global risk cooldown platform={} reason={} cooldownMs={}", safePlatform, reason,
					cooldownMs);
			return;
		}
		purgeExpiredRisks(now);
		riskUntil.put(riskKey(safePlatform, cookie), now + COOKIE_RISK_COOLDOWN_MS);
		logger.warn("platform cookie risk platform={} reason={} cooldownMs={}", safePlatform, reason,
				COOKIE_RISK_COOLDOWN_MS);
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
		String safePlatform = canonicalPlatform(platform);
		Map<String, Object> status = new HashMap<>();
		long now = System.currentTimeMillis();
		if (DOUYIN_PLATFORM_KEY.equals(safePlatform)) {
			long remainingMs = douyinGlobalCooldownRemainingMillis(now);
			status.put("cooling", remainingMs > 0 ? 1 : 0);
			status.put("cooldownMinutes", douyinRiskCooldownMinutes());
			status.put("remainingMs", remainingMs);
			return status;
		}
		purgeExpiredRisks(now);
		int cooling = 0;
		for (Map.Entry<String, Long> entry : riskUntil.entrySet()) {
			if (entry.getKey().startsWith(safePlatform + ":") && entry.getValue() > now) {
				cooling++;
			}
		}
		status.put("cooling", cooling);
		status.put("cooldownMinutes", 10);
		return status;
	}

	public boolean isDouyinGlobalCooldownActive() {
		return douyinGlobalCooldownRemainingMillis() > 0;
	}

	public long douyinGlobalCooldownRemainingMillis() {
		return douyinGlobalCooldownRemainingMillis(System.currentTimeMillis());
	}

	public Instant douyinGlobalCooldownRetryAt(Duration safetyBuffer) {
		long now = System.currentTimeMillis();
		long deadline = Math.max(now, douyinGlobalCooldownUntilEpochMillis());
		long bufferMs = safetyBuffer == null ? 0 : Math.max(0, safetyBuffer.toMillis());
		return Instant.ofEpochMilli(deadline).plusMillis(bufferMs);
	}

	private String selectAvailable(String platform, String strategy, List<String> cookies) {
		long now = System.currentTimeMillis();
		purgeExpiredRisks(now);
		AtomicInteger cursor = cursors.computeIfAbsent(platform + ":" + strategy, key -> new AtomicInteger(0));
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
		logger.warn("all platform cookies are cooling platform={} strategy={} earliestAvailableInMs={}",
				platform, strategy, Math.max(0, earliestUntil - now));
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

	private String canonicalPlatform(String platform) {
		String canonical = PlatformCatalog.canonicalKey(platform, null);
		return isBlank(canonical) ? "unknown" : canonical;
	}

	private long douyinGlobalCooldownRemainingMillis(long now) {
		return Math.max(0, douyinGlobalCooldownUntilEpochMillis() - now);
	}

	private long douyinGlobalCooldownUntilEpochMillis() {
		long startedAt = douyinGlobalRiskStartedAtMs.get();
		return startedAt <= 0 ? 0 : startedAt + douyinRiskCooldownMillis();
	}

	private long douyinRiskCooldownMillis() {
		return Duration.ofMinutes(douyinRiskCooldownMinutes()).toMillis();
	}

	private int douyinRiskCooldownMinutes() {
		return tikTokConfigService == null ? TikTokConfigService.DEFAULT_RISK_COOLDOWN_MINUTES
				: tikTokConfigService.getRiskCooldownMinutes();
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
