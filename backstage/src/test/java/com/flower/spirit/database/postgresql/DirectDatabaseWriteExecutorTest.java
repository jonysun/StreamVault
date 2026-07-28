package com.flower.spirit.database.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class DirectDatabaseWriteExecutorTest {

	@Test
	void executesActionExactlyOnce() {
		AtomicInteger attempts = new AtomicInteger();

		String result = new DirectDatabaseWriteExecutor().execute("work-persistence", () -> {
			attempts.incrementAndGet();
			return "saved";
		});

		assertThat(result).isEqualTo("saved");
		assertThat(attempts).hasValue(1);
	}
}
