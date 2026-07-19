package com.flower.spirit.task;

import java.util.List;
import java.util.Set;

import jakarta.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.flower.spirit.config.Global;
import com.flower.spirit.dao.CollectdDataDao;
import com.flower.spirit.entity.CollectDataEntity;
import com.flower.spirit.entity.ConfigEntity;
import com.flower.spirit.service.ConfigService;

/**
 * Quartz任务调度管理服务
 */
@Service
public class QuartzTaskService {

    private static final Logger logger = LoggerFactory.getLogger(QuartzTaskService.class);

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private ConfigService configService;

    @Autowired
    private CollectdDataDao collectdDataDao;

    /**
     * 启动时初始化所有监控任务
     */
    @PostConstruct
    public void initAllTasks() {
        try {
            logger.info("开始初始化所有监控任务...");
            refreshAllTasks();
            logger.info("监控任务初始化完成");
        } catch (Exception e) {
            logger.error("初始化监控任务失败", e);
        }
    }

    /**
     * 添加或更新收藏夹任务
     * 
     * @param task 收藏夹任务实体
     */
    public void scheduleTask(CollectDataEntity task) {
        try {
            if ("N".equalsIgnoreCase(task.getTaskenabled())) {
                logger.info("任务已暂停，跳过调度注册：{}", task.getId());
                return;
            }
            String jobName = "job-" + task.getId();
            String groupName = "collect";
            // 获取cron表达式
            String cron = getCronExpression(task);

            // 删除已存在的任务
            JobKey jobKey = JobKey.jobKey(jobName, groupName);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
            }

            // 创建新任务
            JobDetail jobDetail = JobBuilder.newJob(CollectDataJob.class)
                    .withIdentity(jobName, groupName)
                    .usingJobData("taskId", task.getId())
                    .usingJobData("taskName", task.getTaskname())
                    .build();

            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("trigger-" + task.getId(), groupName)
                    .withSchedule(CronScheduleBuilder.cronSchedule(cron))
                    .build();

            scheduler.scheduleJob(jobDetail, trigger);
        } catch (Exception e) {
            logger.error("调度任务失败：{}", task.getTaskname(), e);
        }
    }

    public boolean isTaskExists(Integer taskId) {
        try {
            JobKey jobKey = JobKey.jobKey("job-" + taskId, "collect");
            return scheduler.checkExists(jobKey);
        } catch (Exception e) {
            logger.error("检查任务是否存在失败：{}", taskId, e);
            return false;
        }
    }
    
    /**
     * 手动触发任务执行一次
     */
    public boolean triggerTask(Integer taskId) {
        try {
            if (Global.isCollectPaused()) {
                logger.info("收藏任务已暂停，跳过手动触发：{}", taskId);
                return false;
            }
            JobKey jobKey = JobKey.jobKey("job-" + taskId, "collect");
            if (!scheduler.checkExists(jobKey)) {
                logger.warn("任务不存在，无法手动触发：{}", taskId);
                return false;
            }
            scheduler.triggerJob(jobKey);
            return true;
        } catch (Exception e) {
            logger.error("手动触发任务失败：{}", taskId, e);
            return false;
        }
    }

    
    /**
     * 删除任务
     * 
     * @param taskId 任务ID
     * @return 是否删除成功
     */
    public boolean deleteTask(Integer taskId) {
        try {
            JobKey jobKey = JobKey.jobKey("job-" + taskId, "collect");

            // 检查是否正在运行
            if (isTaskRunning(taskId)) {
                logger.warn("任务正在运行，无法删除：{}", taskId);
                return false;
            }

            boolean deleted = scheduler.deleteJob(jobKey);
            if (deleted) {
                logger.info("成功删除任务：{}", taskId);
            }
            return deleted;

        } catch (Exception e) {
            logger.error("删除任务失败：{}", taskId, e);
            return false;
        }
    }

    
    /**
     * 强制删除任务（只删除调度信息，不中断当前执行）
     *
     * @param taskId 任务ID
     */
    public void removeTaskSchedule(Integer taskId) {
        try {
            JobKey jobKey = JobKey.jobKey("job-" + taskId, "collect");
            // 直接删除 Job，会同时删除其绑定的所有 Trigger
            scheduler.deleteJob(jobKey);
            logger.warn("单次调度删除任务：{}", taskId);
        } catch (Exception e) {
            logger.error("强制删除任务失败：{}", taskId, e);
        }
    }

    /**
     * 强制删除任务
     * 
     * @param taskId 任务ID
     */
    public void forceDeleteTask(Integer taskId) {
        try {
            JobKey jobKey = JobKey.jobKey("job-" + taskId, "collect");
            scheduler.interrupt(jobKey);
            scheduler.deleteJob(jobKey);
            logger.warn("强制删除任务：{}", taskId);
        } catch (Exception e) {
            logger.error("强制删除任务失败：{}", taskId, e);
        }
    }

    /**
     * 检查任务是否正在运行
     * 
     * @param taskId 任务ID
     * @return 是否正在运行
     */
    public boolean isTaskRunning(Integer taskId) {
        try {
            JobKey jobKey = JobKey.jobKey("job-" + taskId, "collect");
            return scheduler.getCurrentlyExecutingJobs().stream()
                    .anyMatch(ctx -> ctx.getJobDetail().getKey().equals(jobKey));
        } catch (Exception e) {
            logger.error("检查任务运行状态失败：{}", taskId, e);
            return false;
        }
    }

    /**
     * 获取Quartz调度器实例
     * 
     * @return Scheduler实例
     */
    public Scheduler getScheduler() {
        return scheduler;
    }

    /**
     * 获取cron表达式（兼容老数据）
     * 
     * @param task 任务实体
     * @return cron表达式
     */
    private String getCronExpression(CollectDataEntity task) {
        // 优先使用独立cron
        if (StringUtils.isNotBlank(task.getTaskcron())) {
            return task.getTaskcron();
        }
        try {
            ConfigEntity config = configService.getData();
            if (config != null && StringUtils.isNotBlank(config.getTaskcron()) && CronExpression.isValidExpression(config.getTaskcron())) {
                return config.getTaskcron();
            }
        } catch (Exception e) {
            logger.warn("获取全局cron失败", e);
        }
        return "0 0 0/3 * * ?";
    }

    /**
     * 刷新所有监控任务
     */
    public void refreshAllTasks() {
        try {
            Set<JobKey> jobKeys = scheduler.getJobKeys(GroupMatcher.groupEquals("collect"));
            for (JobKey jobKey : jobKeys) {
                scheduler.deleteJob(jobKey);
            }
            List<CollectDataEntity> tasks = collectdDataDao.findByMonitoring("Y");
            for (CollectDataEntity task : tasks) {
                // 兼容旧数据：taskenabled为空视为启用
                if ("N".equalsIgnoreCase(task.getTaskenabled())) {
                    continue;
                }
                scheduleTask(task);
            }
            logger.info("刷新任务完成，共{}个", tasks.size());

        } catch (Exception e) {
            logger.error("刷新任务失败", e);
        }
    }
}
