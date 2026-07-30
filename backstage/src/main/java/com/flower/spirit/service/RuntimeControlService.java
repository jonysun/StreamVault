package com.flower.spirit.service;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.config.Global;
import com.flower.spirit.service.RuntimeControlSnapshot.RuntimeControlValue;
import com.flower.spirit.service.transaction.RuntimeControlTransaction;

@Service
public class RuntimeControlService {

	private static final Logger logger = LoggerFactory.getLogger(RuntimeControlService.class);
	private final RuntimeControlTransaction transaction;
	private final DatabaseWriteExecutor databaseWriteExecutor;
	private volatile Map<String, RuntimeControlValue> values = Map.of();
	private volatile boolean initialized;

	public RuntimeControlService(RuntimeControlTransaction transaction, DatabaseWriteExecutor databaseWriteExecutor) {
		this.transaction = transaction;
		this.databaseWriteExecutor = databaseWriteExecutor;
	}

	@Order(100)
	@EventListener(ApplicationReadyEvent.class)
	public void initialize() {
		values = databaseWriteExecutor.execute("runtime-control-initialize",
				() -> transaction.initializeAndLoad(Instant.now()));
		applyToLegacyGlobals(values);
		initialized = true;
		logger.info("[RuntimeControl] loaded all={} collect={} download={} hls={}",
				enabled(RuntimeControlTransaction.PAUSE_ALL), enabled(RuntimeControlTransaction.PAUSE_COLLECT),
				enabled(RuntimeControlTransaction.PAUSE_DOWNLOAD), enabled(RuntimeControlTransaction.PAUSE_HLS));
	}

	public RuntimeControlSnapshot set(String scope, boolean paused, String updatedBy, String reason) {
		String key = keyForScope(scope);
		values = databaseWriteExecutor.execute("runtime-control-set",
				() -> transaction.set(key, paused, updatedBy, reason, Instant.now()));
		applyToLegacyGlobals(values);
		logger.warn("[RuntimeControl] changed key={} enabled={} updatedBy={} reason={}", key, paused, updatedBy,
				reason);
		return snapshot();
	}

	public PauseDecision mayRun(TaskCategory category) {
		if (!initialized) {
			return PauseDecision.paused("runtime-control.starting", "Runtime control is not loaded");
		}
		if (enabled(RuntimeControlTransaction.PAUSE_ALL)) {
			return PauseDecision.paused(RuntimeControlTransaction.PAUSE_ALL,
					reason(RuntimeControlTransaction.PAUSE_ALL));
		}
		String key = switch (category) {
		case COLLECT_FETCH -> RuntimeControlTransaction.PAUSE_COLLECT;
		case MEDIA_DOWNLOAD -> RuntimeControlTransaction.PAUSE_DOWNLOAD;
		case HLS_TRANSCODE -> RuntimeControlTransaction.PAUSE_HLS;
		};
		return enabled(key) ? PauseDecision.paused(key, reason(key)) : PauseDecision.permit();
	}

	public RuntimeControlSnapshot snapshot() {
		boolean all = enabled(RuntimeControlTransaction.PAUSE_ALL);
		boolean collect = enabled(RuntimeControlTransaction.PAUSE_COLLECT);
		boolean download = enabled(RuntimeControlTransaction.PAUSE_DOWNLOAD);
		boolean hls = enabled(RuntimeControlTransaction.PAUSE_HLS);
		return new RuntimeControlSnapshot(all, collect, download, hls, all || collect, all || download,
				all || hls, Map.copyOf(values));
	}

	public boolean isInitialized() {
		return initialized;
	}

	private boolean enabled(String key) {
		RuntimeControlValue value = values.get(key);
		return value != null && value.enabled();
	}

	private String reason(String key) {
		RuntimeControlValue value = values.get(key);
		return value == null ? null : value.reason();
	}

	private String keyForScope(String scope) {
		String normalized = scope == null ? "" : scope.trim().toLowerCase();
		return switch (normalized) {
		case "all" -> RuntimeControlTransaction.PAUSE_ALL;
		case "collect", "collect_fetch" -> RuntimeControlTransaction.PAUSE_COLLECT;
		case "download", "media_download" -> RuntimeControlTransaction.PAUSE_DOWNLOAD;
		case "hls", "hls_transcode" -> RuntimeControlTransaction.PAUSE_HLS;
		default -> throw new IllegalArgumentException("未知任务范围: " + scope);
		};
	}

	private void applyToLegacyGlobals(Map<String, RuntimeControlValue> current) {
		Global.backgroundTaskPauseAll = value(current, RuntimeControlTransaction.PAUSE_ALL);
		Global.backgroundTaskPauseCollect = value(current, RuntimeControlTransaction.PAUSE_COLLECT);
		Global.backgroundTaskPauseDownload = value(current, RuntimeControlTransaction.PAUSE_DOWNLOAD);
		Global.backgroundTaskPauseHls = value(current, RuntimeControlTransaction.PAUSE_HLS);
	}

	private boolean value(Map<String, RuntimeControlValue> current, String key) {
		RuntimeControlValue value = current.get(key);
		return value != null && value.enabled();
	}
}
