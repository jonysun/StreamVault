package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson.JSONObject;

class AuthorProfileServiceTest {

	@Test
	void extractProfileUserReturnsNullForNonUserPayload() throws Exception {
		AuthorProfileService service = new AuthorProfileService();
		JSONObject payload = JSONObject.parseObject("{\"status_code\":1,\"message\":\"error\"}");

		JSONObject user = invokeExtractProfileUser(service, payload);

		assertThat(user).isNull();
	}

	@Test
	void extractProfileUserAcceptsDataUserPayload() throws Exception {
		AuthorProfileService service = new AuthorProfileService();
		JSONObject payload = JSONObject.parseObject("{\"data\":{\"user\":{\"sec_uid\":\"sec\",\"unique_id\":\"name\"}}}");

		JSONObject user = invokeExtractProfileUser(service, payload);

		assertThat(user.getString("sec_uid")).isEqualTo("sec");
		assertThat(user.getString("unique_id")).isEqualTo("name");
	}

	private JSONObject invokeExtractProfileUser(AuthorProfileService service, JSONObject payload) throws Exception {
		Method method = AuthorProfileService.class.getDeclaredMethod("extractProfileUser", JSONObject.class);
		method.setAccessible(true);
		return (JSONObject) method.invoke(service, payload);
	}
}
