package com.flower.spirit.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.entity.AuthorProfileEntity;
import com.flower.spirit.service.transaction.AuthorEnrichmentTransaction;
import com.flower.spirit.utils.AuthorIdentityUtil;

@Service
public class AuthorEnrichmentWorker {

	private static final Logger logger = LoggerFactory.getLogger(AuthorEnrichmentWorker.class);

	private final AuthorEnrichmentTransaction transaction;
	private final AuthorProfileDao authorProfileDao;
	private final AuthorProfileService authorProfileService;
	private final DouyinProfileGateway douyinProfileGateway;
	private final DatabaseWriteExecutor databaseWriteExecutor;
	private final int maxAttempts;
	private final AtomicBoolean running = new AtomicBoolean(false);

	public AuthorEnrichmentWorker(AuthorEnrichmentTransaction transaction, AuthorProfileDao authorProfileDao,
			AuthorProfileService authorProfileService, DouyinProfileGateway douyinProfileGateway,
			DatabaseWriteExecutor databaseWriteExecutor,
			@Value("${streamvault.author-enrichment.max-attempts:5}") int maxAttempts) {
		this.transaction = transaction;
		this.authorProfileDao = authorProfileDao;
		this.authorProfileService = authorProfileService;
		this.douyinProfileGateway = douyinProfileGateway;
		this.databaseWriteExecutor = databaseWriteExecutor;
		this.maxAttempts = Math.max(1, maxAttempts);
	}

	public void processOne() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		try {
			AuthorEnrichmentClaim claim = databaseWriteExecutor.execute("author-enrichment-claim",
					() -> transaction.claimNext(Instant.now(), 15));
			if (claim != null) {
				process(claim);
			}
		} catch (RuntimeException error) {
			logger.error("[AuthorEnrichment] worker tick failed", error);
		} finally {
			running.set(false);
		}
	}

	private void process(AuthorEnrichmentClaim claim) {
		logger.info("[AuthorEnrichment] started jobId={} platformKey={} authorUid={} attempt={}",
				claim.id(), claim.platformKey(), claim.authorUid(), claim.attemptCount());
		if (!"douyin".equals(claim.platformKey()) || !AuthorIdentityUtil.isDouyinSecUid(claim.authorUid())) {
			markFailed(claim, "UNSUPPORTED_IDENTITY", "Unsupported platform or non-canonical author UID");
			return;
		}

		JSONObject profileUser;
		try {
			profileUser = douyinProfileGateway.fetchProfileUser(claim.authorUid());
			if (profileUser == null) {
				throw new IllegalStateException("Profile API returned empty data or an unknown response schema");
			}
		} catch (RuntimeException error) {
			retryOrFail(claim, classifyFetchError(error), rootMessage(error), retryDelay(error));
			return;
		}

		String responseUid = AuthorIdentityUtil.canonicalAuthorUid("douyin", profileUser.getString("sec_uid"),
				profileUser.getString("sec_uid"));
		if (!claim.authorUid().equals(responseUid)) {
			markFailed(claim, "IDENTITY_MISMATCH", responseUid == null
					? "Profile response is missing a canonical sec_uid"
					: "Profile response UID does not match the queued author: " + responseUid);
			return;
		}

		AuthorProfileEntity profile = preferredProfile(claim.authorUid());
		if (profile == null || profile.getId() == null) {
			retryOrFail(claim, "PROFILE_NOT_READY", "Canonical local author profile does not exist yet",
					5, ChronoUnit.MINUTES);
			return;
		}

		try {
			databaseWriteExecutor.execute("author-enrichment-apply-profile",
					() -> authorProfileService.applyExternalDouyinProfile(profile.getId(), profileUser));
		} catch (RuntimeException error) {
			retryOrFail(claim, "DB_WRITE_FAILED", rootMessage(error), 1, ChronoUnit.MINUTES);
			return;
		}

		databaseWriteExecutor.execute("author-enrichment-complete", () -> {
			transaction.complete(claim.id(), Instant.now());
			return null;
		});
		logger.info("[AuthorEnrichment] completed jobId={} authorUid={} attempt={}", claim.id(), claim.authorUid(),
				claim.attemptCount());
	}

	private AuthorProfileEntity preferredProfile(String authorUid) {
		List<AuthorProfileEntity> profiles = authorProfileDao
				.findAllByPlatformkeyAndAuthoruidOrderByUpdatetimeDescIdDesc("douyin", authorUid);
		if (!profiles.isEmpty()) {
			return profiles.get(0);
		}
		for (String alias : List.of("抖音", "douyin")) {
			profiles = authorProfileDao.findAllByPlatformAndAuthoruidOrderByUpdatetimeDescIdDesc(alias, authorUid);
			if (!profiles.isEmpty()) {
				return profiles.get(0);
			}
		}
		return null;
	}

	private void retryOrFail(AuthorEnrichmentClaim claim, String errorCode, String message, RetryDelay delay) {
		retryOrFail(claim, errorCode, message, delay.amount(), delay.unit());
	}

	private void retryOrFail(AuthorEnrichmentClaim claim, String errorCode, String message, long amount,
			ChronoUnit unit) {
		if (claim.attemptCount() >= maxAttempts) {
			markFailed(claim, errorCode, message);
			return;
		}
		Instant now = Instant.now();
		Instant nextAttemptAt = now.plus(amount, unit);
		databaseWriteExecutor.execute("author-enrichment-retry", () -> {
			transaction.retryLater(claim.id(), errorCode, message, nextAttemptAt, now);
			return null;
		});
		logger.warn("[AuthorEnrichment] retry scheduled jobId={} authorUid={} attempt={} code={} nextAttemptAt={} root={}",
				claim.id(), claim.authorUid(), claim.attemptCount(), errorCode, nextAttemptAt, message);
	}

	private void markFailed(AuthorEnrichmentClaim claim, String errorCode, String message) {
		databaseWriteExecutor.execute("author-enrichment-fail", () -> {
			transaction.fail(claim.id(), errorCode, message, Instant.now());
			return null;
		});
		logger.error("[AuthorEnrichment] failed jobId={} authorUid={} attempt={} code={} root={}",
				claim.id(), claim.authorUid(), claim.attemptCount(), errorCode, message);
	}

	private String classifyFetchError(RuntimeException error) {
		String message = rootMessage(error).toLowerCase(Locale.ROOT);
		if (message.contains("429") || message.contains("risk") || message.contains("风控")
				|| message.contains("cookie")) {
			return "RISK_CONTROL";
		}
		if (message.contains("404") || message.contains("not found") || message.contains("不存在")) {
			return "PROFILE_NOT_FOUND";
		}
		return "FETCH_FAILED";
	}

	private RetryDelay retryDelay(RuntimeException error) {
		String code = classifyFetchError(error);
		if ("PROFILE_NOT_FOUND".equals(code)) {
			return new RetryDelay(7, ChronoUnit.DAYS);
		}
		if ("RISK_CONTROL".equals(code)) {
			return new RetryDelay(1, ChronoUnit.HOURS);
		}
		return new RetryDelay(15, ChronoUnit.MINUTES);
	}

	private String rootMessage(Throwable error) {
		Throwable root = error;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String message = root.getMessage();
		return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
	}

	private record RetryDelay(long amount, ChronoUnit unit) {
	}
}
