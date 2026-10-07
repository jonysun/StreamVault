package com.flower.spirit.service;

import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;

@Service
public class DouyinProfileGateway {
	private final DouyinDataProviderService providerService;

	public DouyinProfileGateway(DouyinDataProviderService providerService) {
		this.providerService = providerService;
	}

	public JSONObject fetchProfileUser(String secUid) {
		return providerService.fetchAuthorProfile(secUid);
	}

	public JSONObject fetchProfileUserByUniqueId(String uniqueId) {
		return providerService.fetchAuthorProfileByUniqueId(uniqueId);
	}
}
