package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.flower.spirit.dao.AuthorNameHistoryDao;
import com.flower.spirit.dao.AuthorProfileDao;
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
}
