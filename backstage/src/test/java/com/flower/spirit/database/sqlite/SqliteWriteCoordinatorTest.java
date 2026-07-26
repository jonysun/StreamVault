package com.flower.spirit.database.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.flower.spirit.database.DatabaseWriteContentionException;

class SqliteWriteCoordinatorTest {

	@Test
	void secondWriterWaitsUntilFirstPermitIsReleased() throws Exception {
		SqliteWriteCoordinator coordinator = new SqliteWriteCoordinator(25, 2000);
		CountDownLatch firstAcquired = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		List<String> events = new CopyOnWriteArrayList<>();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = executor.submit(() -> {
				try (SqliteWriteCoordinator.Permit ignored = coordinator.acquire("first")) {
					events.add("first-acquired");
					firstAcquired.countDown();
					await(releaseFirst);
					events.add("first-releasing");
				}
			});
			assertThat(firstAcquired.await(1, TimeUnit.SECONDS)).isTrue();
			Future<?> second = executor.submit(() -> {
				try (SqliteWriteCoordinator.Permit ignored = coordinator.acquire("second")) {
					events.add("second-acquired");
				}
			});

			Thread.sleep(75);
			assertThat(events).containsExactly("first-acquired");
			assertThat(coordinator.waitingCount()).isEqualTo(1);
			releaseFirst.countDown();
			first.get(1, TimeUnit.SECONDS);
			second.get(1, TimeUnit.SECONDS);
			assertThat(events).containsExactly("first-acquired", "first-releasing", "second-acquired");
			assertThat(coordinator.isLocked()).isFalse();
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void reentrantPermitsKeepOuterWriterOwnership() {
		SqliteWriteCoordinator coordinator = new SqliteWriteCoordinator(0, 1000);
		try (SqliteWriteCoordinator.Permit outer = coordinator.acquire("outer")) {
			assertThat(coordinator.ownerOperation()).isEqualTo("outer");
			try (SqliteWriteCoordinator.Permit inner = coordinator.acquire("inner")) {
				assertThat(coordinator.ownerOperation()).isEqualTo("outer");
			}
			assertThat(coordinator.isLocked()).isTrue();
		}
		assertThat(coordinator.isLocked()).isFalse();
	}

	@Test
	void timeoutReportsCurrentOwnerAndIncrementsMetric() throws Exception {
		SqliteWriteCoordinator coordinator = new SqliteWriteCoordinator(10, 50);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (SqliteWriteCoordinator.Permit ignored = coordinator.acquire("long-write")) {
			Future<?> blocked = executor.submit(() -> assertThatThrownBy(() -> coordinator.acquire("blocked-write"))
					.isInstanceOf(DatabaseWriteContentionException.class)
					.hasMessageContaining("blocked-write")
					.hasMessageContaining("long-write"));
			blocked.get(1, TimeUnit.SECONDS);
			assertThat(coordinator.lockTimeoutCount()).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(error);
		}
	}
}
