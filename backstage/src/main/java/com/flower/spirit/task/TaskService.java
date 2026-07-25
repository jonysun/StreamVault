package com.flower.spirit.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flower.spirit.service.BiliConfigService;
import com.flower.spirit.service.AuthorEnrichmentWorker;
import com.flower.spirit.service.AuthorEnrichmentQueueService;
import com.flower.spirit.service.CollectJobWorker;
import com.flower.spirit.service.CookiesConfigService;
import com.flower.spirit.service.FfmpegQueueService;
import com.flower.spirit.service.HlsTranscodeService;
import com.flower.spirit.service.RuntimeControlService;
import com.flower.spirit.service.TaskCategory;

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

	@Autowired
	private RuntimeControlService runtimeControlService;
	
	
	@Scheduled(fixedDelay = 1000*5)
	public void taskCheckStatus() {
		if (!runtimeControlService.mayRun(TaskCategory.MEDIA_DOWNLOAD).allowed()) {
			return;
		}
		ffmpegQueueService.taskCheckStatus();
	}
	
	@Scheduled(fixedDelay = 1000*5)
	public void taskMergeTasks() {
		if (!runtimeControlService.mayRun(TaskCategory.MEDIA_DOWNLOAD).allowed()) {
			return;
		}
		ffmpegQueueService.taskMergeTasks();
	}

	@Scheduled(fixedDelay = 1000*8)
	public void hlsQueueTick() {
		if (!runtimeControlService.mayRun(TaskCategory.HLS_TRANSCODE).allowed()) {
			return;
		}
		hlsTranscodeService.processQueueTick(false);
	}

	@Scheduled(fixedDelayString = "${streamvault.author-enrichment.poll-delay-ms:15000}")
	public void authorEnrichmentTick() {
		if (!runtimeControlService.mayRun(TaskCategory.COLLECT_FETCH).allowed()) {
			return;
		}
		authorEnrichmentWorker.processOne();
	}

	@Scheduled(fixedDelayString = "${streamvault.collect-queue.poll-delay-ms:5000}")
	public void collectQueueTick() {
		if (!runtimeControlService.mayRun(TaskCategory.COLLECT_FETCH).allowed()
				|| !runtimeControlService.mayRun(TaskCategory.MEDIA_DOWNLOAD).allowed()) {
			return;
		}
		collectJobWorker.wakeUp();
	}

	@Scheduled(cron = "${streamvault.author-enrichment.reconcile-cron:0 30 3 * * ?}")
	public void reconcileMissingAuthorEnrichmentJobs() {
		if (!runtimeControlService.mayRun(TaskCategory.COLLECT_FETCH).allowed()) {
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
			if (runtimeControlService.mayRun(TaskCategory.COLLECT_FETCH).allowed()) {
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
