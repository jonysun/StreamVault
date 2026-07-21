package com.flower.spirit.service;

import org.springframework.stereotype.Service;

import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.utils.sendNotify;

@Service
public class WorkNotificationService {

	public void notifyCompleted(WorkMetadata metadata) {
		if (metadata == null) {
			return;
		}
		String title = metadata.getTitle() == null ? metadata.getWorkId() : metadata.getTitle();
		String source = metadata.getSourceUrl() == null ? metadata.getOriginalAddress() : metadata.getSourceUrl();
		String platform = metadata.getPlatformDisplayName() == null
				? metadata.getPlatformKey() : metadata.getPlatformDisplayName();
		sendNotify.sendNotifyData(title, source, platform);
	}
}
