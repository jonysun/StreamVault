package com.flower.spirit.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.flower.spirit.entity.AuthorProfileEntity;
import com.flower.spirit.service.transaction.AuthorEnrichmentTransaction;
import com.flower.spirit.utils.AuthorIdentityUtil;

@Service
public class AuthorEnrichmentQueueService {

	private static final Logger logger = LoggerFactory.getLogger(AuthorEnrichmentQueueService.class);
	private static final int AUTOMATIC_PRIORITY = 100;
	private static final int MANUAL_PRIORITY = 0;

	private final AuthorEnrichmentTransaction transaction;
	private final SqliteWriteRetrier sqliteWriteRetrier;

	public AuthorEnrichmentQueueService(AuthorEnrichmentTransaction transaction,
			SqliteWriteRetrier sqliteWriteRetrier) {
		this.transaction = transaction;
		this.sqliteWriteRetrier = sqliteWriteRetrier;
	}

	public AuthorCompleteness inspect(AuthorObservation observation) {
		EnumSet<AuthorField> missing = EnumSet.noneOf(AuthorField.class);
		if (observation == null || blank(observation.displayName())) missing.add(AuthorField.DISPLAY_NAME);
		if (observation == null || blank(observation.username())) missing.add(AuthorField.USERNAME);
		if (observation == null || blank(observation.avatar())) missing.add(AuthorField.AVATAR);
		if (observation == null || blank(observation.signature())) missing.add(AuthorField.SIGNATURE);
		if (observation == null || blank(observation.homepage())) missing.add(AuthorField.HOMEPAGE);
		return new AuthorCompleteness(missing);
	}

	public void enqueueAfterCommitIfIncomplete(AuthorObservation observation) {
		if (!isSupportedIncompleteObservation(observation)) {
			return;
		}
		AuthorCompleteness completeness = inspect(observation);
		int priority = completeness.missingFields().equals(Set.of(AuthorField.USERNAME))
				? 200 : AUTOMATIC_PRIORITY;
		Runnable enqueue = () -> enqueueSafely(observation.platformKey(), observation.authorUid(), priority,
				false);
		if (TransactionSynchronizationManager.isActualTransactionActive()
				&& TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					enqueue.run();
				}
			});
			return;
		}
		enqueue.run();
	}

	public AuthorEnrichmentEnqueueResult enqueueManual(AuthorProfileEntity profile) {
		if (profile == null || profile.getId() == null) {
			throw new IllegalArgumentException("Author profile is required");
		}
		String platformKey = AuthorIdentityUtil.canonicalPlatformKey(profile.getPlatformkey(), profile.getPlatform());
		String authorUid = AuthorIdentityUtil.canonicalAuthorUid(platformKey, profile.getAuthoruid(),
				profile.getAuthoruid());
		if (!"douyin".equals(platformKey) || !AuthorIdentityUtil.isDouyinSecUid(authorUid)) {
			throw new IllegalArgumentException("Only canonical Douyin author profiles can be refreshed");
		}
		return sqliteWriteRetrier.execute(() -> transaction.enqueue(platformKey, authorUid, MANUAL_PRIORITY,
				true, Instant.now()));
	}

	public int reconcileMissingWorkAuthors(int limit) {
		return sqliteWriteRetrier.execute(() -> transaction.enqueueMissingWorkAuthors(Instant.now(), limit));
	}

	private boolean isSupportedIncompleteObservation(AuthorObservation observation) {
		return observation != null
				&& "douyin".equals(AuthorIdentityUtil.canonicalPlatformKey(observation.platformKey(),
						observation.platformKey()))
				&& AuthorIdentityUtil.isDouyinSecUid(observation.authorUid())
				&& !inspect(observation).isComplete();
	}

	private void enqueueSafely(String platformKey, String authorUid, int priority, boolean promote) {
		try {
			sqliteWriteRetrier.execute(() -> transaction.enqueue(platformKey, authorUid, priority, promote,
					Instant.now()));
		} catch (RuntimeException error) {
			logger.error("[AuthorEnrichment] enqueue failed platformKey={} authorUid={}", platformKey, authorUid,
					error);
		}
	}

	private boolean blank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
