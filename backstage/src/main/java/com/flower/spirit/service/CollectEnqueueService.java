package com.flower.spirit.service;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.flower.spirit.dao.CollectdDataDao;
import com.flower.spirit.entity.CollectDataEntity;
import com.flower.spirit.service.transaction.CollectQueueTransaction;

@Service
public class CollectEnqueueService {

	private final CollectdDataDao collectdDataDao;
	private final CollectQueueTransaction transaction;
	private final SqliteWriteRetrier sqliteWriteRetrier;
	private final RuntimeControlService runtimeControlService;
	private final int maxAttempts;

	public CollectEnqueueService(CollectdDataDao collectdDataDao, CollectQueueTransaction transaction,
			SqliteWriteRetrier sqliteWriteRetrier, RuntimeControlService runtimeControlService,
			@Value("${streamvault.collect-queue.max-attempts:3}") int maxAttempts) {
		this.collectdDataDao = collectdDataDao;
		this.transaction = transaction;
		this.sqliteWriteRetrier = sqliteWriteRetrier;
		this.runtimeControlService = runtimeControlService;
		this.maxAttempts = Math.max(1, maxAttempts);
	}

	public CollectEnqueueResult enqueueManual(int taskId) {
		return enqueue(taskId, CollectTriggerType.MANUAL, Instant.now(), 0);
	}

	public CollectEnqueueResult enqueueScheduled(int taskId, Instant fireTime) {
		return enqueue(taskId, CollectTriggerType.SCHEDULED, fireTime == null ? Instant.now() : fireTime, 100);
	}

	private CollectEnqueueResult enqueue(int taskId, CollectTriggerType triggerType, Instant availableAt,
			int priority) {
		CollectDataEntity task = collectdDataDao.findById(taskId)
				.orElseThrow(() -> new IllegalArgumentException("收藏任务不存在: " + taskId));
		if ("N".equalsIgnoreCase(task.getTaskenabled())) {
			return sqliteWriteRetrier.execute(() -> transaction.recordSkipped(taskId, triggerType,
					requestedLimit(task), "收藏任务已停用", Instant.now()));
		}
		PauseDecision pause = runtimeControlService.mayRun(TaskCategory.COLLECT_FETCH);
		if (!pause.allowed()) {
			return sqliteWriteRetrier.execute(() -> transaction.recordSkipped(taskId, triggerType,
					requestedLimit(task), "收藏/爬取任务已暂停: " + pause.controlKey(), Instant.now()));
		}
		return sqliteWriteRetrier.execute(() -> transaction.enqueue(taskId, triggerType, requestedLimit(task),
				availableAt, priority, maxAttempts));
	}

	private Integer requestedLimit(CollectDataEntity task) {
		return task.getMaxcur() != null ? task.getMaxcur() : task.getOmaxcur();
	}
}
