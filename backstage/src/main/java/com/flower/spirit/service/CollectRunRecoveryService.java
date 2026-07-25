package com.flower.spirit.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import com.flower.spirit.service.transaction.CollectQueueTransaction;

@Service
public class CollectRunRecoveryService {

	private static final Logger logger = LoggerFactory.getLogger(CollectRunRecoveryService.class);
	private final CollectQueueTransaction transaction;
	private final SqliteWriteRetrier sqliteWriteRetrier;

	public CollectRunRecoveryService(CollectQueueTransaction transaction, SqliteWriteRetrier sqliteWriteRetrier) {
		this.transaction = transaction;
		this.sqliteWriteRetrier = sqliteWriteRetrier;
	}

	@Order(300)
	@EventListener(ApplicationReadyEvent.class)
	public void recover() {
		Instant now = Instant.now();
		try {
			int recovered = sqliteWriteRetrier.execute(
					() -> transaction.recoverStale(now.minus(5, ChronoUnit.MINUTES), now));
			if (recovered > 0) logger.warn("[CollectRecovery] recovered stale jobs count={}", recovered);
		} catch (RuntimeException error) {
			logger.error("[CollectRecovery] startup recovery failed", error);
		}
	}
}
