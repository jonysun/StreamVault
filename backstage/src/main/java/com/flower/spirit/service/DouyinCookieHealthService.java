package com.flower.spirit.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONArray;
import com.flower.spirit.config.Global;
import com.flower.spirit.entity.CollectDataEntity;
import com.flower.spirit.entity.TikTokConfigEntity;
import com.flower.spirit.utils.CommandUtil;
import com.flower.spirit.utils.sendNotify;

@Service
public class DouyinCookieHealthService {

	private static final long NOTIFY_COOLDOWN_MS = 6 * 60 * 60 * 1000L;
	private static final int COLLECT_DEGRADED_MIN_EXPECTED = 20;
	private static final int COLLECT_DEGRADED_LOW_COUNT = 8;

	@Autowired
	private TikTokConfigService tikTokConfigService;

	@Autowired(required = false)
	private PlatformCookieService platformCookieService;

	private final Map<String, Long> lastNotifyAt = new ConcurrentHashMap<>();

	public Map<String, Object> checkDouyinCookies(boolean notify) {
		TikTokConfigEntity config = tikTokConfigService == null ? null : tikTokConfigService.getData();
		String pool = config == null ? null : firstNotBlank(config.getCookiepool(), config.getCookies(), Global.tiktokCookie);
		List<String> cookies = parseCookieLines(pool);
		List<Map<String, Object>> items = new ArrayList<>();
		int valid = 0;
		int degraded = 0;
		int invalid = 0;
		for (int i = 0; i < cookies.size(); i++) {
			Map<String, Object> item = checkOne(cookies.get(i), i + 1);
			items.add(item);
			String status = stringValue(item.get("status"));
			if ("VALID".equals(status)) {
				valid++;
			} else if ("DEGRADED".equals(status)) {
				degraded++;
			} else {
				invalid++;
			}
			if (notify && shouldNotify(status)) {
				notifyCookieProblem(item);
			}
		}
		Map<String, Object> result = new HashMap<>();
		result.put("total", cookies.size());
		result.put("valid", valid);
		result.put("degraded", degraded);
		result.put("invalid", invalid);
		result.put("items", items);
		return result;
	}

	public void reportCollectFetchWindow(CollectDataEntity entity, String mode, String cookie, int requested, int fetched,
			long existingDetailCount) {
		if (isBlank(cookie) || requested < COLLECT_DEGRADED_MIN_EXPECTED || existingDetailCount < COLLECT_DEGRADED_MIN_EXPECTED) {
			return;
		}
		if (fetched > COLLECT_DEGRADED_LOW_COUNT) {
			return;
		}
		String taskName = entity == null ? "" : entity.getTaskname();
		String taskId = entity == null || entity.getId() == null ? "" : String.valueOf(entity.getId());
		Map<String, Object> item = baseItem(cookie, 0);
		item.put("status", "DEGRADED");
		item.put("statusText", "疑似非登录降级");
		item.put("message", "收藏任务返回作品数异常少，可能是 cookie 过期后进入非登录状态");
		item.put("taskId", taskId);
		item.put("taskName", taskName);
		item.put("mode", mode);
		item.put("requested", requested);
		item.put("fetched", fetched);
		item.put("existingDetailCount", existingDetailCount);
		if (platformCookieService != null) {
			platformCookieService.reportRisk("抖音", cookie, "douyin cookie degraded fetch window");
		}
		notifyCookieProblem(item);
	}

	private Map<String, Object> checkOne(String cookie, int index) {
		Map<String, Object> item = baseItem(cookie, index);
		List<String> missing = missingRequiredCookieNames(cookie);
		item.put("missing", missing);
		item.put("hasSession", containsIgnoreCase(cookie, "sessionid") || containsIgnoreCase(cookie, "sessionid_ss"));
		item.put("hasOdinTt", containsIgnoreCase(cookie, "odin_tt"));
		item.put("hasSidGuard", containsIgnoreCase(cookie, "sid_guard"));
		item.put("hasTtwid", containsIgnoreCase(cookie, "ttwid"));
		item.put("hasPassportCsrf", containsIgnoreCase(cookie, "passport_csrf_token"));
		if (!missing.isEmpty()) {
			item.put("status", "INCOMPLETE");
			item.put("statusText", "不完整");
			item.put("message", "缺少关键字段: " + String.join(", ", missing));
			return item;
		}
		String output = CommandUtil.f2cmd(cookie, null, "fetch_user_collects", null, null, null, null);
		item.put("exitCode", CommandUtil.getLastF2ExitCode());
		item.put("durationMs", CommandUtil.getLastF2DurationMs());
		item.put("outputPreview", preview(output, 500));
		if (isExpiredSignal(output)) {
			item.put("status", "EXPIRED");
			item.put("statusText", "疑似过期");
			item.put("message", "登录态探针返回登录/验证/风控信号");
			if (platformCookieService != null) {
				platformCookieService.reportRisk("抖音", cookie, "douyin cookie login probe expired");
			}
			return item;
		}
		JSONArray collects = parseCollects(output);
		if (collects != null) {
			item.put("collectCount", collects.size());
			if (collects.size() > 0) {
				item.put("status", "VALID");
				item.put("statusText", "有效");
				item.put("message", "登录态探针正常");
				if (platformCookieService != null) {
					platformCookieService.reportSuccess("抖音", cookie);
				}
			} else {
				item.put("status", "DEGRADED");
				item.put("statusText", "疑似降级");
				item.put("message", "登录态探针成功但收藏夹为空；若账号实际有收藏夹，通常是非登录/降级状态");
			}
			return item;
		}
		item.put("status", "ERROR");
		item.put("statusText", "检测失败");
		item.put("message", "无法解析登录态探针输出");
		return item;
	}

	private Map<String, Object> baseItem(String cookie, int index) {
		Map<String, Object> item = new HashMap<>();
		item.put("index", index);
		item.put("fingerprint", fingerprint(cookie));
		item.put("length", cookie == null ? 0 : cookie.length());
		return item;
	}

	private JSONArray parseCollects(String output) {
		if (output == null) {
			return null;
		}
		String startTag = "stream-vault-start-collects";
		String endTag = "stream-vault-end-collects";
		int start = output.indexOf(startTag);
		int end = output.indexOf(endTag);
		if (start < 0 || end <= start) {
			return null;
		}
		String content = output.substring(start + startTag.length(), end).trim();
		try {
			return JSONArray.parseArray(content);
		} catch (Exception e) {
			return null;
		}
	}

	private void notifyCookieProblem(Map<String, Object> item) {
		String status = stringValue(item.get("status"));
		String fingerprint = stringValue(item.get("fingerprint"));
		String key = status + ":" + fingerprint;
		long now = System.currentTimeMillis();
		Long previous = lastNotifyAt.get(key);
		if (previous != null && now - previous < NOTIFY_COOLDOWN_MS) {
			return;
		}
		lastNotifyAt.put(key, now);
		String message = "抖音 cookie " + stringValue(item.get("statusText")) + "\n"
				+ "标识: " + fingerprint + "\n"
				+ "说明: " + stringValue(item.get("message"));
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
		if (text == null) {
			return true;
		}
		String lower = text.toLowerCase();
		return lower.contains("login") || lower.contains("verify") || lower.contains("risk")
				|| lower.contains("401") || lower.contains("403") || text.contains("登录")
				|| text.contains("验证") || text.contains("风控");
	}

	private List<String> missingRequiredCookieNames(String cookie) {
		List<String> missing = new ArrayList<>();
		if (!containsIgnoreCase(cookie, "odin_tt")) {
			missing.add("odin_tt");
		}
		if (!(containsIgnoreCase(cookie, "sessionid") || containsIgnoreCase(cookie, "sessionid_ss"))) {
			missing.add("sessionid/sessionid_ss");
		}
		if (!containsIgnoreCase(cookie, "ttwid")) {
			missing.add("ttwid");
		}
		if (!containsIgnoreCase(cookie, "passport_csrf_token")) {
			missing.add("passport_csrf_token");
		}
		return missing;
	}

	private List<String> parseCookieLines(String pool) {
		List<String> cookies = new ArrayList<>();
		if (pool == null) {
			return cookies;
		}
		String[] lines = pool.split("\\r?\\n");
		for (String line : lines) {
			if (!isBlank(line)) {
				cookies.add(line.trim());
			}
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
		if (text == null) {
			return "";
		}
		String normalized = text.replace("\r", "\\r").replace("\n", "\\n");
		return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
	}

	private String fingerprint(String cookie) {
		if (cookie == null) {
			return "empty";
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(cookie.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 6 && i < hash.length; i++) {
				sb.append(String.format("%02x", hash[i] & 0xff));
			}
			return sb.toString();
		} catch (Exception e) {
			return String.valueOf(cookie.hashCode());
		}
	}

	private String stringValue(Object value) {
		return value == null ? "" : String.valueOf(value);
	}
}
