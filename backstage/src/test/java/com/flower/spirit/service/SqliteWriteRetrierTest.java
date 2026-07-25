package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class SqliteWriteRetrierTest {

	@Test
	void retriesBusyFailureAndReturnsSecondAttempt() {
		AtomicInteger attempts = new AtomicInteger();
		List<Long> delays = new ArrayList<>();
		SqliteWriteRetrier retrier = new SqliteWriteRetrier(3, 0, 0, delays::add);

		String result = retrier.execute(() -> {
			if (attempts.incrementAndGet() == 1) {
				throw new RuntimeException("SQLITE_BUSY_SNAPSHOT");
			}
			return "saved";
		});

		assertThat(result).isEqualTo("saved");
		assertThat(attempts).hasValue(2);
		assertThat(delays).containsExactly(0L);
	}

	@Test
	void thirdBusyFailureIsRethrownWithoutWrappingRootCause() {
		AtomicInteger attempts = new AtomicInteger();
		RuntimeException terminal = new RuntimeException("SQLITE_BUSY terminal root");
		SqliteWriteRetrier retrier = new SqliteWriteRetrier(3, 0, 0, ignored -> { });

		assertThatThrownBy(() -> retrier.execute(() -> {
			int attempt = attempts.incrementAndGet();
			throw attempt == 3 ? terminal : new RuntimeException("SQLITE_BUSY attempt " + attempt);
		})).isSameAs(terminal);
		assertThat(attempts).hasValue(3);
	}

	@Test
	void unrelatedFailureIsNotRetried() {
		AtomicInteger attempts = new AtomicInteger();
		RuntimeException failure = new RuntimeException("validation failed");
		SqliteWriteRetrier retrier = new SqliteWriteRetrier(3, 0, 0, ignored -> { });

		assertThatThrownBy(() -> retrier.execute(() -> {
			attempts.incrementAndGet();
			throw failure;
		})).isSameAs(failure);
		assertThat(attempts).hasValue(1);
	}
}
