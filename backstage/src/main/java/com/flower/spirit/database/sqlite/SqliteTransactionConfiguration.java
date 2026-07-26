package com.flower.spirit.database.sqlite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManagerFactory;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "streamvault.database.kind", havingValue = "sqlite", matchIfMissing = true)
public class SqliteTransactionConfiguration {

	@Bean
	SqliteWriteCoordinator sqliteWriteCoordinator(
			@Value("${streamvault.sqlite.writer-lock.warn-after-ms:1000}") long warnAfterMs,
			@Value("${streamvault.sqlite.writer-lock.timeout-ms:30000}") long timeoutMs) {
		return new SqliteWriteCoordinator(warnAfterMs, timeoutMs);
	}

	@Bean(name = "transactionManager")
	PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory,
			SqliteWriteCoordinator coordinator) {
		return new SqliteSerializingJpaTransactionManager(entityManagerFactory, coordinator);
	}
}
