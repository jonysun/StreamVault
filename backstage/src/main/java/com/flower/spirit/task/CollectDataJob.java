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

import com.flower.spirit.config.Global;
import com.flower.spirit.entity.CollectDataEntity;
import com.flower.spirit.service.CollectDataService;

/**
 * 收藏夹监控任务Job
 */
@Component
@DisallowConcurrentExecution
public class CollectDataJob implements Job {
    
    private static final Logger logger = LoggerFactory.getLogger(CollectDataJob.class);
    
    @Autowired
    private CollectDataService collectDataService;
    
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
            if (Global.isCollectPaused()) {
                logger.info("收藏任务已暂停，跳过执行：{}", taskName);
                return;
            }
            logger.info("开始执行收藏夹任务：{}", taskName);
            
            // 获取任务详情
            Optional<CollectDataEntity> taskOpt = collectDataService.findById(taskId);
            if (!taskOpt.isPresent()) {
            	logger.warn("任务不存在：{}", taskName);
                return;
            }
            CollectDataEntity collectDataEntity = taskOpt.get();
            collectDataService.submitCollectData(collectDataEntity, "Y");
            if (!"Y".equals(collectDataEntity.getMonitoring())) {
                quartzTaskService.removeTaskSchedule(taskId);
            }
            logger.info("收藏夹任务执行完成：{}", taskName);
            
        } catch (Exception e) {
            logger.error("收藏夹任务执行失败：{}", taskName, e);
            throw new JobExecutionException("任务执行失败", e);
        }
    }
}
