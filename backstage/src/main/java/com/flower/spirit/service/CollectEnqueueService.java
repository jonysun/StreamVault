package com.flower.spirit.service;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.dao.CollectdDataDao;
import com.flower.spirit.entity.CollectDataEntity;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.service.transaction.CollectQueueTransaction;

@Service
public class CollectEnqueueService {

	private final CollectdDataDao collectdDataDao;
	private final CollectQueueTransaction transaction;
	private final DatabaseWriteExecutor databaseWriteExecutor;
	private final RuntimeControlService runtimeControlService;
	private final int maxAttempts;

	public CollectEnqueueService(CollectdDataDao collectdDataDao, CollectQueueTransaction transaction,
			DatabaseWriteExecutor databaseWriteExecutor, RuntimeControlService runtimeControlService,
			@Value("${streamvault.collect-queue.max-attempts:3}") int maxAttempts) {
		this.collectdDataDao = collectdDataDao;
		this.transaction = transaction;
		this.databaseWriteExecutor = databaseWriteExecutor;
		this.runtimeControlService = runtimeControlService;
		this.maxAttempts = Math.max(1, maxAttempts);
	}

	public CollectEnqueueResult enqueueManual(int taskId) {
		return enqueue(taskId, CollectTriggerType.MANUAL, Instant.now(), 0);
	}

	public CollectEnqueueResult enqueueScheduled(int taskId, Instant fireTime) {
		return enqueue(taskId, CollectTriggerType.SCHEDULED, fireTime == null ? Instant.now() : fireTime, 100);
	}

	public CollectEnqueueResult enqueueAudit(int taskId) {
		return enqueue(taskId, CollectTriggerType.AUDIT, Instant.now(), 10);
	}

	public CollectEnqueueResult enqueueSnapshotRefresh(int taskId) {
		return enqueue(taskId, CollectTriggerType.SCHEDULED, Instant.now(), 20);
	}

	private CollectEnqueueResult enqueue(int taskId, CollectTriggerType triggerType, Instant availableAt,
			int priority) {
		CollectDataEntity task = collectdDataDao.findById(taskId)
				.orElseThrow(() -> new IllegalArgumentException("收藏任务不存在: " + taskId));
		String platformKey = PlatformCatalog.canonicalKey(null, task.getPlatform());
		if (!"douyin".equals(platformKey)) {
			return CollectEnqueueResult.unsupported("当前持久化抓取队列仅支持抖音收藏任务");
		}
		if ("N".equalsIgnoreCase(task.getTaskenabled())) {
			return databaseWriteExecutor.execute("collect-enqueue-skipped-disabled", () -> transaction.recordSkipped(taskId, triggerType,
					requestedLimit(task), "收藏任务已停用", Instant.now()));
		}
		PauseDecision pause = runtimeControlService.mayRun(TaskCategory.COLLECT_FETCH);
		if (!pause.allowed()) {
			return databaseWriteExecutor.execute("collect-enqueue-skipped-paused", () -> transaction.recordSkipped(taskId, triggerType,
					requestedLimit(task), "收藏/爬取任务已暂停: " + pause.controlKey(), Instant.now()));
		}
		return databaseWriteExecutor.execute("collect-enqueue", () -> transaction.enqueue(taskId, triggerType, requestedLimit(task),
				availableAt, priority, maxAttempts));
	}

	private Integer requestedLimit(CollectDataEntity task) {
		return task.getMaxcur() != null ? task.getMaxcur() : task.getOmaxcur();
	}
}
