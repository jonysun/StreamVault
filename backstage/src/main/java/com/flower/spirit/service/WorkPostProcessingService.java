package com.flower.spirit.service;

import org.springframework.stereotype.Service;

import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.service.WorkPersistenceService.PersistenceResult;

@Service
public class WorkPostProcessingService {

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
			hlsTranscodeService.enqueueVideo(persistenceResult.id());
		}
		notificationService.notifyCompleted(metadata);
		if (completeHistory) processHistoryService.completePlatformProcess(historyId);
	}
}
