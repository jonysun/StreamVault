package com.flower.spirit.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flower.spirit.config.Global;
import com.flower.spirit.service.BiliConfigService;
import com.flower.spirit.service.AuthorEnrichmentWorker;
import com.flower.spirit.service.AuthorEnrichmentQueueService;
import com.flower.spirit.service.CollectJobWorker;
import com.flower.spirit.service.CookiesConfigService;
import com.flower.spirit.service.FfmpegQueueService;
import com.flower.spirit.service.HlsTranscodeService;

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

	@Autowired
	private HlsTranscodeService hlsTranscodeService;

	@Autowired
	private AuthorEnrichmentWorker authorEnrichmentWorker;

	@Autowired
	private AuthorEnrichmentQueueService authorEnrichmentQueueService;

	@Autowired
	private CollectJobWorker collectJobWorker;
	
	
	@Scheduled(fixedDelay = 1000*5)
	public void taskCheckStatus() {
		if (Global.isDownloadPaused()) {
			return;
		}
		ffmpegQueueService.taskCheckStatus();
	}
	
	@Scheduled(fixedDelay = 1000*5)
	public void taskMergeTasks() {
		if (Global.isDownloadPaused()) {
			return;
		}
		ffmpegQueueService.taskMergeTasks();
	}

	@Scheduled(fixedDelay = 1000*8)
	public void hlsQueueTick() {
		if (Global.isHlsPaused()) {
			return;
		}
		hlsTranscodeService.processQueueTick(false);
	}

	@Scheduled(fixedDelayString = "${streamvault.author-enrichment.poll-delay-ms:15000}")
	public void authorEnrichmentTick() {
		if (Global.isCollectPaused()) {
			return;
		}
		authorEnrichmentWorker.processOne();
	}

	@Scheduled(fixedDelayString = "${streamvault.collect-queue.poll-delay-ms:5000}")
	public void collectQueueTick() {
		if (Global.isCollectPaused()) {
			return;
		}
		collectJobWorker.wakeUp();
	}

	@Scheduled(cron = "${streamvault.author-enrichment.reconcile-cron:0 30 3 * * ?}")
	public void reconcileMissingAuthorEnrichmentJobs() {
		if (Global.isCollectPaused()) {
			return;
		}
		try {
			int queued = authorEnrichmentQueueService.reconcileMissingWorkAuthors(200);
			if (queued > 0) {
				logger.info("[AuthorEnrichment] reconciliation queued missing authors count={}", queued);
			}
		} catch (RuntimeException error) {
			logger.error("[AuthorEnrichment] reconciliation scan failed", error);
		}
	}
	
	@Scheduled(cron = "0 0 9 * * ?")
	public void isNeedRefreshAndUpdate() {
		try {
			if (!Global.isCollectPaused()) {
				biliConfigService.isNeedRefreshAndUpdate();
			}
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
