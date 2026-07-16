package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.dao.AuthorNameHistoryDao;
import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.entity.AuthorNameHistoryEntity;
import com.flower.spirit.entity.AuthorProfileEntity;

@ExtendWith(MockitoExtension.class)
class AuthorProfileServiceTest {

	@Mock
	private AuthorProfileDao authorProfileDao;

	@Mock
	private AuthorNameHistoryDao authorNameHistoryDao;

	@InjectMocks
	private AuthorProfileService service;

	@Test
	void preferDouyinAuthorUidUsesSecUidWhenAvailable() {
		String result = AuthorProfileService.preferDouyinAuthorUid(" MS4wLjABAAAAstable ", "84583932458");

		assertThat(result).isEqualTo("MS4wLjABAAAAstable");
	}

	@Test
	void preferDouyinAuthorUidDoesNotPromoteNumericUidWhenSecUidMissing() {
		String result = AuthorProfileService.preferDouyinAuthorUid("", "84583932458");

		assertThat(result).isNull();
	}

	@Test
	void upsertAuthorIgnoresDouyinNumericUid() {
		service.upsertAuthor("抖音", "84583932458", "unique-id", "display", null, null);

		verify(authorProfileDao, never()).save(any(AuthorProfileEntity.class));
	}

	@Test
	void upsertAuthorUpdatesCurrentDisplayNameAndNameHistory() {
		AuthorProfileEntity profile = new AuthorProfileEntity();
		profile.setId(7);
		profile.setPlatform("douyin");
		profile.setAuthoruid("MS4wLjABAAAAstable");
		profile.setDisplayname("old name");
		when(authorProfileDao.findByPlatformAndAuthoruid("douyin", "MS4wLjABAAAAstable"))
				.thenReturn(Optional.of(profile));
		when(authorProfileDao.save(any(AuthorProfileEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(authorNameHistoryDao.findByAuthorprofileidAndDisplayname(eq(7), eq("new name")))
				.thenReturn(Optional.empty());

		service.upsertAuthor("douyin", "MS4wLjABAAAAstable", "handle", "new name", null, null);

		assertThat(profile.getDisplayname()).isEqualTo("new name");
		assertThat(profile.getUsername()).isEqualTo("handle");
		ArgumentCaptor<AuthorNameHistoryEntity> historyCaptor = ArgumentCaptor.forClass(AuthorNameHistoryEntity.class);
		verify(authorNameHistoryDao).save(historyCaptor.capture());
		assertThat(historyCaptor.getValue().getAuthorprofileid()).isEqualTo(7);
		assertThat(historyCaptor.getValue().getDisplayname()).isEqualTo("new name");
		assertThat(historyCaptor.getValue().getFirstseentime()).isNotNull();
		assertThat(historyCaptor.getValue().getLastseentime()).isNotNull();
	}

	@Test
	void extractProfileUserReturnsNullForNonUserPayload() throws Exception {
		JSONObject payload = JSONObject.parseObject("{\"status_code\":1,\"message\":\"error\"}");

		JSONObject user = invokeExtractProfileUser(service, payload);

		assertThat(user).isNull();
	}

	@Test
	void extractProfileUserAcceptsDataUserPayload() throws Exception {
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
