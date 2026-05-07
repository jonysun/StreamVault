package com.flower.spirit.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flower.spirit.service.BiliConfigService;
import com.flower.spirit.service.CookiesConfigService;
import com.flower.spirit.service.FfmpegQueueService;

@Configuration
@Component
public class TaskService {

	private static final Logger logger = LoggerFactory.getLogger(TaskService.class);
	
	@Autowired
	private FfmpegQueueService ffmpegQueueService;
	
	@Autowired 
	private BiliConfigService biliConfigService;
	
	@Autowired
	private CookiesConfigService cookiesConfigService;
	
	
	@Scheduled(fixedDelay = 1000*5)
	public void taskCheckStatus() {
		ffmpegQueueService.taskCheckStatus();
	}
	
	@Scheduled(fixedDelay = 1000*5)
	public void taskMergeTasks() {
		ffmpegQueueService.taskMergeTasks();
	}
	
	@Scheduled(cron = "0 0 9 * * ?")
	public void isNeedRefreshAndUpdate() {
		try {
			biliConfigService.isNeedRefreshAndUpdate();
		} catch (Exception e) {
			logger.error("[TaskService] bili refresh task failed", e);
		}
		try {
			cookiesConfigService.checkCookieStatus();
		} catch (Exception e) {
			logger.error("[TaskService] cookies check task failed", e);
		}
	}

}
