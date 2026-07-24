package com.flower.spirit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.entity.AuthorProfileEntity;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.utils.AuthorIdentityUtil;
import com.flower.spirit.utils.DouUtil;

@Service
public class DouyinAuthorProfileRefreshService {

	private static final Logger logger = LoggerFactory.getLogger(DouyinAuthorProfileRefreshService.class);

	private final AuthorProfileDao authorProfileDao;
	private final AuthorProfileService authorProfileService;

	public DouyinAuthorProfileRefreshService(AuthorProfileDao authorProfileDao,
			AuthorProfileService authorProfileService) {
		this.authorProfileDao = authorProfileDao;
		this.authorProfileService = authorProfileService;
	}

	public AuthorProfileService.AuthorProfileRefreshResult refresh(Integer authorProfileId) {
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

		JSONObject rawProfile;
		try {
			rawProfile = DouUtil.fetchUserProfile(canonicalUid);
		} catch (RuntimeException e) {
			logger.warn("[AuthorProfileRefresh] request failed profileId={} authorUid={}", authorProfileId,
					canonicalUid, e);
			throw new WorkMetadataValidationException("外部 profile 请求失败: " + safeMessage(e), e);
		}
		JSONObject profileUser = authorProfileService.extractProfileUser(rawProfile);
		if (profileUser == null) {
			logger.warn("[AuthorProfileRefresh] invalid response profileId={} authorUid={} responsePresent={}",
					authorProfileId, canonicalUid, rawProfile != null);
			throw new WorkMetadataValidationException("外部 profile API 返回空数据或响应结构异常");
		}
		String responseUid = AuthorIdentityUtil.canonicalAuthorUid("douyin", profileUser.getString("sec_uid"),
				profileUser.getString("sec_uid"));
		if (!canonicalUid.equals(responseUid)) {
			logger.warn("[AuthorProfileRefresh] uid mismatch profileId={} expected={} actual={}", authorProfileId,
					canonicalUid, responseUid);
			throw new WorkMetadataValidationException(responseUid == null
					? "外部 profile 响应缺少有效 sec_uid"
					: "外部 profile 返回作者 UID 不一致，已拒绝更新");
		}
		AuthorProfileService.AuthorProfileRefreshResult result = authorProfileService
				.applyExternalDouyinProfile(authorProfileId, profileUser);
		logger.info("[AuthorProfileRefresh] completed profileId={} authorUid={} authorFields={} videos={} graphics={}",
				authorProfileId, canonicalUid, result.authorFieldsUpdated(), result.videosUpdated(),
				result.graphicsUpdated());
		return result;
	}

	private String safeMessage(RuntimeException error) {
		String message = error == null ? null : error.getMessage();
		return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message.trim();
	}

	private String trimToNull(String value) {
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}
}
