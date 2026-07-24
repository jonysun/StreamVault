package com.flower.spirit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.flower.spirit.config.Global;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.VideoDataEntity;

class HlsTranscodeServiceTest {

	private boolean previousEnabled;
	private int previousConcurrency;
	private String previousMode;

	@BeforeEach
	void rememberGlobalConfig() {
		previousEnabled = Global.hlsEnable;
		previousConcurrency = Global.hlsConcurrency;
		previousMode = Global.hlsMode;
		Global.hlsEnable = true;
		Global.hlsMode = "immediate";
	}

	@AfterEach
	void restoreGlobalConfig() {
		Global.hlsEnable = previousEnabled;
		Global.hlsConcurrency = previousConcurrency;
		Global.hlsMode = previousMode;
	}

	@Test
	void statsRemainResponsiveWhileTranscodeIsRunning() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		BlockingHlsService service = new BlockingHlsService(started, release,
				new AtomicInteger(), new AtomicInteger());
		prepareVideos(service, 1);
		Global.hlsConcurrency = 1;

		service.enqueueByIds("1");
		service.processQueueTick(true);
		assertTrue(started.await(2, TimeUnit.SECONDS));

		org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofMillis(250), service::stats);
		assertEquals(1, service.runningCountSnapshot());
		assertTrue(service.runningVideoIdsSnapshot().contains(1));

		release.countDown();
		waitForIdle(service);
		service.shutdown();
	}

	@Test
	void configuredConcurrencyLimitsReservedJobs() throws Exception {
		CountDownLatch started = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger active = new AtomicInteger();
		AtomicInteger maxActive = new AtomicInteger();
		BlockingHlsService service = new BlockingHlsService(started, release, active, maxActive);
		prepareVideos(service, 1, 2, 3, 4);
		Global.hlsConcurrency = 2;

		service.enqueueByIds("1,2,3,4");
		service.processQueueTick(true);
		assertTrue(started.await(2, TimeUnit.SECONDS));

		assertEquals(2, service.runningCountSnapshot());
		assertEquals(2, service.queueSize());
		assertEquals(2, maxActive.get());

		release.countDown();
		waitForIdle(service);
		assertEquals(0, service.queueSize());
		service.shutdown();
	}

	@Test
	void failedJobReleasesRunningStateAndRecordsError() throws Exception {
		HlsTranscodeService service = new HlsTranscodeService() {
			@Override
			protected void transcodeOne(Integer id) {
				throw new IllegalStateException("simulated failure");
			}
		};
		prepareVideos(service, 9);
		Global.hlsConcurrency = 1;

		service.enqueueByIds("9");
		service.processQueueTick(true);
		waitForIdle(service);

		@SuppressWarnings("unchecked")
		Map<String, Object> stats = (Map<String, Object>) service.stats().getRecord();
		assertEquals(0, stats.get("runningCount"));
		assertTrue(String.valueOf(stats.get("lastError")).contains("simulated failure"));
		service.shutdown();
	}

	@Test
	void deletionReservationRemovesQueuedWorkAndBlocksRequeueUntilReleased() {
		HlsTranscodeService service = new HlsTranscodeService();
		prepareVideos(service, 17);

		assertTrue(service.enqueueVideo(17));
		assertEquals(1, service.queueSize());
		assertTrue(service.beginVideoDeletion(17));
		assertEquals(0, service.queueSize());
		assertFalse(service.enqueueVideo(17));

		service.endVideoDeletion(17);
		assertTrue(service.enqueueVideo(17));
		service.shutdown();
	}

	@Test
	void runningWorkCannotBeReservedForDeletion() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		BlockingHlsService service = new BlockingHlsService(started, release,
				new AtomicInteger(), new AtomicInteger());
		prepareVideos(service, 18);
		Global.hlsConcurrency = 1;

		service.enqueueByIds("18");
		service.processQueueTick(true);
		assertTrue(started.await(2, TimeUnit.SECONDS));
		assertFalse(service.beginVideoDeletion(18));

		release.countDown();
		waitForIdle(service);
		service.shutdown();
	}

	private void prepareVideos(HlsTranscodeService service, int... ids) {
		VideoDataDao dao = mock(VideoDataDao.class);
		for (int id : ids) {
			VideoDataEntity video = new VideoDataEntity();
			video.setId(id);
			when(dao.findById(id)).thenReturn(Optional.of(video));
		}
		ReflectionTestUtils.setField(service, "videoDataDao", dao);
	}

	private void waitForIdle(HlsTranscodeService service) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 3000;
		while (service.runningCountSnapshot() > 0 && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertEquals(0, service.runningCountSnapshot());
	}

	private static final class BlockingHlsService extends HlsTranscodeService {
		private final CountDownLatch started;
		private final CountDownLatch release;
		private final AtomicInteger active;
		private final AtomicInteger maxActive;

		private BlockingHlsService(CountDownLatch started, CountDownLatch release,
				AtomicInteger active, AtomicInteger maxActive) {
			this.started = started;
			this.release = release;
			this.active = active;
			this.maxActive = maxActive;
		}

		@Override
		protected void transcodeOne(Integer id) {
			int current = active.incrementAndGet();
			maxActive.accumulateAndGet(current, Math::max);
			started.countDown();
			try {
				release.await(2, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				active.decrementAndGet();
			}
		}
	}
}
