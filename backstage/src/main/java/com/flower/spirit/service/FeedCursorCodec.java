package com.flower.spirit.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.dto.FeedCursor;

@Service
public class FeedCursorCodec {

	private final byte[] signingKey;

	public FeedCursorCodec(@Value("${streamvault.feed.cursor-secret:}") String configuredSecret) {
		this.signingKey = signingKey(configuredSecret);
	}

	public String encode(FeedCursor cursor) {
		if (cursor == null || cursor.sortTime() == null) throw new IllegalArgumentException("cursor is incomplete");
		JSONObject payload = new JSONObject(true);
		payload.put("sortTime", cursor.sortTime().toEpochMilli());
		payload.put("mediaType", cursor.mediaType());
		payload.put("internalId", cursor.internalId());
		payload.put("order", cursor.order());
		payload.put("filterHash", cursor.filterHash());
		String encoded = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(payload.toJSONString().getBytes(StandardCharsets.UTF_8));
		return encoded + "." + hmac(encoded);
	}

	public FeedCursor decode(String token) {
		if (token == null || token.isBlank()) return null;
		String[] parts = token.split("\\.", -1);
		if (parts.length != 2 || !MessageDigest.isEqual(hmac(parts[0]).getBytes(StandardCharsets.US_ASCII),
				parts[1].getBytes(StandardCharsets.US_ASCII))) {
			throw new IllegalArgumentException("feed cursor signature is invalid");
		}
		try {
			JSONObject payload = JSONObject.parseObject(new String(Base64.getUrlDecoder().decode(parts[0]),
					StandardCharsets.UTF_8));
			return new FeedCursor(Instant.ofEpochMilli(payload.getLongValue("sortTime")),
					payload.getString("mediaType"), payload.getIntValue("internalId"),
					payload.getString("order"), payload.getString("filterHash"));
		} catch (RuntimeException error) {
			throw new IllegalArgumentException("feed cursor payload is invalid", error);
		}
	}

	private String hmac(String value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
			return Base64.getUrlEncoder().withoutPadding()
					.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException("HmacSHA256 unavailable", error);
		}
	}

	private byte[] signingKey(String configuredSecret) {
		if (configuredSecret != null && !configuredSecret.isBlank()) {
			return configuredSecret.getBytes(StandardCharsets.UTF_8);
		}
		byte[] generated = new byte[32];
		new SecureRandom().nextBytes(generated);
		return generated;
	}
}
