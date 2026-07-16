package com.flower.spirit.service;

import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.utils.CommandUtil;

import jakarta.annotation.PreDestroy;

@Service
public class HlsTranscodeService {

	private static final Logger logger = LoggerFactory.getLogger(HlsTranscodeService.class);
	private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
	private static final String HLS_FLAGS = "independent_segments+temp_file";
	private static final int MAX_QUEUE_SIZE = 1000;
	private static final int FFMPEG_THREADS = 2;
	private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger(1);

	@Autowired
	private VideoDataDao videoDataDao;

	private final Object stateLock = new Object();
	private final Deque<Integer> queue = new ArrayDeque<>();
	private final Set<Integer> dedupe = new HashSet<>();
	private final Set<Integer> runningVideoIds = new LinkedHashSet<>();
	private final ExecutorService transcodeExecutor = Executors.newCachedThreadPool(new HlsThreadFactory());

	private volatile long lastRunAt = 0L;
	private volatile long lastSuccessAt = 0L;
	private volatile long lastFailAt = 0L;
	private volatile String lastError = "";
	private volatile boolean shuttingDown = false;

	public AjaxEntity enqueueByIds(String idsCsv) {
		if (idsCsv == null || idsCsv.trim().isEmpty()) {
			return new AjaxEntity(Global.ajax_uri_error, "ids不能为空", null);
		}
		String[] parts = idsCsv.split(",");
		int added = 0;
		for (String part : parts) {
			String value = part == null ? "" : part.trim();
			if (value.isEmpty()) {
				continue;
			}
			try {
				if (enqueue(Integer.parseInt(value))) {
					added++;
				}
			} catch (NumberFormatException e) {
				logger.warn("[HLS] ignore invalid enqueue id={}", value);
			}
		}
		return new AjaxEntity(Global.ajax_success, "已入队: " + added, added);
	}

	public AjaxEntity enqueueMissingLatest(int limit) {
		int safeLimit = Math.max(1, Math.min(limit, 1000));
		List<VideoDataEntity> all = videoDataDao.findAll();
		all.sort(Comparator.comparing(VideoDataEntity::getId, Comparator.nullsLast(Comparator.reverseOrder())));
		int added = 0;
		int seen = 0;
		for (VideoDataEntity video : all) {
			if (video == null || video.getId() == null || !allowPrivacy(video)) {
				continue;
			}
			if (++seen > safeLimit) {
				break;
			}
			if (!hasHls(video) && enqueue(video.getId())) {
				added++;
			}
		}
		return new AjaxEntity(Global.ajax_success, "扫描并入队完成: " + added, added);
	}

	public AjaxEntity rebuildByIds(String idsCsv) {
		if (idsCsv == null || idsCsv.trim().isEmpty()) {
			return new AjaxEntity(Global.ajax_uri_error, "ids不能为空", null);
		}
		int added = 0;
		for (String part : idsCsv.split(",")) {
			String value = part == null ? "" : part.trim();
			if (value.isEmpty()) {
				continue;
			}
			try {
				if (enqueue(Integer.parseInt(value), true)) {
					added++;
				}
			} catch (NumberFormatException e) {
				logger.warn("[HLS] ignore invalid rebuild id={}", value);
			}
		}
		return new AjaxEntity(Global.ajax_success, "已强制重建入队: " + added, added);
	}

	public AjaxEntity rebuildLatest(int limit) {
		int safeLimit = Math.max(1, Math.min(limit, 1000));
		List<VideoDataEntity> all = videoDataDao.findAll();
		all.sort(Comparator.comparing(VideoDataEntity::getId, Comparator.nullsLast(Comparator.reverseOrder())));
		int added = 0;
		int seen = 0;
		for (VideoDataEntity video : all) {
			if (video == null || video.getId() == null || !allowPrivacy(video)) {
				continue;
			}
			if (++seen > safeLimit) {
				break;
			}
			if (enqueue(video.getId(), true)) {
				added++;
			}
		}
		return new AjaxEntity(Global.ajax_success, "强制重建扫描并入队完成: " + added, added);
	}

	public AjaxEntity processNowOnce() {
		processQueueTick(true);
		return new AjaxEntity(Global.ajax_success, "已触发立即处理", null);
	}

	public void processQueueTick(boolean forceRun) {
		if (!Global.hlsEnable) {
			return;
		}
		if (!forceRun && "idle".equalsIgnoreCase(Global.hlsMode) && !isInIdleWindow(Global.hlsIdleWindow)) {
			return;
		}
		for (Integer id : reserveAvailableJobs()) {
			try {
				transcodeExecutor.execute(() -> runReservedJob(id, forceRun));
			} catch (RejectedExecutionException e) {
				releaseReservedJob(id, true);
				markFailure(id, "transcode worker rejected task", e);
			}
		}
	}

	private List<Integer> reserveAvailableJobs() {
		List<Integer> reserved = new ArrayList<>();
		synchronized (stateLock) {
			if (shuttingDown) {
				return reserved;
			}
			int capacity = Math.max(1, Global.hlsConcurrency) - runningVideoIds.size();
			while (capacity-- > 0) {
				Integer id = queue.pollFirst();
				if (id == null) {
					break;
				}
				dedupe.remove(id);
				runningVideoIds.add(id);
				reserved.add(id);
			}
		}
		return reserved;
	}

	private void runReservedJob(Integer id, boolean forceRun) {
		try {
			transcodeOne(id);
		} catch (Exception e) {
			markFailure(id, e.getMessage(), e);
		} finally {
			releaseReservedJob(id, false);
			processQueueTick(forceRun);
		}
	}

	private void releaseReservedJob(Integer id, boolean requeue) {
		synchronized (stateLock) {
			runningVideoIds.remove(id);
			if (requeue && !shuttingDown && !dedupe.contains(id)) {
				dedupe.add(id);
				queue.offerFirst(id);
			}
		}
	}

	public int queueSize() {
		synchronized (stateLock) {
			return queue.size();
		}
	}

	public Set<Integer> queuedIdsSnapshot() {
		synchronized (stateLock) {
			return new HashSet<>(dedupe);
		}
	}

	public int runningCountSnapshot() {
		synchronized (stateLock) {
			return runningVideoIds.size();
		}
	}

	public Integer runningVideoIdSnapshot() {
		synchronized (stateLock) {
			return runningVideoIds.stream().findFirst().orElse(null);
		}
	}

	public Set<Integer> runningVideoIdsSnapshot() {
		synchronized (stateLock) {
			return new LinkedHashSet<>(runningVideoIds);
		}
	}

	public AjaxEntity stats() {
		int queued;
		Set<Integer> runningIds;
		synchronized (stateLock) {
			queued = queue.size();
			runningIds = new LinkedHashSet<>(runningVideoIds);
		}
		java.util.Map<String, Object> map = new java.util.HashMap<>();
		map.put("enabled", Global.hlsEnable);
		map.put("mode", Global.hlsMode);
		map.put("idleWindow", Global.hlsIdleWindow);
		map.put("concurrency", Global.hlsConcurrency);
		map.put("segmentSeconds", Global.hlsSegmentSeconds);
		map.put("privacyEnabled", Global.hlsPrivacyEnabled);
		map.put("queueSize", queued);
		map.put("runningCount", runningIds.size());
		map.put("runningVideoIds", runningIds);
		map.put("runningVideoId", runningIds.stream().findFirst().orElse(null));
		map.put("lastRunAt", lastRunAt);
		map.put("lastSuccessAt", lastSuccessAt);
		map.put("lastFailAt", lastFailAt);
		map.put("lastError", lastError);
		return new AjaxEntity(Global.ajax_success, "数据获取成功", map);
	}

	public boolean hasHls(VideoDataEntity video) {
		if (video == null || video.getVideoaddr() == null || video.getVideoaddr().trim().isEmpty()) {
			return false;
		}
		File mp4 = new File(video.getVideoaddr());
		File hls = new File(mp4.getParentFile(), "hls" + File.separator + "index.m3u8");
		return hls.isFile() && hls.length() > 0;
	}

	public String buildHlsPlayUrl(VideoDataEntity video) {
		if (video == null || video.getVideounrealaddr() == null || video.getVideounrealaddr().trim().isEmpty()) {
			return null;
		}
		String normalized = video.getVideounrealaddr().replace("\\", "/");
		int index = normalized.lastIndexOf('/');
		return index <= 0 ? normalized : normalized.substring(0, index) + "/hls/index.m3u8";
	}

	private boolean enqueue(Integer id) {
		return enqueue(id, false);
	}

	private boolean enqueue(Integer id, boolean forceRebuild) {
		synchronized (stateLock) {
			if (id == null || shuttingDown || dedupe.contains(id) || runningVideoIds.contains(id)) {
				return false;
			}
			if (queue.size() >= MAX_QUEUE_SIZE) {
				logger.warn("[HLS] enqueue rejected because queue is full size={} id={}", queue.size(), id);
				return false;
			}
			Optional<VideoDataEntity> optional = videoDataDao.findById(id);
			if (!optional.isPresent()) {
				return false;
			}
			VideoDataEntity video = optional.get();
			if (!allowPrivacy(video) || (!forceRebuild && hasHls(video))) {
				return false;
			}
			if (forceRebuild) {
				clearHlsArtifacts(video);
			}
			dedupe.add(id);
			queue.offerLast(id);
			return true;
		}
	}

	private void clearHlsArtifacts(VideoDataEntity video) {
		if (video == null || video.getVideoaddr() == null || video.getVideoaddr().trim().isEmpty()) {
			return;
		}
		File parent = new File(video.getVideoaddr()).getParentFile();
		if (parent == null) {
			return;
		}
		File hlsDir = new File(parent, "hls");
		if (!hlsDir.isDirectory()) {
			return;
		}
		File[] files = hlsDir.listFiles();
		if (files != null) {
			for (File file : files) {
				if (file != null && file.exists() && !file.delete()) {
					logger.warn("[HLS] force rebuild failed to delete file path={}", file.getPath());
				}
			}
		}
		if (!hlsDir.delete() && hlsDir.exists()) {
			logger.warn("[HLS] force rebuild failed to delete dir path={}", hlsDir.getPath());
		}
	}

	private boolean allowPrivacy(VideoDataEntity video) {
		return video != null && (Global.hlsPrivacyEnabled || !"1".equals(video.getVideoprivacy()));
	}

	protected void transcodeOne(Integer id) {
		lastRunAt = System.currentTimeMillis();
		VideoDataEntity video = videoDataDao.findById(id)
				.orElseThrow(() -> new IllegalStateException("video not found"));
		if (hasHls(video)) {
			lastSuccessAt = System.currentTimeMillis();
			return;
		}
		if (video.getVideoaddr() == null || video.getVideoaddr().trim().isEmpty()) {
			throw new IllegalStateException("video path is empty");
		}
		File input = new File(video.getVideoaddr());
		if (!input.isFile() || input.length() <= 0) {
			throw new IllegalStateException("video file is missing or empty: " + input.getPath());
		}
		File hlsDir = new File(input.getParentFile(), "hls");
		if (!hlsDir.exists() && !hlsDir.mkdirs()) {
			throw new IllegalStateException("cannot create HLS directory: " + hlsDir.getPath());
		}
		File m3u8 = new File(hlsDir, "index.m3u8");
		String tsPattern = new File(hlsDir, "seg_%05d.ts").getPath();

		if (m3u8.exists() && !m3u8.delete()) {
			logger.warn("[HLS] failed to delete old m3u8 id={} path={}", id, m3u8.getPath());
		}
		File[] oldSegments = hlsDir.listFiles((dir, name) -> name != null
				&& (name.endsWith(".ts") || name.endsWith(".m4s") || name.endsWith(".tmp")));
		if (oldSegments != null) {
			for (File segment : oldSegments) {
				if (segment != null && segment.exists() && !segment.delete()) {
					logger.warn("[HLS] failed to delete old segment id={} path={}", id, segment.getPath());
				}
			}
		}

		String command = "ffmpeg -y -i \"" + input.getPath()
				+ "\" -map 0:v:0 -map 0:a? -c:v libx264 -profile:v main -level 4.0 "
				+ "-pix_fmt yuv420p -preset veryfast -crf 23 -threads " + FFMPEG_THREADS
				+ " -r 30 -vsync cfr -g 60 -keyint_min 60 -sc_threshold 0 "
				+ "-c:a aac -b:a 128k -af aresample=async=1000:min_hard_comp=0.100:first_pts=0 "
				+ "-ar 48000 -ac 2 -hls_time " + Math.max(2, Global.hlsSegmentSeconds)
				+ " -max_muxing_queue_size 2048 -movflags +faststart"
				+ " -hls_list_size 0 -hls_playlist_type vod -hls_flags " + HLS_FLAGS
				+ " -hls_segment_filename \"" + tsPattern + "\" \"" + m3u8.getPath() + "\"";
		logger.info("[HLS] transcode start id={} mode=compat-encode threads={}", id, FFMPEG_THREADS);
		CommandUtil.command(command);
		if (!m3u8.isFile() || m3u8.length() <= 0) {
			throw new IllegalStateException("transcode produced no playlist");
		}
		logger.info("[HLS] transcode success id={} by compat-encode", id);
		lastSuccessAt = System.currentTimeMillis();
		lastError = "";
	}

	private void markFailure(Integer id, String detail, Exception error) {
		lastFailAt = System.currentTimeMillis();
		String safeDetail = detail == null || detail.trim().isEmpty()
				? error.getClass().getSimpleName() : detail.trim();
		lastError = "transcode failed for video id=" + id + ": " + safeDetail;
		logger.error("[HLS] transcode failed id={} detail={}", id, safeDetail, error);
	}

	private boolean isInIdleWindow(String windowsRaw) {
		if (windowsRaw == null || windowsRaw.trim().isEmpty()) {
			return false;
		}
		LocalTime now = LocalTime.now();
		for (String window : windowsRaw.split(",")) {
			String value = window == null ? "" : window.trim();
			if (value.isEmpty() || !value.contains("-")) {
				continue;
			}
			String[] range = value.split("-");
			if (range.length != 2) {
				continue;
			}
			try {
				LocalTime start = LocalTime.parse(range[0].trim(), TIME_FMT);
				LocalTime end = LocalTime.parse(range[1].trim(), TIME_FMT);
				if (start.equals(end)
						|| (start.isBefore(end) && !now.isBefore(start) && now.isBefore(end))
						|| (!start.isBefore(end) && (!now.isBefore(start) || now.isBefore(end)))) {
					return true;
				}
			} catch (Exception e) {
				logger.warn("[HLS] invalid idle window piece={}", value);
			}
		}
		return false;
	}

	@PreDestroy
	public void shutdown() {
		shuttingDown = true;
		transcodeExecutor.shutdown();
		try {
			if (!transcodeExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
				transcodeExecutor.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			transcodeExecutor.shutdownNow();
		}
	}

	private static final class HlsThreadFactory implements ThreadFactory {
		@Override
		public Thread newThread(Runnable task) {
			Thread thread = new Thread(task, "hls-transcode-" + WORKER_SEQUENCE.getAndIncrement());
			thread.setDaemon(true);
			return thread;
		}
	}
}
