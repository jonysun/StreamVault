package com.flower.spirit.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.CollectdDataDao;
import com.flower.spirit.entity.CollectDataEntity;
import com.flower.spirit.entity.TikTokConfigEntity;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.utils.CommandUtil;
import com.flower.spirit.utils.sendNotify;

@Service
public class DouyinCookieHealthService {

	private static final long NOTIFY_COOLDOWN_MS = 6 * 60 * 60 * 1000L;
	private static final Duration RECENT_SUCCESS_MAX_AGE = Duration.ofMinutes(15);
	private static final int COLLECT_DEGRADED_MIN_EXPECTED = 20;
	private static final int COLLECT_DEGRADED_LOW_COUNT = 8;
	private static final String PROBE_START = "stream-vault-start-cookie-probe";
	private static final String PROBE_END = "stream-vault-end-cookie-probe";
	private static final String AUTHOR_PROBE_START = "stream-vault-start-author-probe";
	private static final String AUTHOR_PROBE_END = "stream-vault-end-author-probe";

	private final TikTokConfigService tikTokConfigService;
	private final PlatformCookieService platformCookieService;
	private final CollectdDataDao collectdDataDao;
	private final ProbeRunner probeRunner;
	private final Map<String, Long> lastNotifyAt = new ConcurrentHashMap<>();

	@Autowired
	public DouyinCookieHealthService(TikTokConfigService tikTokConfigService,
			PlatformCookieService platformCookieService, CollectdDataDao collectdDataDao) {
		this(tikTokConfigService, platformCookieService, collectdDataDao, (cookie, secUserId) -> {
			String output = CommandUtil.f2cmd(cookie, null, "probe_author_list", secUserId, null, null, null);
			return new ProbeExecution(output, CommandUtil.getLastF2ExitCode(), CommandUtil.getLastF2DurationMs());
		});
	}

	DouyinCookieHealthService(TikTokConfigService tikTokConfigService, PlatformCookieService platformCookieService,
			ProbeRunner probeRunner) {
		this(tikTokConfigService, platformCookieService, null, probeRunner);
	}

	DouyinCookieHealthService(TikTokConfigService tikTokConfigService, PlatformCookieService platformCookieService,
			CollectdDataDao collectdDataDao, ProbeRunner probeRunner) {
		this.tikTokConfigService = tikTokConfigService;
		this.platformCookieService = platformCookieService;
		this.collectdDataDao = collectdDataDao;
		this.probeRunner = probeRunner;
	}

	public Map<String, Object> checkDouyinCookies(boolean notify) {
		TikTokConfigEntity config = tikTokConfigService == null ? null : tikTokConfigService.getData();
		String pool = config == null ? null
				: firstNotBlank(config.getCookiepool(), config.getCookies(), Global.tiktokCookie);
		List<String> cookies = parseCookieLines(pool);
		List<Map<String, Object>> items = new ArrayList<>();
		int valid = 0;
		int degraded = 0;
		int indeterminate = 0;
		int invalid = 0;
		int cooling = 0;
		for (int i = 0; i < cookies.size(); i++) {
			Map<String, Object> item = checkOne(cookies.get(i), i + 1, config);
			items.add(item);
			String status = stringValue(item.get("status"));
			switch (status) {
			case "VALID" -> valid++;
			case "DEGRADED" -> degraded++;
			case "INDETERMINATE" -> indeterminate++;
			case "COOLDOWN" -> cooling++;
			default -> invalid++;
			}
			if (notify && shouldNotify(status)) notifyCookieProblem(item);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("total", cookies.size());
		result.put("valid", valid);
		result.put("degraded", degraded);
		result.put("indeterminate", indeterminate);
		result.put("invalid", invalid);
		result.put("cooling", cooling);
		result.put("items", items);
		return result;
	}

	public void reportCollectFetchWindow(CollectDataEntity entity, String mode, String cookie, int requested,
			int fetched, long existingDetailCount) {
		if (isBlank(cookie) || requested < COLLECT_DEGRADED_MIN_EXPECTED
				|| existingDetailCount < COLLECT_DEGRADED_MIN_EXPECTED || fetched > COLLECT_DEGRADED_LOW_COUNT) return;
		Map<String, Object> item = baseItem(cookie, 0);
		item.put("status", "DEGRADED");
		item.put("statusText", "疑似登录状态降级");
		item.put("message", "收藏任务返回作品数异常偏少，可能进入了非登录或降级状态");
		item.put("taskId", entity == null || entity.getId() == null ? "" : String.valueOf(entity.getId()));
		item.put("taskName", entity == null ? "" : entity.getTaskname());
		item.put("mode", mode);
		item.put("requested", requested);
		item.put("fetched", fetched);
		item.put("existingDetailCount", existingDetailCount);
		if (platformCookieService != null) {
			platformCookieService.reportRisk("douyin", cookie, "douyin cookie degraded fetch window");
		}
		notifyCookieProblem(item);
	}

	private Map<String, Object> checkOne(String cookie, int index, TikTokConfigEntity config) {
		Map<String, Object> item = baseItem(cookie, index);
		List<String> missing = missingRequiredCookieNames(cookie);
		item.put("missing", missing);
		item.put("hasSession", containsIgnoreCase(cookie, "sessionid")
				|| containsIgnoreCase(cookie, "sessionid_ss"));
		item.put("hasOdinTt", containsIgnoreCase(cookie, "odin_tt"));
		item.put("hasSidGuard", containsIgnoreCase(cookie, "sid_guard"));
		item.put("hasTtwid", containsIgnoreCase(cookie, "ttwid"));
		item.put("hasPassportCsrf", containsIgnoreCase(cookie, "passport_csrf_token"));
		if (!missing.isEmpty()) {
			return status(item, "INCOMPLETE", "不完整", "缺少关键字段: " + String.join(", ", missing),
					"STATIC_VALIDATION");
		}
		if (platformCookieService != null && platformCookieService.isDouyinGlobalCooldownActive()) {
			item.put("remainingMs", platformCookieService.douyinGlobalCooldownRemainingMillis());
			return status(item, "COOLDOWN", "全局冷却中", "当前处于抖音全局风控冷却期，未发送检测请求", "COOLDOWN");
		}
		if (platformCookieService != null
				&& platformCookieService.hasRecentSuccess("douyin", cookie, RECENT_SUCCESS_MAX_AGE)) {
			return status(item, "VALID", "有效", "最近的真实抓取或下载请求已成功", "RECENT_SUCCESS");
		}

		List<String> probeAuthors = probeAuthors(config);
		item.put("probeAuthors", probeAuthors);
		if (probeAuthors.isEmpty()) {
			return status(item, "INDETERMINATE", "无法确认", "没有可用的作者列表探针作者，未判定 Cookie 失效", "PROBE_CONFIGURATION");
		}

		List<Map<String, Object>> attempts = new ArrayList<>();
		JSONObject firstIndeterminate = null;
		boolean authorUnavailable = true;
		for (String secUserId : probeAuthors) {
			ProbeExecution execution = probeRunner.run(cookie, secUserId);
			String output = execution == null ? null : execution.output();
			item.put("exitCode", execution == null ? null : execution.exitCode());
			item.put("durationMs", execution == null ? null : execution.durationMs());
			item.put("outputPreview", preview(output, 500));
			JSONObject probe = parseAuthorProbe(output);
			if (probe == null) {
				if (isExpiredSignal(output)) {
					probe = new JSONObject();
					probe.put("probeStatus", "EXPIRED");
					probe.put("errorCategory", "AUTHENTICATION");
				} else {
					probe = new JSONObject();
					probe.put("probeStatus", "INDETERMINATE");
					probe.put("errorCategory", "UPSTREAM_ERROR");
				}
			}
			probe.put("secUserId", secUserId);
			attempts.add(new LinkedHashMap<>(probe));
			String probeStatus = stringValue(probe.get("probeStatus")).toUpperCase();
			if ("VALID".equals(probeStatus)) {
				item.put("probeAttempts", attempts);
				return applyProbe(item, cookie, probe);
			}
			if ("EXPIRED".equals(probeStatus)) {
				item.put("probeAttempts", attempts);
				return applyProbe(item, cookie, probe);
			}
			if (!"AUTHOR_UNAVAILABLE".equals(probeStatus)) {
				authorUnavailable = false;
				if (firstIndeterminate == null) firstIndeterminate = probe;
			}
		}
		item.put("probeAttempts", attempts);
		if (firstIndeterminate != null) return applyProbe(item, cookie, firstIndeterminate);
		if (authorUnavailable) {
			return status(item, "INDETERMINATE", "无法确认", "探针作者均不可用，未判定 Cookie 失效", "AUTHOR_PROBE");
		}
		return status(item, "INDETERMINATE", "无法确认", "作者列表探针未返回可识别结果，未判定 Cookie 失效", "AUTHOR_PROBE");
	}

	private Map<String, Object> applyProbe(Map<String, Object> item, String cookie, JSONObject probe) {
		String probeStatus = stringValue(probe.get("probeStatus")).toUpperCase();
		String listState = stringValue(probe.get("listState"));
		String errorCategory = stringValue(probe.get("errorCategory"));
		item.put("probeStatus", probeStatus);
		item.put("upstreamStatus", stringValue(probe.get("upstreamStatus")));
		item.put("listState", listState);
		item.put("errorCategory", errorCategory);
		item.put("collectCount", numberValue(probe.get("collectCount")));
		if (probe.containsKey("errorCode")) item.put("errorCode", stringValue(probe.get("errorCode")));
		if (probe.containsKey("message")) item.put("probeMessage", stringValue(probe.get("message")));
		if (probe.containsKey("diagnostics")) item.put("diagnostics", probe.get("diagnostics"));
		if (probe.containsKey("secUserId")) item.put("probeSecUserId", stringValue(probe.get("secUserId")));
		if (probe.containsKey("profileStatus")) item.put("profileStatus", probe.get("profileStatus"));
		if (probe.containsKey("pageStatus")) item.put("pageStatus", probe.get("pageStatus"));
		if ("VALID".equals(probeStatus)) {
			if (platformCookieService != null) platformCookieService.reportSuccess("douyin", cookie);
			return status(item, "VALID", "有效", "作者 profile 和作品列表请求成功", "AUTHOR_PROBE");
		}
		if ("EXPIRED".equals(probeStatus)) {
			if (platformCookieService != null) {
				platformCookieService.reportRisk("douyin", cookie,
						"douyin cookie probe " + errorCategory.toLowerCase());
			}
			return status(item, "EXPIRED", "疑似过期", "探针返回明确的登录失效信号", "AUTHOR_PROBE");
		}
		if ("AUTHOR_UNAVAILABLE".equals(probeStatus)) {
			return status(item, "INDETERMINATE", "无法确认", "探针作者不存在或不可见，未判定 Cookie 失效", "AUTHOR_PROBE");
		}
		if ("F2_UPSTREAM_SOFT_BLOCK".equals(stringValue(probe.get("errorCode")))
				&& platformCookieService != null) {
			platformCookieService.reportRisk("douyin", cookie, "F2_UPSTREAM_SOFT_BLOCK");
		}
		return status(item, "INDETERMINATE", "无法确认",
				"探针返回 " + valueOr(errorCategory, "UNKNOWN") + "，未判定 Cookie 失效", "PROBE");
	}

	private Map<String, Object> status(Map<String, Object> item, String value, String text, String message,
			String evidence) {
		item.put("status", value);
		item.put("statusText", text);
		item.put("message", message);
		item.put("evidence", evidence);
		return item;
	}

	private Map<String, Object> baseItem(String cookie, int index) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("index", index);
		item.put("fingerprint", fingerprint(cookie));
		item.put("length", cookie == null ? 0 : cookie.length());
		return item;
	}

	private JSONObject parseProbe(String output) {
		String content = markerContent(output, PROBE_START, PROBE_END);
		if (content == null) return null;
		try {
			return JSONObject.parseObject(content);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private JSONObject parseAuthorProbe(String output) {
		String content = markerContent(output, AUTHOR_PROBE_START, AUTHOR_PROBE_END);
		if (content == null) return parseProbe(output);
		try {
			return JSONObject.parseObject(content);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private List<String> probeAuthors(TikTokConfigEntity config) {
		Map<String, Boolean> candidates = new LinkedHashMap<>();
		String configured = config == null ? null : config.getDouyinProbeSecUserId();
		if (isBlank(configured)) configured = TikTokConfigService.DEFAULT_DOUYIN_PROBE_SEC_USER_ID;
		if (!isBlank(configured)) candidates.put(configured.trim(), Boolean.TRUE);
		List<String> fallback = new ArrayList<>();
		if (collectdDataDao != null) {
			for (CollectDataEntity task : collectdDataDao.findAll()) {
				if (task == null || "N".equalsIgnoreCase(task.getTaskenabled())) continue;
				if (!"douyin".equals(PlatformCatalog.canonicalKey(null, task.getPlatform()))) continue;
				String secUserId = authorSecUserId(task.getOriginaladdress());
				if (!isBlank(secUserId) && !candidates.containsKey(secUserId)) fallback.add(secUserId);
			}
		}
		Collections.shuffle(fallback);
		for (String secUserId : fallback) {
			if (candidates.size() >= 4) break;
			candidates.put(secUserId, Boolean.TRUE);
		}
		return new ArrayList<>(candidates.keySet());
	}

	private String authorSecUserId(String originalAddress) {
		if (isBlank(originalAddress)) return null;
		String value = originalAddress.trim();
		for (String prefix : List.of("post", "like", "recommend")) {
			if (value.startsWith(prefix) && value.length() > prefix.length()) {
				return value.substring(prefix.length()).trim();
			}
		}
		return null;
	}

	private JSONArray parseCollects(String output) {
		String content = markerContent(output, "stream-vault-start-collects", "stream-vault-end-collects");
		if (content == null) return null;
		try {
			return JSONArray.parseArray(content);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private String markerContent(String output, String startTag, String endTag) {
		if (output == null) return null;
		int start = output.indexOf(startTag);
		int end = output.indexOf(endTag, start < 0 ? 0 : start + startTag.length());
		return start < 0 || end <= start ? null : output.substring(start + startTag.length(), end).trim();
	}

	private void notifyCookieProblem(Map<String, Object> item) {
		String status = stringValue(item.get("status"));
		String fingerprint = stringValue(item.get("fingerprint"));
		String key = status + ":" + fingerprint;
		long now = System.currentTimeMillis();
		Long previous = lastNotifyAt.get(key);
		if (previous != null && now - previous < NOTIFY_COOLDOWN_MS) return;
		lastNotifyAt.put(key, now);
		String message = "抖音 Cookie " + stringValue(item.get("statusText")) + "\n"
				+ "标识: " + fingerprint + "\n说明: " + stringValue(item.get("message"));
		if (item.get("taskName") != null) {
			message += "\n任务: " + stringValue(item.get("taskName"))
					+ "\n请求/返回: " + item.get("requested") + "/" + item.get("fetched");
		}
		sendNotify.sendMessage("StreamVault 抖音 Cookie 提醒", message);
	}

	private boolean shouldNotify(String status) {
		return "EXPIRED".equals(status) || "INCOMPLETE".equals(status) || "DEGRADED".equals(status);
	}

	private boolean isExpiredSignal(String text) {
		if (text == null) return false;
		String lower = text.toLowerCase();
		return lower.contains("login") || lower.contains("unauthorized") || lower.contains("401")
				|| text.contains("登录");
	}

	private List<String> missingRequiredCookieNames(String cookie) {
		List<String> missing = new ArrayList<>();
		if (!containsIgnoreCase(cookie, "odin_tt")) missing.add("odin_tt");
		if (!(containsIgnoreCase(cookie, "sessionid") || containsIgnoreCase(cookie, "sessionid_ss"))) {
			missing.add("sessionid/sessionid_ss");
		}
		if (!containsIgnoreCase(cookie, "ttwid")) missing.add("ttwid");
		if (!containsIgnoreCase(cookie, "passport_csrf_token")) missing.add("passport_csrf_token");
		return missing;
	}

	private List<String> parseCookieLines(String pool) {
		List<String> cookies = new ArrayList<>();
		if (pool == null) return cookies;
		for (String line : pool.split("\\r?\\n")) {
			if (!isBlank(line)) cookies.add(line.trim());
		}
		return cookies;
	}

	private String firstNotBlank(String first, String second, String third) {
		if (!isBlank(first)) return first.trim();
		if (!isBlank(second)) return second.trim();
		return isBlank(third) ? "" : third.trim();
	}

	private boolean containsIgnoreCase(String text, String needle) {
		return text != null && needle != null && text.toLowerCase().contains(needle.toLowerCase());
	}

	private boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private String preview(String text, int maxLength) {
		if (text == null) return "";
		String normalized = CommandUtil.sanitizeF2Output(text).replace("\r", "\\r").replace("\n", "\\n");
		return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
	}

	private String fingerprint(String cookie) {
		if (cookie == null) return "empty";
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(cookie.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 6 && i < hash.length; i++) sb.append(String.format("%02x", hash[i] & 0xff));
			return sb.toString();
		} catch (Exception e) {
			return String.valueOf(cookie.hashCode());
		}
	}

	private int numberValue(Object value) {
		return value instanceof Number number ? number.intValue() : 0;
	}

	private String valueOr(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private String stringValue(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	@FunctionalInterface
	interface ProbeRunner {
		ProbeExecution run(String cookie, String secUserId);
	}

	record ProbeExecution(String output, Integer exitCode, Long durationMs) {
	}
}
