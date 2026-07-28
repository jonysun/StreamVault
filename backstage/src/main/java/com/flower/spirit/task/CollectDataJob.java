package com.flower.spirit.task;

import java.util.Optional;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.flower.spirit.dao.CollectdDataDao;
import com.flower.spirit.entity.CollectDataEntity;
import com.flower.spirit.service.CollectEnqueueResult;
import com.flower.spirit.service.CollectEnqueueService;

/**
 * 收藏夹监控任务Job
 */
@Component
@DisallowConcurrentExecution
public class CollectDataJob implements Job {
    
    private static final Logger logger = LoggerFactory.getLogger(CollectDataJob.class);
    
    @Autowired
	private CollectEnqueueService collectEnqueueService;

	@Autowired
	private CollectdDataDao collectdDataDao;
    
    @Autowired
    private QuartzTaskService quartzTaskService;
    
    /**
     * 执行收藏夹监控任务
     * @param context 任务执行上下文
     */
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap dataMap = context.getJobDetail().getJobDataMap();
        Integer taskId = dataMap.getInt("taskId");
        String taskName = dataMap.getString("taskName");

        try {
			logger.info("收藏任务到期，准备入队：{}", taskName);
			Optional<CollectDataEntity> taskOpt = collectdDataDao.findById(taskId);
			if (!taskOpt.isPresent()) {
				logger.warn("任务不存在：{}", taskName);
				return;
			}
			CollectDataEntity collectDataEntity = taskOpt.get();
			CollectEnqueueResult result = collectEnqueueService.enqueueScheduled(taskId,
					context.getFireTime() == null ? null : context.getFireTime().toInstant());
			if (!"Y".equals(collectDataEntity.getMonitoring())) {
				quartzTaskService.removeTaskSchedule(taskId);
			}
			if (result.skippedUnsupported()) {
				logger.warn("收藏任务未进入持久队列 taskId={} taskName={} platform={} reason={}", taskId,
						taskName, collectDataEntity.getPlatform(), result.reason());
				return;
			}
			logger.info("收藏任务入队完成 taskId={} taskName={} runId={} jobId={} state={} inserted={}", taskId,
					taskName, result.runId(), result.jobId(), result.state(), result.inserted());
            
        } catch (Exception e) {
            logger.error("收藏夹任务执行失败：{}", taskName, e);
            throw new JobExecutionException("任务执行失败", e);
        }
    }
}
