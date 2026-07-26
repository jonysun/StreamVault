package com.flower.spirit.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.service.transaction.CollectQueueTransaction;

@Service
public class CollectRunRecoveryService {

	private static final Logger logger = LoggerFactory.getLogger(CollectRunRecoveryService.class);
	private final CollectQueueTransaction transaction;
	private final DatabaseWriteExecutor databaseWriteExecutor;

	public CollectRunRecoveryService(CollectQueueTransaction transaction, DatabaseWriteExecutor databaseWriteExecutor) {
		this.transaction = transaction;
		this.databaseWriteExecutor = databaseWriteExecutor;
	}

	@Order(300)
	@EventListener(ApplicationReadyEvent.class)
	public void recover() {
		Instant now = Instant.now();
		try {
			int recovered = databaseWriteExecutor.execute("collect-run-recovery",
					() -> transaction.recoverStale(now.minus(5, ChronoUnit.MINUTES), now));
			if (recovered > 0) logger.warn("[CollectRecovery] recovered stale jobs count={}", recovered);
		} catch (RuntimeException error) {
			logger.error("[CollectRecovery] startup recovery failed", error);
		}
	}
}
