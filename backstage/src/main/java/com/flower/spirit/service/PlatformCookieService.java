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
	private static final long DOUYIN_SOFT_BLOCK_COOLDOWN_MS = 5 * 60 * 1000L;
	private static final String DOUYIN_PLATFORM_KEY = "douyin";
	private static final Logger logger = LoggerFactory.getLogger(PlatformCookieService.class);

	private final Map<String, AtomicInteger> cursors = new ConcurrentHashMap<>();
	private final Map<String, Long> riskUntil = new ConcurrentHashMap<>();
	private final Map<String, Long> successAt = new ConcurrentHashMap<>();
	private final AtomicLong douyinGlobalRiskStartedAtMs = new AtomicLong(0);
	private final AtomicLong douyinGlobalSoftBlockStartedAtMs = new AtomicLong(0);

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
		if (DOUYIN_PLATFORM_KEY.equals(safePlatform) && isDouyinGlobalRiskCooldownActive()) {
			return "";
		}
		List<String> cookies = parseCookiePool(cookiePool, legacyCookie);
		if (cookies.isEmpty()) {
			return "";
		}
		String safeStrategy = isBlank(strategy) ? STRATEGY_ROUND_ROBIN : strategy.trim();
		return selectAvailable(safePlatform, safeStrategy, cookies);
	}

	/**
	 * Records a platform risk signal.
	 *
	 * @return {@code true} when the signal was accepted as confirmed risk evidence;
	 *         {@code false} when it was ignored or, for Douyin, deliberately suppressed
	 *         because it lacks confirmation.
	 */
	public boolean reportRisk(String platform, String cookie, String reason) {
		if (isBlank(platform) || isBlank(cookie)) {
			return false;
		}
		String safePlatform = canonicalPlatform(platform);
		long now = System.currentTimeMillis();
		if (DOUYIN_PLATFORM_KEY.equals(safePlatform)) {
			String confirmedEvidence = confirmedDouyinRiskEvidence(reason);
			if (confirmedEvidence == null) {
				logger.warn("platform risk signal suppressed platform={} scope=GLOBAL_RISK reason=UNCONFIRMED", safePlatform);
				return false;
			}
			successAt.remove(riskKey(safePlatform, cookie));
			douyinGlobalRiskStartedAtMs.accumulateAndGet(now, Math::max);
			long cooldownMs = douyinRiskCooldownMillis();
			logger.warn("platform cooldown platform={} scope=GLOBAL_RISK reason={} cooldownMs={}", safePlatform,
					confirmedEvidence,
					cooldownMs);
			return true;
		}
		purgeExpiredRisks(now);
		successAt.remove(riskKey(safePlatform, cookie));
		riskUntil.put(riskKey(safePlatform, cookie), now + COOKIE_RISK_COOLDOWN_MS);
		logger.warn("platform cookie risk platform={} reason={} cooldownMs={}", safePlatform, reason,
				COOKIE_RISK_COOLDOWN_MS);
		return true;
	}

	public void reportSuccess(String platform, String cookie) {
		if (isBlank(platform) || isBlank(cookie)) return;
		successAt.put(riskKey(canonicalPlatform(platform), cookie), System.currentTimeMillis());
		// Recording evidence must not cancel a newer risk cooldown.
	}

	/** Records a confirmed repeated empty detail response without treating it as an authentication failure. */
	public void reportDetailSoftBlock(String platform, String reason) {
		if (!DOUYIN_PLATFORM_KEY.equals(canonicalPlatform(platform))) return;
		long now = System.currentTimeMillis();
		douyinGlobalSoftBlockStartedAtMs.accumulateAndGet(now, Math::max);
		logger.warn("platform cooldown platform={} scope=DETAIL_API reason={} cooldownMs={}", DOUYIN_PLATFORM_KEY,
				reason, DOUYIN_SOFT_BLOCK_COOLDOWN_MS);
	}

	/** @deprecated use {@link #reportDetailSoftBlock(String, String)} for detail endpoint evidence. */
	@Deprecated
	public void reportSoftBlock(String platform, String reason) {
		reportDetailSoftBlock(platform, reason);
	}

	public boolean hasRecentSuccess(String platform, String cookie, Duration maxAge) {
		if (isBlank(platform) || isBlank(cookie) || maxAge == null || maxAge.isNegative()) return false;
		Long timestamp = successAt.get(riskKey(canonicalPlatform(platform), cookie));
		return timestamp != null && System.currentTimeMillis() - timestamp <= maxAge.toMillis();
	}

	public boolean isRiskSignal(String text) {
		if (text == null) {
			return false;
		}
		String lower = text.toLowerCase();
		if (lower.contains("429") || lower.contains("too many requests")
				|| lower.contains("rate limit") || lower.contains("ratelimit")) {
			return true;
		}
		return lower.contains("risk") || lower.contains("verify") || lower.contains("login")
				|| lower.contains("403") || lower.contains("401") || text.contains("验证码") || text.contains("风控");
	}

	public Map<String, Object> cookieStatus(String platform) {
		String safePlatform = canonicalPlatform(platform);
		Map<String, Object> status = new HashMap<>();
		long now = System.currentTimeMillis();
		if (DOUYIN_PLATFORM_KEY.equals(safePlatform)) {
			long remainingMs = douyinGlobalRiskCooldownRemainingMillis(now);
			long detailRemainingMs = douyinDetailSoftBackoffRemainingMillis(now);
			status.put("cooling", remainingMs > 0 ? 1 : 0);
			status.put("cooldownMinutes", douyinRiskCooldownMinutes());
			status.put("remainingMs", remainingMs);
			status.put("detailCooling", detailRemainingMs > 0 ? 1 : 0);
			status.put("detailRemainingMs", detailRemainingMs);
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
		return isDouyinGlobalRiskCooldownActive();
	}

	public long douyinGlobalCooldownRemainingMillis() {
		return douyinGlobalRiskCooldownRemainingMillis();
	}

	public Instant douyinGlobalCooldownRetryAt(Duration safetyBuffer) {
		return douyinGlobalRiskCooldownRetryAt(safetyBuffer);
	}

	public boolean isDouyinGlobalRiskCooldownActive() {
		return douyinGlobalRiskCooldownRemainingMillis() > 0;
	}

	public long douyinGlobalRiskCooldownRemainingMillis() {
		return douyinGlobalRiskCooldownRemainingMillis(System.currentTimeMillis());
	}

	public Instant douyinGlobalRiskCooldownRetryAt(Duration safetyBuffer) {
		long now = System.currentTimeMillis();
		long deadline = Math.max(now, douyinGlobalRiskCooldownUntilEpochMillis());
		long bufferMs = safetyBuffer == null ? 0 : Math.max(0, safetyBuffer.toMillis());
		return Instant.ofEpochMilli(deadline).plusMillis(bufferMs);
	}

	public boolean isDouyinDetailSoftBackoffActive() {
		return douyinDetailSoftBackoffRemainingMillis() > 0;
	}

	public long douyinDetailSoftBackoffRemainingMillis() {
		return douyinDetailSoftBackoffRemainingMillis(System.currentTimeMillis());
	}

	public Instant douyinDetailSoftBackoffRetryAt(Duration safetyBuffer) {
		long now = System.currentTimeMillis();
		long deadline = Math.max(now, douyinDetailSoftBackoffUntilEpochMillis());
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

	private long douyinGlobalRiskCooldownRemainingMillis(long now) {
		return Math.max(0, douyinGlobalRiskCooldownUntilEpochMillis() - now);
	}

	/** Clears only the in-memory global Douyin cooldown; configured cookies are unchanged. */
	public Map<String, Object> clearDouyinGlobalCooldown(String operator) {
		long previousRisk = douyinGlobalRiskStartedAtMs.getAndSet(0);
		long previousSoft = douyinGlobalSoftBlockStartedAtMs.getAndSet(0);
		logger.warn("platform cooldown manually cleared platform={} operator={} hadRisk={} hadDetailBackoff={}",
				DOUYIN_PLATFORM_KEY, operator == null || operator.isBlank() ? "unknown" : operator, previousRisk > 0,
				previousSoft > 0);
		return Map.of("cleared", true, "hadGlobalRiskCooldown", previousRisk > 0,
				"hadDetailBackoff", previousSoft > 0);
	}

	private long douyinGlobalRiskCooldownUntilEpochMillis() {
		long strongStartedAt = douyinGlobalRiskStartedAtMs.get();
		return strongStartedAt <= 0 ? 0 : strongStartedAt + douyinRiskCooldownMillis();
	}

	private long douyinDetailSoftBackoffRemainingMillis(long now) {
		return Math.max(0, douyinDetailSoftBackoffUntilEpochMillis() - now);
	}

	private long douyinDetailSoftBackoffUntilEpochMillis() {
		long softStartedAt = douyinGlobalSoftBlockStartedAtMs.get();
		return softStartedAt <= 0 ? 0 : softStartedAt + DOUYIN_SOFT_BLOCK_COOLDOWN_MS;
	}

	private long douyinRiskCooldownMillis() {
		return Duration.ofMinutes(douyinRiskCooldownMinutes()).toMillis();
	}

	private String confirmedDouyinRiskEvidence(String reason) {
		String normalized = reason == null ? "" : reason.toLowerCase();
		if (normalized.contains("f2_cookie_or_verify_required")) {
			return "F2_COOKIE_OR_VERIFY_REQUIRED";
		}
		if (normalized.contains("f2_upstream_rate_limit")) {
			return "F2_UPSTREAM_RATE_LIMIT";
		}
		if (containsHttpStatusEvidence(normalized, "401")) return "HTTP_STATUS_401";
		if (containsHttpStatusEvidence(normalized, "403")) return "HTTP_STATUS_403";
		if (containsHttpStatusEvidence(normalized, "429")) return "HTTP_STATUS_429";
		if (normalized.contains("confidence\":\"confirmed")
				&& normalized.contains("structured_")
				&& normalized.contains("_required")) {
			return "STRUCTURED_AUTH_OR_VERIFY_REQUIRED";
		}
		return null;
	}

	private boolean containsHttpStatusEvidence(String normalized, String status) {
		return normalized.contains("http " + status)
				|| normalized.contains("status=" + status)
				|| normalized.contains("statuscode\": " + status)
				|| normalized.contains("statuscode\":" + status)
				|| normalized.contains("status_code\": " + status)
				|| normalized.contains("status_code\":" + status);
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
