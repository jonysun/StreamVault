package com.flower.spirit.service;

import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.utils.DouUtil;

@Service
public class DouyinProfileGateway {

	public JSONObject fetchProfileUser(String secUid) {
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
