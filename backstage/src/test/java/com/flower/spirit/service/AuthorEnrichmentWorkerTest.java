package com.flower.spirit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.entity.AuthorProfileEntity;
import com.flower.spirit.service.transaction.AuthorEnrichmentTransaction;

class AuthorEnrichmentWorkerTest {

	@Test
	void validProfileIsAppliedAndJobCompletes() {
		Fixture fixture = new Fixture();
		AuthorEnrichmentClaim claim = new AuthorEnrichmentClaim(8, "douyin", "MS4-author", 1);
		when(fixture.transaction.claimNext(any(Instant.class), eq(15L))).thenReturn(claim);
		JSONObject profileUser = new JSONObject();
		profileUser.put("sec_uid", "MS4-author");
		when(fixture.gateway.fetchProfileUser("MS4-author")).thenReturn(profileUser);
		AuthorProfileEntity profile = new AuthorProfileEntity();
		profile.setId(12);
		when(fixture.authorProfileDao.findAllByPlatformkeyAndAuthoruidOrderByUpdatetimeDescIdDesc(
				"douyin", "MS4-author")).thenReturn(List.of(profile));

		fixture.worker.processOne();

		verify(fixture.authorProfileService).applyExternalDouyinProfile(12, profileUser);
		verify(fixture.transaction).complete(eq(8), any(Instant.class));
	}

	@Test
	void mismatchedProfileUidIsRejectedWithoutAuthorWrite() {
		Fixture fixture = new Fixture();
		when(fixture.transaction.claimNext(any(Instant.class), eq(15L)))
				.thenReturn(new AuthorEnrichmentClaim(9, "douyin", "MS4-expected", 1));
		JSONObject profileUser = new JSONObject();
		profileUser.put("sec_uid", "MS4-other");
		when(fixture.gateway.fetchProfileUser("MS4-expected")).thenReturn(profileUser);

		fixture.worker.processOne();

		verify(fixture.authorProfileService, never()).applyExternalDouyinProfile(any(), any());
		verify(fixture.transaction).fail(eq(9), eq("IDENTITY_MISMATCH"), any(), any(Instant.class));
	}

	private static class Fixture {
		final AuthorEnrichmentTransaction transaction = mock(AuthorEnrichmentTransaction.class);
		final AuthorProfileDao authorProfileDao = mock(AuthorProfileDao.class);
		final AuthorProfileService authorProfileService = mock(AuthorProfileService.class);
		final DouyinProfileGateway gateway = mock(DouyinProfileGateway.class);
		final AuthorEnrichmentWorker worker = new AuthorEnrichmentWorker(transaction, authorProfileDao,
				authorProfileService, gateway, new SqliteWriteRetrier(3, 0, 0), 5);
	}
}
