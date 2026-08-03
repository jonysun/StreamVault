package com.flower.spirit.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.config.Global;
import com.flower.spirit.entity.CollectDataEntity;
import com.flower.spirit.entity.TikTokConfigEntity;
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

	private final TikTokConfigService tikTokConfigService;
	private final PlatformCookieService platformCookieService;
	private final ProbeRunner probeRunner;
	private final Map<String, Long> lastNotifyAt = new ConcurrentHashMap<>();

	@Autowired
	public DouyinCookieHealthService(TikTokConfigService tikTokConfigService,
			PlatformCookieService platformCookieService) {
		this(tikTokConfigService, platformCookieService, cookie -> {
			String output = CommandUtil.f2cmd(cookie, null, "fetch_user_collects", null, null, null, null);
			return new ProbeExecution(output, CommandUtil.getLastF2ExitCode(), CommandUtil.getLastF2DurationMs());
		});
	}

	DouyinCookieHealthService(TikTokConfigService tikTokConfigService, PlatformCookieService platformCookieService,
			ProbeRunner probeRunner) {
		this.tikTokConfigService = tikTokConfigService;
		this.platformCookieService = platformCookieService;
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
			Map<String, Object> item = checkOne(cookies.get(i), i + 1);
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

	private Map<String, Object> checkOne(String cookie, int index) {
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

		ProbeExecution execution = probeRunner.run(cookie);
		String output = execution == null ? null : execution.output();
		item.put("exitCode", execution == null ? null : execution.exitCode());
		item.put("durationMs", execution == null ? null : execution.durationMs());
		item.put("outputPreview", preview(output, 500));
		JSONObject probe = parseProbe(output);
		if (probe != null) return applyProbe(item, cookie, probe);

		if (isExpiredSignal(output)) {
			if (platformCookieService != null) {
				platformCookieService.reportRisk("douyin", cookie, "douyin cookie login probe expired");
			}
			return status(item, "EXPIRED", "疑似过期", "登录状态探针返回明确的登录、验证或风控信号", "LEGACY_PROBE");
		}
		JSONArray collects = parseCollects(output);
		if (collects != null) {
			item.put("collectCount", collects.size());
			if (platformCookieService != null) platformCookieService.reportSuccess("douyin", cookie);
			return status(item, "VALID", "有效", "登录状态探针请求成功", "LEGACY_PROBE");
		}
		return status(item, "INDETERMINATE", "无法确认", "探针未返回可识别结果，未判定 Cookie 失效", "PROBE");
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
		if ("VALID".equals(probeStatus)) {
			if (platformCookieService != null) platformCookieService.reportSuccess("douyin", cookie);
			return status(item, "VALID", "有效", "登录状态探针请求成功", "PROBE");
		}
		if ("EXPIRED".equals(probeStatus)) {
			if (platformCookieService != null) {
				platformCookieService.reportRisk("douyin", cookie,
						"douyin cookie probe " + errorCategory.toLowerCase());
			}
			return status(item, "EXPIRED", "疑似过期", "探针返回明确的登录失效或风控信号", "PROBE");
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
		return lower.contains("login") || lower.contains("verify") || lower.contains("risk")
				|| lower.contains("401") || lower.contains("403") || text.contains("登录")
				|| text.contains("验证") || text.contains("风控");
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
		ProbeExecution run(String cookie);
	}

	record ProbeExecution(String output, Integer exitCode, Long durationMs) {
	}
}
