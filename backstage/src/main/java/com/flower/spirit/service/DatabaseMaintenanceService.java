package com.flower.spirit.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.dto.DatabaseMaintenanceRequest;
import com.flower.spirit.service.transaction.DatabaseMaintenanceTransaction;
import com.flower.spirit.service.transaction.DatabaseMaintenanceTransaction.BatchResult;

@Service
public class DatabaseMaintenanceService {

	public static final String CLEAR_EXACT_DUPLICATE_VIDEOINFO = "CLEAR_EXACT_DUPLICATE_VIDEOINFO";
	public static final String PURGE_EXPIRED_RUN_ITEMS = "PURGE_EXPIRED_RUN_ITEMS";
	public static final String PURGE_EXPIRED_RUN_EVENTS = "PURGE_EXPIRED_RUN_EVENTS";
	public static final String PURGE_EXPIRED_TERMINAL_RUNS = "PURGE_EXPIRED_TERMINAL_RUNS";
	public static final String PURGE_EXPIRED_TERMINAL_JOBS = "PURGE_EXPIRED_TERMINAL_JOBS";
	private static final List<String> DEFAULT_OPERATIONS = List.of(
			CLEAR_EXACT_DUPLICATE_VIDEOINFO,
			PURGE_EXPIRED_RUN_ITEMS,
			PURGE_EXPIRED_RUN_EVENTS,
			PURGE_EXPIRED_TERMINAL_RUNS,
			PURGE_EXPIRED_TERMINAL_JOBS);
	private static final Set<String> ALLOWED_OPERATIONS = Set.of(
			CLEAR_EXACT_DUPLICATE_VIDEOINFO,
			PURGE_EXPIRED_RUN_ITEMS,
			PURGE_EXPIRED_RUN_EVENTS,
			PURGE_EXPIRED_TERMINAL_RUNS,
			PURGE_EXPIRED_TERMINAL_JOBS);

	private final DatabaseAuditService auditService;
	private final DatabaseMaintenanceTransaction transaction;
	private final RuntimeControlService runtimeControlService;
	private final boolean applyEnabled;
	private final long tokenTtlSeconds;
	private final byte[] signingKey;

	public DatabaseMaintenanceService(DatabaseAuditService auditService, DatabaseMaintenanceTransaction transaction,
			RuntimeControlService runtimeControlService,
			@Value("${streamvault.database-maintenance.apply-enabled:false}") boolean applyEnabled,
			@Value("${streamvault.database-maintenance.preview-token-ttl-seconds:1800}") long tokenTtlSeconds,
			@Value("${streamvault.database-maintenance.preview-secret:}") String previewSecret) {
		this.auditService = auditService;
		this.transaction = transaction;
		this.runtimeControlService = runtimeControlService;
		this.applyEnabled = applyEnabled;
		this.tokenTtlSeconds = Math.max(60, tokenTtlSeconds);
		this.signingKey = signingKey(previewSecret);
	}

	public Map<String, Object> preview(List<String> requestedOperations) {
		List<String> operations = normalizeOperations(requestedOperations);
		Map<String, Object> audit = auditService.audit();
		Map<String, Object> estimates = estimates(audit, operations);
		Instant now = Instant.now();
		JSONObject payload = new JSONObject(true);
		payload.put("fingerprint", audit.get("fingerprint"));
		payload.put("operations", operations);
		payload.put("estimates", estimates);
		payload.put("issuedAt", now.getEpochSecond());
		payload.put("expiresAt", now.plusSeconds(tokenTtlSeconds).getEpochSecond());
		String token = sign(payload.toJSONString());
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("previewToken", token);
		result.put("expiresAt", Instant.ofEpochSecond(payload.getLongValue("expiresAt")));
		result.put("applyEnabled", applyEnabled);
		result.put("requiresPauseAll", true);
		result.put("operations", estimates);
		result.put("differentRows", number(map(audit.get("video")).get("differentRows")));
		result.put("fingerprint", audit.get("fingerprint"));
		result.put("warning", "不会删除作品或媒体文件，不会执行 VACUUM；不同内容的 videoinfo 永远不会清理");
		return result;
	}

	public Map<String, Object> apply(DatabaseMaintenanceRequest request) {
		if (!applyEnabled) {
			throw new IllegalStateException("数据库维护 apply 默认关闭，请先设置 streamvault.database-maintenance.apply-enabled=true");
		}
		if (request == null || request.getPreviewToken() == null || request.getPreviewToken().isBlank()) {
			throw new IllegalArgumentException("previewToken 不能为空");
		}
		if (!runtimeControlService.snapshot().allPaused()) {
			throw new IllegalStateException("执行数据库维护前必须先暂停全部后台任务");
		}
		JSONObject token = verify(request.getPreviewToken());
		List<String> tokenOperations = normalizeOperations(token.getJSONArray("operations").toJavaList(String.class));
		List<String> requested = request.getOperations() == null || request.getOperations().isEmpty()
				? tokenOperations : normalizeOperations(request.getOperations());
		if (!tokenOperations.equals(requested)) {
			throw new IllegalArgumentException("operations 与 preview 不一致，请重新 preview");
		}
		int batchSize = Math.min(Math.max(request.getBatchSize() == null ? 500 : request.getBatchSize(), 1), 2000);
		String tokenHash = sha256(request.getPreviewToken());
		Map<String, Object> operation = transaction.findByTokenHash(tokenHash);
		if (operation.isEmpty()) {
			Map<String, Object> audit = auditService.audit();
			if (!String.valueOf(token.get("fingerprint")).equals(String.valueOf(audit.get("fingerprint")))) {
				throw new IllegalStateException("数据库自 preview 后已变化，请重新 preview");
			}
			long estimatedRows = requested.stream().mapToLong(item -> estimateRows(token, item)).sum();
			long operationId = transaction.create(tokenHash, String.valueOf(audit.get("fingerprint")),
					JSON.toJSONString(requested), estimatedRows, batchSize, requested.get(0), Instant.now());
			operation = transaction.find(operationId);
		}
		if ("COMPLETED".equals(operation.get("status")) || "FAILED".equals(operation.get("status"))) return operation;
		long operationId = number(operation.get("operationId"));
		String current = String.valueOf(operation.get("currentOperation"));
		long lastId = number(operation.get("lastProcessedId"));
		try {
			BatchResult batch = processBatch(operationId, current, lastId, batchSize);
			if (batch.exhausted()) advanceOrComplete(operationId, requested, current);
			return transaction.find(operationId);
		} catch (RuntimeException error) {
			transaction.fail(operationId, rootMessage(error), Instant.now());
			throw error;
		}
	}

	public Map<String, Object> status(long operationId) {
		return transaction.find(operationId);
	}

	private BatchResult processBatch(long operationId, String operation, long lastId, int batchSize) {
		return switch (operation) {
		case CLEAR_EXACT_DUPLICATE_VIDEOINFO -> transaction.clearDuplicateVideoInfo(operationId, lastId, batchSize,
				Instant.now());
		case PURGE_EXPIRED_RUN_ITEMS -> transaction.purgeExpiredRunItems(operationId, lastId, batchSize, Instant.now());
		case PURGE_EXPIRED_RUN_EVENTS -> transaction.purgeExpiredRunEvents(operationId, lastId, batchSize,
				Instant.now());
		case PURGE_EXPIRED_TERMINAL_RUNS -> transaction.purgeExpiredTerminalRuns(operationId, lastId, batchSize,
				Instant.now());
		case PURGE_EXPIRED_TERMINAL_JOBS -> transaction.purgeExpiredTerminalJobs(operationId, lastId, batchSize,
				Instant.now());
		default -> throw new IllegalStateException("未知数据库维护操作: " + operation);
		};
	}

	private void advanceOrComplete(long operationId, List<String> operations, String current) {
		int index = operations.indexOf(current);
		if (index >= 0 && index + 1 < operations.size()) {
			transaction.moveToOperation(operationId, operations.get(index + 1), Instant.now());
		} else {
			transaction.complete(operationId, Instant.now());
		}
	}

	private Map<String, Object> estimates(Map<String, Object> audit, List<String> operations) {
		Map<String, Object> result = new LinkedHashMap<>();
		Map<String, Object> video = map(audit.get("video"));
		Map<String, Object> retention = map(audit.get("retentionCandidates"));
		for (String operation : operations) {
			Map<String, Object> estimate = new LinkedHashMap<>();
			if (CLEAR_EXACT_DUPLICATE_VIDEOINFO.equals(operation)) {
				estimate.put("rows", number(video.get("exactEqualRows")));
				estimate.put("logicalChars", number(video.get("exactDuplicateVideoInfoChars")));
				estimate.put("unhandledDifferentRows", number(video.get("differentRows")));
			} else {
				estimate.put("rows", number(retention.get(retentionAuditKey(operation))));
				estimate.put("logicalChars", 0L);
			}
			result.put(operation, estimate);
		}
		return result;
	}

	private String retentionAuditKey(String operation) {
		return switch (operation) {
		case PURGE_EXPIRED_RUN_ITEMS -> "runItems";
		case PURGE_EXPIRED_RUN_EVENTS -> "runEvents";
		case PURGE_EXPIRED_TERMINAL_RUNS -> "terminalRuns";
		case PURGE_EXPIRED_TERMINAL_JOBS -> "terminalJobs";
		default -> throw new IllegalStateException("未知保留期操作: " + operation);
		};
	}

	private long estimateRows(JSONObject token, String operation) {
		JSONObject estimates = token.getJSONObject("estimates");
		JSONObject estimate = estimates == null ? null : estimates.getJSONObject(operation);
		return estimate == null ? 0L : estimate.getLongValue("rows");
	}

	private List<String> normalizeOperations(List<String> requested) {
		List<String> source = requested == null || requested.isEmpty()
				? DEFAULT_OPERATIONS : requested;
		LinkedHashSet<String> result = new LinkedHashSet<>();
		for (String operation : source) {
			String normalized = operation == null ? "" : operation.trim().toUpperCase();
			if (!ALLOWED_OPERATIONS.contains(normalized)) {
				throw new IllegalArgumentException("不支持的数据库维护操作: " + operation);
			}
			result.add(normalized);
		}
		return List.copyOf(result);
	}

	private String sign(String payload) {
		String encoded = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
		return encoded + "." + hmac(encoded);
	}

	private JSONObject verify(String token) {
		String[] parts = token.split("\\.", -1);
		if (parts.length != 2 || !MessageDigest.isEqual(hmac(parts[0]).getBytes(StandardCharsets.US_ASCII),
				parts[1].getBytes(StandardCharsets.US_ASCII))) {
			throw new IllegalArgumentException("previewToken 签名无效");
		}
		JSONObject payload = JSONObject.parseObject(new String(Base64.getUrlDecoder().decode(parts[0]),
				StandardCharsets.UTF_8));
		if (payload.getLongValue("expiresAt") < Instant.now().getEpochSecond()) {
			throw new IllegalArgumentException("previewToken 已过期，请重新 preview");
		}
		return payload;
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

	private byte[] signingKey(String configured) {
		if (configured != null && !configured.isBlank()) return configured.getBytes(StandardCharsets.UTF_8);
		byte[] generated = new byte[32];
		new SecureRandom().nextBytes(generated);
		return generated;
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> source ? (Map<String, Object>) source : Map.of();
	}

	private long number(Object value) {
		if (value instanceof Number number) return number.longValue();
		if (value == null || String.valueOf(value).isBlank()) return 0L;
		return Long.parseLong(String.valueOf(value));
	}

	private String rootMessage(Throwable error) {
		Throwable root = error;
		while (root.getCause() != null && root.getCause() != root) root = root.getCause();
		return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
	}
}
