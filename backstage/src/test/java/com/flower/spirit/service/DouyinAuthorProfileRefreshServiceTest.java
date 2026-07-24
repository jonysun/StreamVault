package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.entity.AuthorProfileEntity;
import com.flower.spirit.platform.WorkMetadataValidationException;

class DouyinAuthorProfileRefreshServiceTest {

	@Test
	void rejectsNonDouyinProfileBeforeExternalRequest() {
		AuthorProfileDao authorProfileDao = mock(AuthorProfileDao.class);
		AuthorProfileService authorProfileService = mock(AuthorProfileService.class);
		DouyinAuthorProfileRefreshService service = new DouyinAuthorProfileRefreshService(authorProfileDao,
				authorProfileService);
		AuthorProfileEntity profile = new AuthorProfileEntity();
		profile.setId(7);
		profile.setPlatform("其他平台");
		profile.setPlatformkey("other");
		profile.setAuthoruid("MS4wLjABAAAAinvalidPlatform");
		when(authorProfileDao.findById(7)).thenReturn(Optional.of(profile));

		assertThatThrownBy(() -> service.refresh(7))
				.isInstanceOf(WorkMetadataValidationException.class)
				.hasMessage("仅支持刷新抖音作者档案");

		verifyNoInteractions(authorProfileService);
	}
}
