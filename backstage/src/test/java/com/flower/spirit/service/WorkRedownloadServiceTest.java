package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.dto.WorkOperationRequest;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.platform.WorkParseRequest;
import com.flower.spirit.platform.adapter.PlatformWorkAdapter;
import com.flower.spirit.service.WorkPersistenceService.PersistenceResult;
import com.flower.spirit.service.WorkRefreshService.PreparedWork;

@ExtendWith(MockitoExtension.class)
class WorkRedownloadServiceTest {

	@TempDir Path tempDir;
	@Mock private WorkRefreshService refreshService;
	@Mock private WorkPersistenceService persistenceService;
	@Mock private WorkMetadataEditService editService;
	@Mock private VideoDataDao videoDataDao;
	@Mock private GraphicContentDao graphicContentDao;
	@Mock private HlsTranscodeService hlsTranscodeService;

	private WorkRedownloadService service;
	private VideoDataEntity existing;

	@BeforeEach
	void setUp() throws Exception {
		service = new WorkRedownloadService(refreshService, new MediaDownloadService(), persistenceService,
				editService, videoDataDao, graphicContentDao, hlsTranscodeService);
		Path directory = tempDir.resolve("work-7");
		Files.createDirectories(directory);
		Files.writeString(directory.resolve("video.mp4"), "old-media");
		existing = new VideoDataEntity();
		existing.setId(7);
		existing.setVideoaddr(directory.resolve("video.mp4").toString());
	}

	@Test
	void successfulReplacementPromotesVerifiedFilesKeepsRowIdAndRebuildsHls() throws Exception {
		prepareWith(adapter(false));
		when(persistenceService.persist(any())).thenReturn(PersistenceResult.video(false, existing));
		when(videoDataDao.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		WorkRedownloadService.RedownloadResult result = service.redownload(request());

		assertThat(result.status()).isEqualTo(DownloadResult.Status.COMPLETED);
		assertThat(result.persistence().id()).isEqualTo(7);
		assertThat(Files.readString(tempDir.resolve("work-7/video.mp4"))).isEqualTo("new-media");
		verify(editService).reapplyStoredOverrides(existing);
		verify(hlsTranscodeService).enqueueVideo(7);
	}

	@Test
	void persistenceFailureRestoresOldFilesAndDoesNotEnqueueHls() throws Exception {
		prepareWith(adapter(false));
		when(persistenceService.persist(any())).thenThrow(new IllegalStateException("database unavailable"));

		assertThatThrownBy(() -> service.redownload(request()))
				.isInstanceOf(WorkMetadataValidationException.class)
				.hasMessageContaining("database unavailable");
		assertThat(Files.readString(tempDir.resolve("work-7/video.mp4"))).isEqualTo("old-media");
		verify(hlsTranscodeService, never()).enqueueVideo(any());
	}

	@Test
	void queuedDownloadDoesNotReplaceFilesOrPersistMetadata() throws Exception {
		prepareWith(adapter(true));

		WorkRedownloadService.RedownloadResult result = service.redownload(request());

		assertThat(result.status()).isEqualTo(DownloadResult.Status.QUEUED);
		assertThat(result.workingDirectory()).exists().isDirectory();
		assertThat(Files.readString(tempDir.resolve("work-7/video.mp4"))).isEqualTo("old-media");
		verify(persistenceService, never()).persist(any());
		verify(hlsTranscodeService, never()).enqueueVideo(any());
	}

	private void prepareWith(PlatformWorkAdapter workAdapter) {
		when(refreshService.prepare(any(), anyBoolean())).thenReturn(
				new PreparedWork("video", metadata(), workAdapter, existing, null));
	}

	private PlatformWorkAdapter adapter(boolean queued) {
		return new PlatformWorkAdapter() {
			@Override public String platformKey() { return "youtube"; }
			@Override public boolean supports(String input) { return true; }
			@Override public WorkMetadata parse(WorkParseRequest request) { return metadata(); }
			@Override public DownloadResult download(WorkMetadata metadata, WorkDownloadRequest request) {
				if (queued) return DownloadResult.queued("queued");
				try {
					Path file = Files.writeString(request.getOutputDirectory().resolve("video.mp4"), "new-media");
					return DownloadResult.completed(List.of(new WorkMediaResource(0, WorkMediaResource.Type.VIDEO,
							null, file, "mp4", Map.of())));
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		};
	}

	private WorkMetadata metadata() {
		return WorkMetadata.builder()
				.platform(PlatformCatalog.requireByKey("youtube"))
				.workId("work-7")
				.contentType(WorkContentType.VIDEO)
				.mediaResources(List.of(new WorkMediaResource(0, WorkMediaResource.Type.VIDEO,
						"https://cdn.example/video.mp4", null, "mp4", Map.of())))
				.build();
	}

	private WorkOperationRequest request() {
		WorkOperationRequest request = new WorkOperationRequest();
		request.setWorkType("video");
		request.setId(7);
		return request;
	}
}
