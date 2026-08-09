package com.flower.spirit.service;

import java.time.Instant;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.entity.ProcessHistoryEntity;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.service.transaction.DirectDownloadQueueTransaction;

@Service
public class DirectDownloadQueueService {

	private final DirectDownloadQueueTransaction transaction;
	private final DatabaseWriteExecutor databaseWriteExecutor;
	private final ProcessHistoryService processHistoryService;
	private final PlatformResolver platformResolver;
	private final int maxAttempts;

	public DirectDownloadQueueService(DirectDownloadQueueTransaction transaction,
			DatabaseWriteExecutor databaseWriteExecutor, ProcessHistoryService processHistoryService,
			PlatformResolver platformResolver,
			@Value("${streamvault.direct-download.max-attempts:3}") int maxAttempts) {
		this.transaction = transaction;
		this.databaseWriteExecutor = databaseWriteExecutor;
		this.processHistoryService = processHistoryService;
		this.platformResolver = platformResolver;
		this.maxAttempts = Math.max(1, Math.min(maxAttempts, 10));
	}

	public DirectDownloadEnqueueResult enqueue(String sourceUrl, String sourceType, String title, String author,
			String batchId) {
		String input = StringUtils.trimToNull(sourceUrl);
		if (input == null || input.length() < 5) throw new IllegalArgumentException("Download URL is required");
		PlatformResolver.Resolution resolution = platformResolver.resolve(input).orElse(null);
		String platformKey = resolution == null ? "generic" : resolution.platform().getKey();
		String platformName = resolution == null ? "Other" : resolution.platform().getDisplayName();
		DirectDownloadSource normalizedSource = DirectDownloadSource.from(sourceType);
		ProcessHistoryEntity history = processHistoryService.beginPlatformProcess(input, platformName, "QUEUED");
		Integer historyId = history == null ? null : history.getId();
		DirectDownloadEnqueueResult result = databaseWriteExecutor.execute("direct-download-enqueue",
				() -> transaction.enqueue(input, platformKey, platformName, StringUtils.trimToNull(title),
						StringUtils.trimToNull(author), normalizedSource, StringUtils.trimToNull(batchId), historyId,
						Instant.now(), maxAttempts));
		if (!result.created() && historyId != null) {
			processHistoryService.completeProcess(historyId, "Duplicate submission; an active download is already queued");
		}
		return result;
	}

	public boolean retry(long jobId) {
		return databaseWriteExecutor.execute("direct-download-retry",
				() -> transaction.requeue(jobId, Instant.now()));
	}
}
