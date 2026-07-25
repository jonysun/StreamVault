package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.flower.spirit.entity.AuthorProfileEntity;
import com.flower.spirit.service.transaction.AuthorEnrichmentTransaction;

class AuthorEnrichmentQueueServiceTest {

	@Test
	void completenessReportsEveryMissingField() {
		AuthorEnrichmentQueueService service = service(mock(AuthorEnrichmentTransaction.class));

		AuthorCompleteness completeness = service.inspect(new AuthorObservation("douyin", "MS4-author",
				null, "Name", null, null, "https://www.douyin.com/user/MS4-author"));

		assertThat(completeness.missingFields()).containsExactlyInAnyOrder(
				AuthorField.USERNAME, AuthorField.AVATAR, AuthorField.SIGNATURE);
	}

	@Test
	void completeObservationDoesNotCreateQueueJob() {
		AuthorEnrichmentTransaction transaction = mock(AuthorEnrichmentTransaction.class);
		AuthorEnrichmentQueueService service = service(transaction);

		service.enqueueAfterCommitIfIncomplete(new AuthorObservation("douyin", "MS4-author", "handle", "Name",
				"avatar", "signature", "https://www.douyin.com/user/MS4-author"));

		verify(transaction, never()).enqueue(any(), any(), eq(100), eq(false), any(Instant.class));
	}

	@Test
	void usernameOnlyGapUsesLowPriority() {
		AuthorEnrichmentTransaction transaction = mock(AuthorEnrichmentTransaction.class);
		when(transaction.enqueue(eq("douyin"), eq("MS4-author"), eq(200), eq(false), any(Instant.class)))
				.thenReturn(new AuthorEnrichmentEnqueueResult(10, "QUEUED", true, false));
		AuthorEnrichmentQueueService service = service(transaction);

		service.enqueueAfterCommitIfIncomplete(new AuthorObservation("douyin", "MS4-author", null, "Name",
				"avatar", "signature", "https://www.douyin.com/user/MS4-author"));

		verify(transaction).enqueue(eq("douyin"), eq("MS4-author"), eq(200), eq(false), any(Instant.class));
	}

	@Test
	void manualRefreshPromotesCanonicalDouyinJob() {
		AuthorEnrichmentTransaction transaction = mock(AuthorEnrichmentTransaction.class);
		when(transaction.enqueue(eq("douyin"), eq("MS4-author"), eq(0), eq(true), any(Instant.class)))
				.thenReturn(new AuthorEnrichmentEnqueueResult(9, "QUEUED", false, true));
		AuthorEnrichmentQueueService service = service(transaction);
		AuthorProfileEntity profile = new AuthorProfileEntity();
		profile.setId(4);
		profile.setPlatform("抖音");
		profile.setPlatformkey("douyin");
		profile.setAuthoruid("MS4-author");

		AuthorEnrichmentEnqueueResult result = service.enqueueManual(profile);

		assertThat(result.jobId()).isEqualTo(9);
		assertThat(result.promoted()).isTrue();
	}

	private AuthorEnrichmentQueueService service(AuthorEnrichmentTransaction transaction) {
		return new AuthorEnrichmentQueueService(transaction, new SqliteWriteRetrier(3, 0, 0));
	}
}
