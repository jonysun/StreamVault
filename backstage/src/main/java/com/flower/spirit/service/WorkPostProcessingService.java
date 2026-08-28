package com.flower.spirit.service;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.service.WorkPersistenceService.PersistenceResult;

@Service
public class WorkPostProcessingService {
	private static final Logger logger = LoggerFactory.getLogger(WorkPostProcessingService.class);

	private final HlsTranscodeService hlsTranscodeService;
	private final ProcessHistoryService processHistoryService;
	private final WorkNotificationService notificationService;

	public WorkPostProcessingService(HlsTranscodeService hlsTranscodeService,
			ProcessHistoryService processHistoryService, WorkNotificationService notificationService) {
		this.hlsTranscodeService = hlsTranscodeService;
		this.processHistoryService = processHistoryService;
		this.notificationService = notificationService;
	}

	public void complete(Integer historyId, WorkMetadata metadata, PersistenceResult persistenceResult) {
		complete(historyId, metadata, persistenceResult, true);
	}

	public void complete(Integer historyId, WorkMetadata metadata, PersistenceResult persistenceResult,
			boolean completeHistory) {
		if (persistenceResult != null && persistenceResult.contentType() == WorkContentType.VIDEO
				&& persistenceResult.id() != null) {
			boolean queued = hlsTranscodeService.enqueueVideo(persistenceResult.id());
			if (!queued) {
				logger.warn("[HLS] enqueue skipped videoId={} after download completion; inspect queue state and HLS pause/configuration",
						persistenceResult.id());
			}
		}
		notificationService.notifyCompleted(metadata);
		if (completeHistory) processHistoryService.completePlatformProcess(historyId);
	}
}
