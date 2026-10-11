package com.flower.spirit.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.CollectdDataDao;
import com.flower.spirit.dao.DtkAdminSyncJobDao;
import com.flower.spirit.entity.CollectDataEntity;
import com.flower.spirit.entity.ConfigEntity;
import com.flower.spirit.entity.DtkAdminSyncJobEntity;
import com.flower.spirit.platform.PlatformCatalog;

import jakarta.annotation.PreDestroy;

@Service
public class DtkAdminWatchlistService {
	private static final Logger logger = LoggerFactory.getLogger(DtkAdminWatchlistService.class);
	private final CollectdDataDao collectdDataDao;
	private final DtkAdminSyncJobDao jobDao;
	private final DtkAdminClient client;
	private final ConfigService configService;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "dtk-admin-watchlist-worker");
		thread.setDaemon(true);
		return thread;
	});
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final Map<String, AuthorTarget> pendingAutoTargets = new LinkedHashMap<>();
	private String activeAutoKey;

	public DtkAdminWatchlistService(CollectdDataDao collectdDataDao, DtkAdminSyncJobDao jobDao,
			DtkAdminClient client, ConfigService configService) {
		this.collectdDataDao = collectdDataDao;
		this.jobDao = jobDao;
		this.client = client;
		this.configService = configService;
	}

	public AjaxEntity start(String mode) {
		String normalizedMode = "FULL".equalsIgnoreCase(mode) ? "FULL" : "INCREMENTAL";
		DtkAdminSyncJobEntity job;
		synchronized (this) {
			if (running.get()) {
				return new AjaxEntity(Global.ajax_uri_error, "已有 DTK 管理同步任务正在运行", jobDao.findFirstByOrderByCreatedAtDesc().orElse(null));
			}
			running.set(true);
			job = newJob(normalizedMode);
			jobDao.save(job);
		}
		executor.submit(() -> run(job));
		return new AjaxEntity(Global.ajax_success, "DTK 管理同步任务已启动", job);
	}

	public void enqueueNewAuthor(CollectDataEntity task) {
		ConfigEntity config = configService.getData();
		if (!"1".equals(config.getDtkAdminWatchlistSyncEnabled()) || task == null) return;
		AuthorTarget target = targetOf(task);
		if (target == null) return;
		DtkAdminSyncJobEntity job = null;
		synchronized (this) {
			if (target.key().equals(activeAutoKey)) return;
			if (running.get()) {
				pendingAutoTargets.putIfAbsent(target.key(), target);
				logger.info("[DtkAdmin] auto sync queued platform={} target={} pending={}", target.platform(), mask(target.targetId()), pendingAutoTargets.size());
				return;
			}
			running.set(true);
			activeAutoKey = target.key();
			job = newJob("AUTO");
			job.setScannedCount(1);
			jobDao.save(job);
		}
		DtkAdminSyncJobEntity submittedJob = job;
		String label = task.getTaskname();
		executor.submit(() -> runAuto(submittedJob, target, label));
	}

	public AjaxEntity status(String jobId) {
		if (jobId == null || jobId.isBlank()) return new AjaxEntity(Global.ajax_uri_error, "同步任务 ID 不能为空", null);
		return jobDao.findById(jobId).map(job -> new AjaxEntity(Global.ajax_success, "状态获取成功", job))
				.orElseGet(() -> new AjaxEntity(Global.ajax_uri_error, "同步任务不存在", null));
	}

	public AjaxEntity latest() {
		return new AjaxEntity(Global.ajax_success, "状态获取成功", jobDao.findFirstByOrderByCreatedAtDesc().orElse(null));
	}

	private void run(DtkAdminSyncJobEntity job) {
		try {
			begin(job);
			Map<String, AuthorTarget> local = loadLocalTargets(job);
			if ("FULL".equals(job.getMode()) && job.getSkippedCount() > 0) {
				job.setErrorDomain("TASK_CONFIGURATION");
				job.setErrorCode("DTK_ADMIN_LOCAL_SOURCE_UNRESOLVED");
				job.setErrorMessage("存在无法解析的本地收藏来源，已禁止完全同步删除");
				finish(job, "FAILED");
				return;
			}
			Map<String, List<DtkAdminClient.WatchEntry>> remote = loadRemote(job, local.keySet());
			if (remote == null) return;
			for (AuthorTarget target : local.values()) {
				if (remote.containsKey(target.key())) continue;
				try {
					client.add(target.platform(), target.targetId(), target.label());
					job.setAddedCount(job.getAddedCount() + 1);
				} catch (DtkAdminClient.DtkAdminException e) {
					recordFailure(job, e);
				}
				jobDao.save(job);
			}
			if ("FULL".equals(job.getMode())) {
				for (Map.Entry<String, List<DtkAdminClient.WatchEntry>> entry : remote.entrySet()) {
					List<DtkAdminClient.WatchEntry> entries = entry.getValue();
					int keep = local.containsKey(entry.getKey()) ? 1 : 0;
					for (int index = keep; index < entries.size(); index++) {
						try {
							client.delete(entries.get(index).id());
							job.setDeletedCount(job.getDeletedCount() + 1);
						} catch (DtkAdminClient.DtkAdminException e) {
							recordFailure(job, e);
						}
						jobDao.save(job);
					}
				}
			}
			finish(job, job.getFailedCount() == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS");
		} catch (Exception e) {
			logger.error("[DtkAdmin] sync failed jobId={}", job.getId(), e);
			job.setErrorDomain("APPLICATION");
			job.setErrorCode("DTK_ADMIN_SYNC_FAILED");
			job.setErrorMessage(safeMessage(e));
			finish(job, "FAILED");
		} finally {
			releaseAndSchedulePendingAuto();
		}
	}

	private void runAuto(DtkAdminSyncJobEntity job, AuthorTarget target, String label) {
		try {
			begin(job);
			List<DtkAdminClient.WatchEntry> remoteEntries = client.listAll(target.platform());
			boolean exists = remoteEntries.stream().anyMatch(entry -> "author".equalsIgnoreCase(entry.kind())
					&& target.platform().equalsIgnoreCase(entry.platform())
					&& target.targetId().equals(entry.targetId()));
			if (!exists) {
				client.add(target.platform(), target.targetId(), label);
				job.setAddedCount(1);
			} else {
				job.setSkippedCount(1);
			}
			finish(job, "COMPLETED");
		} catch (DtkAdminClient.DtkAdminException e) {
			recordFailure(job, e);
			finish(job, "FAILED");
		} finally {
			releaseAndSchedulePendingAuto();
		}
	}

	private void releaseAndSchedulePendingAuto() {
		AuthorTarget target = null;
		DtkAdminSyncJobEntity job = null;
		synchronized (this) {
			activeAutoKey = null;
			if (!pendingAutoTargets.isEmpty()) {
				var iterator = pendingAutoTargets.entrySet().iterator();
				var next = iterator.next();
				target = next.getValue();
				iterator.remove();
				activeAutoKey = target.key();
				job = newJob("AUTO");
				job.setScannedCount(1);
				jobDao.save(job);
			} else {
				running.set(false);
			}
		}
		if (target != null && job != null) {
			logger.info("[DtkAdmin] starting queued auto sync platform={} target={}", target.platform(), mask(target.targetId()));
			DtkAdminSyncJobEntity queuedJob = job;
			AuthorTarget queuedTarget = target;
			executor.submit(() -> runAuto(queuedJob, queuedTarget, queuedTarget.label()));
		}
	}

	private Map<String, AuthorTarget> loadLocalTargets(DtkAdminSyncJobEntity job) {
		Map<String, AuthorTarget> result = new LinkedHashMap<>();
		for (CollectDataEntity task : collectdDataDao.findAll()) {
			String platform = PlatformCatalog.canonicalKey(null, task.getPlatform());
			if (!isSupportedPlatform(platform)) continue;
			AuthorTarget target = targetOf(task);
			if (target == null) {
				job.setSkippedCount(job.getSkippedCount() + 1);
				continue;
			}
			result.putIfAbsent(target.key(), target);
		}
		job.setScannedCount(result.size() + job.getSkippedCount());
		jobDao.save(job);
		return result;
	}

	private Map<String, List<DtkAdminClient.WatchEntry>> loadRemote(DtkAdminSyncJobEntity job, Set<String> localKeys) {
		Map<String, List<DtkAdminClient.WatchEntry>> result = new LinkedHashMap<>();
		Set<String> platforms = new LinkedHashSet<>();
		for (String key : localKeys) {
			int separator = key.indexOf('\u0000');
			if (separator > 0) platforms.add(key.substring(0, separator));
		}
		for (String platform : platforms) {
			try {
				for (DtkAdminClient.WatchEntry entry : client.listAll(platform)) {
					if (!"author".equalsIgnoreCase(entry.kind())) continue;
						result.computeIfAbsent(platform + "\u0000" + entry.targetId(), ignored -> new ArrayList<>()).add(entry);
				}
			} catch (DtkAdminClient.DtkAdminException e) {
				recordFailure(job, e);
				finish(job, "FAILED");
				return null;
			}
		}
		return result;
	}

	private AuthorTarget targetOf(CollectDataEntity task) {
		String platform = PlatformCatalog.canonicalKey(null, task.getPlatform());
		if (!isSupportedPlatform(platform)) return null;
		String address = task.getOriginaladdress() == null ? "" : task.getOriginaladdress().trim();
		String target = extractTarget(platform, address);
		return target == null ? null : new AuthorTarget(platform, target, task.getTaskname());
	}

	private boolean isSupportedPlatform(String platform) {
		return "douyin".equals(platform) || "tiktok".equals(platform);
	}

	private String extractTarget(String platform, String address) {
		if (address.isBlank()) return null;
		if ("douyin".equals(platform)) {
			for (String prefix : List.of("post", "like", "recommend")) {
				if (address.regionMatches(true, 0, prefix, 0, prefix.length())) return nonBlank(address.substring(prefix.length()));
			}
			if (address.regionMatches(true, 0, "fav-", 0, 4)) {
				int end = address.indexOf("-fav", 4);
				return end > 4 ? nonBlank(address.substring(4, end)) : null;
			}
		}
		if ("tiktok".equals(platform)) {
			for (String prefix : List.of("tiktok-post:", "tiktok:", "post:")) {
				if (address.regionMatches(true, 0, prefix, 0, prefix.length())) return nonBlank(address.substring(prefix.length()));
			}
		}
		return null;
	}

	private DtkAdminSyncJobEntity newJob(String mode) {
		DtkAdminSyncJobEntity job = new DtkAdminSyncJobEntity();
		job.setId(UUID.randomUUID().toString());
		job.setMode(mode);
		job.setStatus("QUEUED");
		job.setCreatedAt(new Date());
		return job;
	}
	private void begin(DtkAdminSyncJobEntity job) { job.setStatus("RUNNING"); job.setStartedAt(new Date()); jobDao.save(job); }
	private void finish(DtkAdminSyncJobEntity job, String status) { job.setStatus(status); job.setFinishedAt(new Date()); jobDao.save(job); }
	private void recordFailure(DtkAdminSyncJobEntity job, DtkAdminClient.DtkAdminException e) {
		job.setFailedCount(job.getFailedCount() + 1);
		job.setErrorDomain(e.getStatus() >= 500 ? "REMOTE_API" : "CONFIGURATION");
		job.setErrorCode(e.getErrorCode());
		job.setErrorMessage(safeMessage(e));
		job.setRequestId(e.getRequestId());
		logger.warn("[DtkAdmin] sync item failed jobId={} code={} requestId={} message={}", job.getId(), e.getErrorCode(), e.getRequestId(), safeMessage(e));
	}
	private static String safeMessage(Throwable error) { return error == null || error.getMessage() == null ? "未知错误" : error.getMessage().replaceAll("(?i)(api[-_ ]?key|authorization)[^,; ]*", "$1=<redacted>"); }
	private static String nonBlank(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
	private static String mask(String value) { return value == null || value.length() < 8 ? "***" : value.substring(0, 4) + "..." + value.substring(value.length() - 4); }

	private record AuthorTarget(String platform, String targetId, String label) {
		String key() { return platform + "\u0000" + targetId; }
	}

	@PreDestroy
	void shutdown() { executor.shutdownNow(); }
}
