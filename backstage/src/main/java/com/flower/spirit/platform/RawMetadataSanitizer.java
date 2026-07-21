package com.flower.spirit.platform;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

public final class RawMetadataSanitizer {

	private static final Pattern SENSITIVE_QUERY = Pattern.compile(
			"(?i)([?&](?:token|access_token|refresh_token|id_token|api_?key|auth|authorization|auth_key|"
					+ "cookie|signature|sig|secret|wssecret|wstime|txsecret|key|expires?|expiry|policy|"
					+ "key-pair-id|hdnts?|x-amz-[^=&#]+|x-goog-[^=&#]+)=)[^&#]*");

	private RawMetadataSanitizer() {
	}

	public static String sanitize(String raw) {
		if (raw == null || raw.trim().isEmpty()) return null;
		try {
			Object value = JSON.parse(raw);
			return JSON.toJSONString(sanitizeValue(value));
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private static Object sanitizeValue(Object value) {
		if (value instanceof JSONObject object) {
			for (String key : new ArrayList<>(object.keySet())) {
				if (sensitiveKey(key)) object.remove(key);
				else object.put(key, sanitizeValue(object.get(key)));
			}
			return object;
		}
		if (value instanceof JSONArray array) {
			for (int i = 0; i < array.size(); i++) array.set(i, sanitizeValue(array.get(i)));
			return array;
		}
		if (value instanceof String text) {
			return SENSITIVE_QUERY.matcher(text).replaceAll("$1[redacted]");
		}
		return value;
	}

	private static boolean sensitiveKey(String key) {
		if (key == null) return false;
		String value = key.toLowerCase(Locale.ROOT).replace('-', '_');
		return value.contains("cookie") || value.equals("authorization")
				|| value.equals("proxy_authorization") || value.equals("http_headers")
				|| value.equals("request_headers") || value.equals("headers") || value.endsWith("_headers")
				|| value.equals("token") || value.endsWith("_token") || value.equals("api_key")
				|| value.equals("signature") || value.equals("secret") || value.endsWith("_secret")
				|| value.startsWith("x_amz_") || value.startsWith("x_goog_");
	}
}
