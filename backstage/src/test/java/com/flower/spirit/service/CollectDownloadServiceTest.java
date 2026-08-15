package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.TransientDataAccessResourceException;

import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.DouyinGlobalCooldownException;
import com.flower.spirit.platform.DouyinWorkFetchException;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.service.WorkIngestService.IngestResult;
import com.flower.spirit.service.WorkPersistenceService.PersistenceResult;
import com.flower.spirit.service.transaction.CollectDownloadTransaction;

@ExtendWith(MockitoExtension.class)
class CollectDownloadServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-27T08:00:00Z");

	@Mock private WorkIngestService ingestService;
	@Mock private CollectDownloadTransaction transaction;
	@Mock private DatabaseWriteExecutor databaseWriteExecutor;

	private CollectDownloadService service;
	private CollectDownloadClaim claim;

	@BeforeEach
	void setUp() {
		org.mockito.Mockito.lenient().when(databaseWriteExecutor.execute(anyString(),
				org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
				.thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
		service = new CollectDownloadService(ingestService, transaction, databaseWriteExecutor);
		claim = claim("work-a");
	}

	@Test
	void completedWorkUsesCanonicalSourceAndCompletesOnlyItsClaim() {
		IngestResult result = completed(true);
		when(ingestService.ingest(eq("https://www.douyin.com/video/work-a"), anyDirectory(), eq(false), isNull()))
				.thenReturn(result);

		service.process(claim, NOW);

		verify(transaction).complete(claim, result, NOW);
		verify(transaction, never()).retryOrFail(any(), anyString(), anyString(), anyString(), any());
		verify(transaction, never()).fail(any(), anyString(), anyString(), anyString(), any());
	}

	@Test
	void eachWorkGetsAStableSeparateOutputDirectory() {
		when(ingestService.ingest(anyString(), anyDirectory(), eq(false), isNull())).thenReturn(completed(true));
		ArgumentCaptor<Function<WorkMetadata, Path>> directory = directoryCaptor();

		service.process(claim, NOW);

		verify(ingestService).ingest(anyString(), directory.capture(), eq(false), isNull());
		String normalized = directory.getValue().apply(metadata()).toString().replace('\\', '/');
		assertThat(normalized).contains("work-a");
	}

	@Test
	void queuedIngestIsRetriedInsteadOfBeingMarkedSuccessful() {
		IngestResult queued = new IngestResult(DownloadResult.Status.QUEUED, null, metadata(), null,
				"aria2 accepted", Path.of("staging"));
		when(ingestService.ingest(anyString(), anyDirectory(), eq(false), isNull())).thenReturn(queued);

		service.process(claim, NOW);

		verify(transaction).retryOrFail(eq(claim), eq("INGEST_NOT_TERMINAL"),
				org.mockito.ArgumentMatchers.contains("aria2 accepted"), anyString(), eq(NOW));
		verify(transaction, never()).complete(any(), any(), any());
	}

	@Test
	void oneNetworkFailureSchedulesOnlyThatItemForRetry() {
		when(ingestService.ingest(anyString(), anyDirectory(), eq(false), isNull()))
				.thenThrow(new WorkMetadataValidationException(
						"Douyin download failed", new IOException("unexpected end of stream")));

		service.process(claim, NOW);

		verify(transaction).retryOrFail(eq(claim), eq("NETWORK_IO"),
				org.mockito.ArgumentMatchers.contains("unexpected end of stream"), anyString(), eq(NOW));
		verify(transaction, never()).fail(any(), anyString(), anyString(), anyString(), any());
	}

	@Test
	void claimedListSnapshotIsPassedIntoWorkIngest() {
		String snapshot = "{\"aweme_detail\":{\"aweme_id\":\"work-a\"}}";
		CollectDownloadClaim snapshotClaim = new CollectDownloadClaim(1, 10, 7, "task", "douyin", "work-a",
				"video", "NEW", 1, 1, 4, "worker:lease", snapshot);
		IngestResult result = completed(true);
		when(ingestService.ingest(eq("https://www.douyin.com/video/work-a"), anyDirectory(), eq(false),
				isNull(), eq(snapshot))).thenReturn(result);

		service.process(snapshotClaim, NOW);

		verify(ingestService).ingest(eq("https://www.douyin.com/video/work-a"), anyDirectory(), eq(false),
				isNull(), eq(snapshot));
		verify(transaction).complete(snapshotClaim, result, NOW);
	}

	@Test
	void cooldownDuringIngestDefersWithoutConsumingRetryBudget() {
		Instant retryAt = Instant.parse("2026-08-03T01:00:05Z");
		when(ingestService.ingest(anyString(), anyDirectory(), eq(false), isNull()))
				.thenThrow(new DouyinGlobalCooldownException("Douyin global cooldown is active", retryAt));

		service.process(claim, NOW);

		verify(transaction).deferForCooldown(claim, retryAt, "Douyin global cooldown is active", NOW);
		verify(transaction, never()).retryOrFail(any(), anyString(), anyString(), anyString(), any());
		verify(transaction, never()).fail(any(), anyString(), anyString(), anyString(), any());
	}

	@Test
	void upstreamFailureThatOpenedCooldownStillConsumesOnlyItsOwnRetryAttempt() {
		Instant retryAt = Instant.parse("2026-08-03T01:00:05Z");
		var f2 = new DouyinWorkFetchException("F2_UPSTREAM_SOFT_BLOCK", "empty detail response",
				"REMOTE_API", true, false, 200, "APIRetryExhaustedError");
		when(ingestService.ingest(anyString(), anyDirectory(), eq(false), isNull()))
				.thenThrow(new DouyinGlobalCooldownException("empty detail response", retryAt, true, f2));

		service.process(claim, NOW);

		verify(transaction).retryOrFail(eq(claim), eq("F2_UPSTREAM_SOFT_BLOCK"),
				org.mockito.ArgumentMatchers.contains("empty detail response"), anyString(), eq(NOW));
		verify(transaction, never()).deferForCooldown(any(), any(), anyString(), any());
	}

	@Test
	void unavailableRemoteWorkStopsAutomaticRetryWithoutChangingOtherItems() {
		var cause = new DouyinWorkFetchException("F2_WORK_UNAVAILABLE", "Douyin work is no longer available",
				"REMOTE_API", false, false, 404, "HTTPStatusError");
		when(ingestService.ingest(anyString(), anyDirectory(), eq(false), isNull()))
				.thenThrow(new WorkMetadataValidationException("Douyin parsing failed", cause));

		service.process(claim, NOW);

		verify(transaction).fail(eq(claim), eq("F2_WORK_UNAVAILABLE"),
				org.mockito.ArgumentMatchers.contains("no longer available"), anyString(), eq(NOW));
		verify(transaction, never()).retryOrFail(any(), anyString(), anyString(), anyString(), any());
	}

	@Test
	void temporaryRemoteResponseUsesTypedRetryCodeInsteadOfNetworkIo() {
		var cause = new DouyinWorkFetchException("F2_UPSTREAM_RESPONSE_ERROR", "Douyin response was invalid",
				"REMOTE_API", true, false, null, "APIResponseError");
		when(ingestService.ingest(anyString(), anyDirectory(), eq(false), isNull()))
				.thenThrow(new WorkMetadataValidationException("Douyin parsing failed", cause));

		service.process(claim, NOW);

		verify(transaction).retryOrFail(eq(claim), eq("F2_UPSTREAM_RESPONSE_ERROR"),
				org.mockito.ArgumentMatchers.contains("response was invalid"), anyString(), eq(NOW));
	}

	@Test
	void permanentSchemaValidationFailsOnlyTheClaimedItem() {
		when(ingestService.ingest(anyString(), anyDirectory(), eq(false), isNull()))
				.thenThrow(new WorkMetadataValidationException("formal platform work ID is required"));

		service.process(claim, NOW);

		verify(transaction).fail(eq(claim), eq("WORK_VALIDATION_FAILED"),
				org.mockito.ArgumentMatchers.contains("work ID is required"), anyString(), eq(NOW));
		verify(transaction, never()).retryOrFail(any(), anyString(), anyString(), anyString(), any());
	}

	@Test
	void blockedWorkIsSkippedWithoutConsumingRetryBudget() {
		when(ingestService.ingest(anyString(), anyDirectory(), eq(false), isNull()))
				.thenThrow(new WorkMetadataValidationException("work is blocked: douyin/work-a"));

		service.process(claim, NOW);

		verify(transaction).skipBlocked(eq(claim), org.mockito.ArgumentMatchers.contains("blocked"), eq(NOW));
		verify(transaction, never()).retryOrFail(any(), anyString(), anyString(), anyString(), any());
		verify(transaction, never()).fail(any(), anyString(), anyString(), anyString(), any());
	}

	@Test
	void unsupportedPlatformFailsBeforeIngest() {
		CollectDownloadClaim unsupported = new CollectDownloadClaim(2, 10, 7, "task", "bilibili", "work-b",
				"video", "NEW", 1, 1, 4, "worker:lease");

		service.process(unsupported, NOW);

		verify(ingestService, never()).ingest(anyString(), anyDirectory(), eq(false), isNull());
		verify(transaction).fail(eq(unsupported), eq("UNSUPPORTED_PLATFORM"), anyString(), anyString(), eq(NOW));
	}

	@Test
	void databaseCompletionFailureRetriesTheClaimWithDatabaseCode() {
		IngestResult result = completed(true);
		when(ingestService.ingest(anyString(), anyDirectory(), eq(false), isNull())).thenReturn(result);
		org.mockito.Mockito.doThrow(new TransientDataAccessResourceException("database is locked"))
				.when(transaction).complete(claim, result, NOW);

		service.process(claim, NOW);

		verify(transaction).retryOrFail(eq(claim), eq("SQLITE_BUSY"),
				org.mockito.ArgumentMatchers.contains("database is locked"), anyString(), eq(NOW));
	}

	@Test
	void nullClaimIsRejectedBeforeAnyTransactionCall() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.process(null, NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("claim");
		verify(transaction, never()).fail(any(), anyString(), anyString(), anyString(), any());
	}

	@Test
	void auditRepairForcesExistingWorkThroughReplacementIngest() {
		CollectDownloadClaim audit = new CollectDownloadClaim(3, 10, 7, "收藏作者", "douyin", "work-a",
				"video", "AUDIT_REPAIR", 1, 1, 4, "worker:audit");
		IngestResult result = completed(false);
		when(ingestService.ingest(anyString(), anyDirectory(), eq(true), isNull())).thenReturn(result);

		service.process(audit, NOW);

		verify(ingestService).ingest(eq("https://www.douyin.com/video/work-a"), anyDirectory(), eq(true), isNull());
		verify(transaction).complete(audit, result, NOW);
	}

	@Test
	void manuallyRetriedAuditStillForcesReplacementIngest() {
		CollectDownloadClaim audit = new CollectDownloadClaim(3, 10, 7, "收藏作者", "douyin", "work-a",
				"video", "MANUAL_RETRY_AUDIT_REPAIR", 1, 1, 4, "worker:audit");
		IngestResult result = completed(false);
		when(ingestService.ingest(anyString(), anyDirectory(), eq(true), isNull())).thenReturn(result);

		service.process(audit, NOW);

		verify(transaction).complete(audit, result, NOW);
	}

	@Test
	void staleCompletionDoesNotConsumeAnotherDownloadAttempt() {
		IngestResult result = completed(true);
		when(ingestService.ingest(anyString(), anyDirectory(), eq(false), isNull())).thenReturn(result);
		org.mockito.Mockito.doThrow(new IllegalStateException(
				"Collection download item 1 was not RUNNING during transition to COMPLETED"))
				.when(transaction).complete(claim, result, NOW);

		service.process(claim, NOW);

		verify(transaction, never()).retryOrFail(any(), anyString(), anyString(), anyString(), any());
		verify(transaction, never()).fail(any(), anyString(), anyString(), anyString(), any());
	}

	private CollectDownloadClaim claim(String workId) {
		return new CollectDownloadClaim(1, 10, 7, "收藏作者", "douyin", workId, "video", "NEW", 1, 1, 4,
				"worker:lease");
	}

	private IngestResult completed(boolean created) {
		VideoDataEntity video = new VideoDataEntity();
		video.setId(30);
		return new IngestResult(DownloadResult.Status.COMPLETED, null, metadata(),
				PersistenceResult.video(created, video), created ? null : "work already exists", null);
	}

	private WorkMetadata metadata() {
		return WorkMetadata.builder().platform(PlatformCatalog.requireByKey("douyin"))
				.workId("work-a").contentType(WorkContentType.VIDEO).title("作品 A")
				.sourceUrl("https://www.douyin.com/video/work-a").mediaResources(List.of()).build();
	}

	@SuppressWarnings("unchecked")
	private Function<WorkMetadata, Path> anyDirectory() {
		return any(Function.class);
	}

	@SuppressWarnings("unchecked")
	private ArgumentCaptor<Function<WorkMetadata, Path>> directoryCaptor() {
		return ArgumentCaptor.forClass(Function.class);
	}
}
