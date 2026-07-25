package com.flower.spirit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.entity.AuthorProfileEntity;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.utils.AuthorIdentityUtil;

@Service
public class DouyinAuthorProfileRefreshService {

	private static final Logger logger = LoggerFactory.getLogger(DouyinAuthorProfileRefreshService.class);

	private final AuthorProfileDao authorProfileDao;
	private final AuthorEnrichmentQueueService authorEnrichmentQueueService;

	public DouyinAuthorProfileRefreshService(AuthorProfileDao authorProfileDao,
			AuthorEnrichmentQueueService authorEnrichmentQueueService) {
		this.authorProfileDao = authorProfileDao;
		this.authorEnrichmentQueueService = authorEnrichmentQueueService;
	}

	public AuthorEnrichmentEnqueueResult refresh(Integer authorProfileId) {
		if (authorProfileId == null) {
			throw new WorkMetadataValidationException("作者档案 ID 不能为空");
		}
		AuthorProfileEntity profile = authorProfileDao.findById(authorProfileId)
				.orElseThrow(() -> new WorkMetadataValidationException("作者档案不存在: " + authorProfileId));
		if (!AuthorIdentityUtil.isDouyinPlatform(profile.getPlatform())
				&& !"douyin".equalsIgnoreCase(trimToNull(profile.getPlatformkey()))) {
			throw new WorkMetadataValidationException("仅支持刷新抖音作者档案");
		}
		String canonicalUid = AuthorIdentityUtil.canonicalAuthorUid("douyin", profile.getAuthoruid(),
				profile.getAuthoruid());
		if (!AuthorIdentityUtil.isDouyinSecUid(canonicalUid)) {
			throw new WorkMetadataValidationException("作者缺少有效的抖音 sec_uid，请先执行作者修复");
		}

		try {
			AuthorEnrichmentEnqueueResult result = authorEnrichmentQueueService.enqueueManual(profile);
			logger.info("[AuthorProfileRefresh] queued profileId={} authorUid={} jobId={} state={} promoted={}",
					authorProfileId, canonicalUid, result.jobId(), result.state(), result.promoted());
			return result;
		} catch (RuntimeException e) {
			logger.warn("[AuthorProfileRefresh] enqueue failed profileId={} authorUid={}", authorProfileId,
					canonicalUid, e);
			throw new WorkMetadataValidationException("作者资料刷新入队失败: " + safeMessage(e), e);
		}
	}

	private String safeMessage(RuntimeException error) {
		String message = error == null ? null : error.getMessage();
		return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message.trim();
	}

	private String trimToNull(String value) {
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}
}
