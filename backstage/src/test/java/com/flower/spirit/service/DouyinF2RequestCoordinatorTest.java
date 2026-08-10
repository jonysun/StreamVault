package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class DouyinF2RequestCoordinatorTest {

	@Test
	void permitsOnlyOneF2RequestAndReleasesAfterClose() throws Exception {
		DouyinF2RequestCoordinator coordinator = new DouyinF2RequestCoordinator();
		var executor = Executors.newSingleThreadExecutor();
		CountDownLatch acquired = new CountDownLatch(1);
		try (var first = coordinator.acquire()) {
			var waiting = executor.submit(() -> {
				try (var ignored = coordinator.acquire()) {
					acquired.countDown();
					return true;
				}
			});
			assertThat(acquired.await(100, TimeUnit.MILLISECONDS)).isFalse();
			assertThat(waiting.isDone()).isFalse();
		}
		assertThat(acquired.await(1, TimeUnit.SECONDS)).isTrue();
		executor.shutdownNow();
	}
}
