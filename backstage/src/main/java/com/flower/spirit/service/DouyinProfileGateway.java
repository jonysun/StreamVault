package com.flower.spirit.service;

import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.utils.DouUtil;

@Service
public class DouyinProfileGateway {
	private final DouyinDataProviderService providerService;

	public DouyinProfileGateway(DouyinDataProviderService providerService) {
		this.providerService = providerService;
	}

	public JSONObject fetchProfileUser(String secUid) {
		if (providerService != null && providerService.isDtkOnly()) {
			return providerService.current().fetchAuthorProfile(secUid);
		}
		if (providerService != null && providerService.isAuto()) {
			try {
				JSONObject user = providerService.current().fetchAuthorProfile(secUid);
				if (user != null) return user;
			} catch (RuntimeException error) {
				if (!providerService.shouldFailover(error)) throw error;
			}
			return providerService.fetchDtkAuthorProfile(secUid);
		}
		JSONObject profile = DouUtil.fetchUserProfile(secUid);
		if (profile == null) {
			return null;
		}
		JSONObject user = profile.getJSONObject("user");
		if (user != null) {
			return user;
		}
		JSONObject data = profile.getJSONObject("data");
		if (data == null) {
			return null;
		}
		JSONObject dataUser = data.getJSONObject("user");
		return dataUser != null ? dataUser : data;
	}
}
