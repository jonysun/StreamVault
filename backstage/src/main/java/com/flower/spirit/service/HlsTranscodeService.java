package com.flower.spirit.service;

import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.LinkedList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.utils.CommandUtil;

@Service
public class HlsTranscodeService {

	private static final Logger logger = LoggerFactory.getLogger(HlsTranscodeService.class);
	private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
	private static final String HLS_FLAGS = "independent_segments+temp_file";

	@Autowired
	private VideoDataDao videoDataDao;

	private final Queue<Integer> queue = new LinkedList<>();
	private final Set<Integer> dedupe = new HashSet<>();

	private volatile int runningCount = 0;
	private volatile Integer runningVideoId = null;
	private volatile long lastRunAt = 0L;
	private volatile long lastSuccessAt = 0L;
	private volatile long lastFailAt = 0L;
	private volatile String lastError = "";

	public synchronized AjaxEntity enqueueByIds(String idsCsv) {
		if (idsCsv == null || idsCsv.trim().isEmpty()) {
			return new AjaxEntity(Global.ajax_uri_error, "ids不能为空", null);
		}
		String[] parts = idsCsv.split(",");
		int added = 0;
		for (String p : parts) {
			String s = p == null ? "" : p.trim();
			if (s.isEmpty()) {
				continue;
			}
			try {
				int id = Integer.parseInt(s);
				if (enqueue(id)) {
					added++;
				}
			} catch (NumberFormatException e) {
				logger.warn("hls enqueueByIds ignore invalid id={}", s);
			}
		}
		return new AjaxEntity(Global.ajax_success, "已入队: " + added, added);
	}

	public synchronized AjaxEntity enqueueMissingLatest(int limit) {
		int safeLimit = Math.max(1, Math.min(limit, 1000));
		List<VideoDataEntity> all = videoDataDao.findAll();
		all.sort(Comparator.comparing(VideoDataEntity::getId, Comparator.nullsLast(Comparator.reverseOrder())));
		int added = 0;
		int seen = 0;
		for (VideoDataEntity v : all) {
			if (v == null || v.getId() == null) {
				continue;
			}
			if (!allowPrivacy(v)) {
				continue;
			}
			seen++;
			if (seen > safeLimit) {
				break;
			}
			if (!hasHls(v) && enqueue(v.getId())) {
				added++;
			}
		}
		return new AjaxEntity(Global.ajax_success, "扫描并入队完成: " + added, added);
	}

	public synchronized AjaxEntity rebuildByIds(String idsCsv) {
		if (idsCsv == null || idsCsv.trim().isEmpty()) {
			return new AjaxEntity(Global.ajax_uri_error, "ids不能为空", null);
		}
		String[] parts = idsCsv.split(",");
		int added = 0;
		for (String p : parts) {
			String s = p == null ? "" : p.trim();
			if (s.isEmpty()) {
				continue;
			}
			try {
				int id = Integer.parseInt(s);
				if (enqueue(id, true)) {
					added++;
				}
			} catch (NumberFormatException e) {
				logger.warn("hls rebuildByIds ignore invalid id={}", s);
			}
		}
		return new AjaxEntity(Global.ajax_success, "已强制重建入队: " + added, added);
	}

	public synchronized AjaxEntity rebuildLatest(int limit) {
		int safeLimit = Math.max(1, Math.min(limit, 1000));
		List<VideoDataEntity> all = videoDataDao.findAll();
		all.sort(Comparator.comparing(VideoDataEntity::getId, Comparator.nullsLast(Comparator.reverseOrder())));
		int added = 0;
		int seen = 0;
		for (VideoDataEntity v : all) {
			if (v == null || v.getId() == null) {
				continue;
			}
			if (!allowPrivacy(v)) {
				continue;
			}
			seen++;
			if (seen > safeLimit) {
				break;
			}
			if (enqueue(v.getId(), true)) {
				added++;
			}
		}
		return new AjaxEntity(Global.ajax_success, "强制重建扫描并入队完成: " + added, added);
	}

	public synchronized AjaxEntity processNowOnce() {
		processQueueTick(true);
		return new AjaxEntity(Global.ajax_success, "已触发立即处理", null);
	}

	public synchronized void processQueueTick(boolean forceRun) {
		if (!Global.hlsEnable) {
			return;
		}
		if (!forceRun && "idle".equalsIgnoreCase(Global.hlsMode) && !isInIdleWindow(Global.hlsIdleWindow)) {
			return;
		}
		while (runningCount < Math.max(1, Global.hlsConcurrency)) {
			Integer id = poll();
			if (id == null) {
				break;
			}
			runningCount++;
			try {
				transcodeOne(id);
			} finally {
				runningCount--;
			}
		}
	}

	public synchronized int queueSize() {
		return queue.size();
	}

	public synchronized Set<Integer> queuedIdsSnapshot() {
		return new HashSet<>(dedupe);
	}

	public synchronized int runningCountSnapshot() {
		return runningCount;
	}

	public synchronized Integer runningVideoIdSnapshot() {
		return runningVideoId;
	}

	public synchronized AjaxEntity stats() {
		java.util.Map<String, Object> map = new java.util.HashMap<>();
		map.put("enabled", Global.hlsEnable);
		map.put("mode", Global.hlsMode);
		map.put("idleWindow", Global.hlsIdleWindow);
		map.put("concurrency", Global.hlsConcurrency);
		map.put("segmentSeconds", Global.hlsSegmentSeconds);
		map.put("privacyEnabled", Global.hlsPrivacyEnabled);
		map.put("queueSize", queue.size());
		map.put("runningCount", runningCount);
		map.put("runningVideoId", runningVideoId);
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
		int idx = normalized.lastIndexOf('/');
		if (idx <= 0) {
			return normalized;
		}
		return normalized.substring(0, idx) + "/hls/index.m3u8";
	}

	private synchronized boolean enqueue(Integer id) {
		return enqueue(id, false);
	}

	private synchronized boolean enqueue(Integer id, boolean forceRebuild) {
		if (id == null || dedupe.contains(id)) {
			return false;
		}
		Optional<VideoDataEntity> opt = videoDataDao.findById(id);
		if (!opt.isPresent()) {
			return false;
		}
		VideoDataEntity video = opt.get();
		if (!allowPrivacy(video)) {
			return false;
		}
		if (!forceRebuild && hasHls(video)) {
			return false;
		}
		if (forceRebuild) {
			clearHlsArtifacts(video);
		}
		dedupe.add(id);
		queue.offer(id);
		return true;
	}

	private void clearHlsArtifacts(VideoDataEntity video) {
		if (video == null || video.getVideoaddr() == null || video.getVideoaddr().trim().isEmpty()) {
			return;
		}
		File mp4 = new File(video.getVideoaddr());
		File parent = mp4.getParentFile();
		if (parent == null) {
			return;
		}
		File hlsDir = new File(parent, "hls");
		if (!hlsDir.exists() || !hlsDir.isDirectory()) {
			return;
		}
		File[] files = hlsDir.listFiles();
		if (files != null) {
			for (File f : files) {
				if (f == null || !f.exists()) {
					continue;
				}
				if (!f.delete()) {
					logger.warn("[HLS] force rebuild failed to delete file path={}", f.getPath());
				}
			}
		}
		if (!hlsDir.delete() && hlsDir.exists()) {
			logger.warn("[HLS] force rebuild failed to delete dir path={}", hlsDir.getPath());
		}
	}

	private synchronized Integer poll() {
		Integer id = queue.poll();
		if (id != null) {
			dedupe.remove(id);
		}
		return id;
	}

	private boolean allowPrivacy(VideoDataEntity video) {
		if (video == null) {
			return false;
		}
		if (Global.hlsPrivacyEnabled) {
			return true;
		}
		return !"1".equals(video.getVideoprivacy());
	}

	private void transcodeOne(Integer id) {
		runningVideoId = id;
		lastRunAt = System.currentTimeMillis();
		Optional<VideoDataEntity> opt = videoDataDao.findById(id);
		if (!opt.isPresent()) {
			runningVideoId = null;
			return;
		}
		VideoDataEntity video = opt.get();
		if (hasHls(video)) {
			return;
		}
		if (video.getVideoaddr() == null || video.getVideoaddr().trim().isEmpty()) {
			return;
		}
		File input = new File(video.getVideoaddr());
		if (!input.isFile() || input.length() <= 0) {
			return;
		}
		File hlsDir = new File(input.getParentFile(), "hls");
		if (!hlsDir.exists()) {
			hlsDir.mkdirs();
		}
		File m3u8 = new File(hlsDir, "index.m3u8");
		String tsPattern = new File(hlsDir, "seg_%05d.ts").getPath();
		String m3u8Path = m3u8.getPath();

		if (m3u8.exists() && !m3u8.delete()) {
			logger.warn("[HLS] failed to delete old m3u8 id={} path={}", id, m3u8.getPath());
		}
		File[] oldSegs = hlsDir.listFiles((dir, name) -> name != null && (name.endsWith(".ts") || name.endsWith(".m4s") || name.endsWith(".tmp")));
		if (oldSegs != null) {
			for (File seg : oldSegs) {
				if (seg != null && seg.exists() && !seg.delete()) {
					logger.warn("[HLS] failed to delete old segment id={} path={}", id, seg.getPath());
				}
			}
		}

		String cmdEncode = "ffmpeg -y -i \"" + input.getPath() + "\" -map 0:v:0 -map 0:a? -c:v libx264 -profile:v main -level 4.0 "
				+ "-pix_fmt yuv420p -preset veryfast -crf 23 -g 48 -keyint_min 48 -sc_threshold 0 -c:a aac -b:a 128k "
				+ "-ar 48000 -ac 2 -hls_time " + Math.max(2, Global.hlsSegmentSeconds)
				+ " -hls_list_size 0 -hls_playlist_type vod -hls_flags " + HLS_FLAGS
				+ " -hls_segment_filename \"" + tsPattern + "\" \"" + m3u8Path + "\"";
		logger.info("[HLS] transcode start id={} mode=compat-encode", id);
		CommandUtil.command(cmdEncode);
		if (m3u8.isFile() && m3u8.length() > 0) {
			logger.info("[HLS] transcode success id={} by compat-encode", id);
			lastSuccessAt = System.currentTimeMillis();
			lastError = "";
		} else {
			logger.warn("[HLS] transcode failed id={}", id);
			lastFailAt = System.currentTimeMillis();
			lastError = "transcode failed for video id=" + id;
		}
		runningVideoId = null;
	}

	private boolean isInIdleWindow(String windowsRaw) {
		if (windowsRaw == null || windowsRaw.trim().isEmpty()) {
			return false;
		}
		LocalTime now = LocalTime.now();
		String[] windows = windowsRaw.split(",");
		for (String win : windows) {
			String w = win == null ? "" : win.trim();
			if (w.isEmpty() || !w.contains("-")) {
				continue;
			}
			String[] range = w.split("-");
			if (range.length != 2) {
				continue;
			}
			try {
				LocalTime start = LocalTime.parse(range[0].trim(), TIME_FMT);
				LocalTime end = LocalTime.parse(range[1].trim(), TIME_FMT);
				if (start.equals(end)) {
					return true;
				}
				if (start.isBefore(end)) {
					if (!now.isBefore(start) && now.isBefore(end)) {
						return true;
					}
				} else {
					if (!now.isBefore(start) || now.isBefore(end)) {
						return true;
					}
				}
			} catch (Exception e) {
				logger.warn("[HLS] invalid idle window piece={}", w);
			}
		}
		return false;
	}
}
