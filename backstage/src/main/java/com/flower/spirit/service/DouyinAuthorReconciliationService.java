package com.flower.spirit.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.service.AuthorProfileService.WorkAuthorReconcileResult;

import jakarta.annotation.PreDestroy;

@Service
public class DouyinAuthorReconciliationService {

	private static final Logger logger = LoggerFactory.getLogger(DouyinAuthorReconciliationService.class);
	private static final List<String> DOUYIN_PLATFORMS = List.of("抖音", "douyin");
	private static final int BATCH_SIZE = 100;

	private final VideoDataDao videoDataDao;
	private final GraphicContentDao graphicContentDao;
	private final AuthorProfileDao authorProfileDao;
	private final AuthorProfileService authorProfileService;
	private final JdbcTemplate jdbcTemplate;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final ExecutorService executor = Executors.newSingleThreadExecutor(new ReconcileThreadFactory());
	private volatile ReconcileStatus status = ReconcileStatus.idle();

	public DouyinAuthorReconciliationService(VideoDataDao videoDataDao, GraphicContentDao graphicContentDao,
			AuthorProfileDao authorProfileDao, AuthorProfileService authorProfileService, JdbcTemplate jdbcTemplate) {
		this.videoDataDao = videoDataDao;
		this.graphicContentDao = graphicContentDao;
		this.authorProfileDao = authorProfileDao;
		this.authorProfileService = authorProfileService;
		this.jdbcTemplate = jdbcTemplate;
	}

	public AjaxEntity preview() {
		try {
			return new AjaxEntity(Global.ajax_success, "检查完成", loadPreview());
		} catch (Exception e) {
			logger.error("[DouyinAuthorRepair] preview failed", e);
			return new AjaxEntity(Global.ajax_uri_error, "检查失败: " + rootMessage(e), null);
		}
	}

	public AjaxEntity start() {
		if (!running.compareAndSet(false, true)) {
			return new AjaxEntity(Global.ajax_uri_error, "已有作者修复任务正在运行", status);
		}
		try {
			ReconcilePreview preview = loadPreview();
			if (!preview.safeToRun()) {
				running.set(false);
				status = ReconcileStatus.failed(preview, "数据库完整性检查未通过，未执行任何写入");
				return new AjaxEntity(Global.ajax_uri_error, status.message(), status);
			}
			status = ReconcileStatus.started(preview);
			executor.submit(() -> runRepair(preview));
			return new AjaxEntity(Global.ajax_success, "作者修复任务已启动", status);
		} catch (Exception e) {
			running.set(false);
			status = ReconcileStatus.failed(null, "启动失败: " + rootMessage(e));
			logger.error("[DouyinAuthorRepair] start failed", e);
			return new AjaxEntity(Global.ajax_uri_error, status.message(), status);
		}
	}

	public AjaxEntity getStatus() {
		return new AjaxEntity(Global.ajax_success, "状态获取成功", status);
	}

	private ReconcilePreview loadPreview() {
		String integrity = databaseIntegrity();
		if (!"ok".equalsIgnoreCase(integrity)) {
			return new ReconcilePreview(0, 0, 0, 0, 0, 0, integrity, false);
		}
		long videos = videoDataDao.countByVideoplatformIn(DOUYIN_PLATFORMS);
		long graphics = graphicContentDao.countByPlatformIn(DOUYIN_PLATFORMS);
		long videoCandidates = videoDataDao.countDouyinAuthorRepairCandidates(DOUYIN_PLATFORMS);
		long graphicCandidates = graphicContentDao.countDouyinAuthorRepairCandidates(DOUYIN_PLATFORMS);
		long profiles = authorProfileDao.countDouyinProfiles(DOUYIN_PLATFORMS);
		long legacyProfiles = authorProfileDao.countLegacyDouyinProfiles(DOUYIN_PLATFORMS);
		return new ReconcilePreview(videos, graphics, videoCandidates, graphicCandidates, profiles, legacyProfiles,
				integrity, true);
	}

	private String databaseIntegrity() {
		List<String> rows = jdbcTemplate.queryForList("PRAGMA quick_check(1)", String.class);
		return rows == null || rows.isEmpty() ? "未返回检查结果" : rows.get(0);
	}

	private void runRepair(ReconcilePreview preview) {
		Progress progress = new Progress(preview);
		Map<String, JSONObject> profileCache = new HashMap<>();
		try {
			processVideos(progress, profileCache);
			processGraphics(progress, profileCache);
			status = progress.snapshot("completed", "完成", "修复完成", Instant.now().toString());
			logger.info("[DouyinAuthorRepair] completed scannedVideos={} scannedGraphics={} updatedVideos={} "
					+ "updatedGraphics={} localResolved={} apiResolved={} mergedProfiles={} unresolved={}",
					progress.scannedVideos, progress.scannedGraphics, progress.updatedVideos, progress.updatedGraphics,
					progress.localResolved, progress.apiResolved, progress.mergedProfiles, progress.unresolved);
		} catch (Exception e) {
			status = progress.snapshot("failed", "失败", rootMessage(e), Instant.now().toString());
			logger.error("[DouyinAuthorRepair] failed phase={} lastVideoId={} lastGraphicId={}",
					status.phase(), status.lastVideoId(), status.lastGraphicId(), e);
		} finally {
			running.set(false);
		}
	}

	private void processVideos(Progress progress, Map<String, JSONObject> profileCache) {
		int lastId = 0;
		while (true) {
			List<VideoDataEntity> batch = videoDataDao.findByVideoplatformInAndIdGreaterThanOrderByIdAsc(
					DOUYIN_PLATFORMS, lastId, PageRequest.of(0, BATCH_SIZE));
			if (batch.isEmpty()) {
				return;
			}
			for (VideoDataEntity video : batch) {
				lastId = video.getId();
				progress.scannedVideos++;
				progress.lastVideoId = lastId;
				try {
					WorkAuthorReconcileResult result = authorProfileService.reconcileDouyinVideo(video, profileCache);
					progress.acceptVideo(result);
				} catch (Exception e) {
					progress.failedRows++;
					logger.error("[DouyinAuthorRepair] video row failed id={}", lastId, e);
				}
			}
			videoDataDao.flush();
			status = progress.snapshot("running", "视频", "正在修复视频作者字段", null);
		}
	}

	private void processGraphics(Progress progress, Map<String, JSONObject> profileCache) {
		int lastId = 0;
		while (true) {
			List<GraphicContentEntity> batch = graphicContentDao.findByPlatformInAndIdGreaterThanOrderByIdAsc(
					DOUYIN_PLATFORMS, lastId, PageRequest.of(0, BATCH_SIZE));
			if (batch.isEmpty()) {
				return;
			}
			for (GraphicContentEntity graphic : batch) {
				lastId = graphic.getId();
				progress.scannedGraphics++;
				progress.lastGraphicId = lastId;
				try {
					WorkAuthorReconcileResult result = authorProfileService.reconcileDouyinGraphic(graphic, profileCache);
					progress.acceptGraphic(result);
				} catch (Exception e) {
					progress.failedRows++;
					logger.error("[DouyinAuthorRepair] graphic row failed id={}", lastId, e);
				}
			}
			graphicContentDao.flush();
			status = progress.snapshot("running", "图文", "正在修复图文作者字段", null);
		}
	}

	@PreDestroy
	public void shutdown() {
		executor.shutdownNow();
	}

	private String rootMessage(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		String message = current.getMessage();
		return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
	}

	public record ReconcilePreview(long videos, long graphics, long videoCandidates, long graphicCandidates,
			long profiles, long legacyProfiles, String databaseIntegrity, boolean safeToRun) {
	}

	public record ReconcileStatus(String state, String phase, String startedAt, String finishedAt,
			long totalVideos, long totalGraphics, long candidateVideos, long candidateGraphics,
			long scannedVideos, long scannedGraphics, long updatedVideos, long updatedGraphics,
			long localResolved, long apiResolved, long mergedProfiles, long unresolved,
			long failedRows, int lastVideoId, int lastGraphicId, String message) {

		static ReconcileStatus idle() {
			return new ReconcileStatus("idle", "等待", null, null, 0, 0, 0, 0,
					0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "尚未执行");
		}

		static ReconcileStatus started(ReconcilePreview preview) {
			return new ReconcileStatus("running", "准备", Instant.now().toString(), null,
					preview.videos(), preview.graphics(), preview.videoCandidates(), preview.graphicCandidates(),
					0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "任务已启动");
		}

		static ReconcileStatus failed(ReconcilePreview preview, String message) {
			long videos = preview == null ? 0 : preview.videos();
			long graphics = preview == null ? 0 : preview.graphics();
			long candidateVideos = preview == null ? 0 : preview.videoCandidates();
			long candidateGraphics = preview == null ? 0 : preview.graphicCandidates();
			return new ReconcileStatus("failed", "失败", null, Instant.now().toString(), videos, graphics,
					candidateVideos, candidateGraphics, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, message);
		}
	}

	private static final class Progress {
		private final ReconcilePreview preview;
		private final String startedAt = Instant.now().toString();
		private long scannedVideos;
		private long scannedGraphics;
		private long updatedVideos;
		private long updatedGraphics;
		private long localResolved;
		private long apiResolved;
		private long mergedProfiles;
		private long unresolved;
		private long failedRows;
		private int lastVideoId;
		private int lastGraphicId;

		private Progress(ReconcilePreview preview) {
			this.preview = preview;
		}

		private void acceptVideo(WorkAuthorReconcileResult result) {
			if (result.updated()) updatedVideos++;
			accept(result);
		}

		private void acceptGraphic(WorkAuthorReconcileResult result) {
			if (result.updated()) updatedGraphics++;
			accept(result);
		}

		private void accept(WorkAuthorReconcileResult result) {
			if (result.localResolved()) localResolved++;
			if (result.apiResolved()) apiResolved++;
			if (result.merged()) mergedProfiles++;
			if (result.unresolved()) unresolved++;
		}

		private ReconcileStatus snapshot(String state, String phase, String message, String finishedAt) {
			return new ReconcileStatus(state, phase, startedAt, finishedAt, preview.videos(), preview.graphics(),
					preview.videoCandidates(), preview.graphicCandidates(), scannedVideos, scannedGraphics,
					updatedVideos, updatedGraphics, localResolved, apiResolved, mergedProfiles, unresolved,
					failedRows, lastVideoId, lastGraphicId, message);
		}
	}

	private static final class ReconcileThreadFactory implements ThreadFactory {
		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "douyin-author-reconcile");
			thread.setDaemon(true);
			return thread;
		}
	}
}
