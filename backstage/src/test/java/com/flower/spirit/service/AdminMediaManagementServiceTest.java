package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import com.flower.spirit.dao.AuthorNameHistoryDao;
import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.dao.CollectdDataDao;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.dto.AdminAuthorDeletionRequest;
import com.flower.spirit.dto.AdminDeleteWorkRequest;
import com.flower.spirit.entity.AuthorProfileEntity;
import com.flower.spirit.entity.CollectDataEntity;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.task.QuartzTaskService;

@ExtendWith(MockitoExtension.class)
class AdminMediaManagementServiceTest {

	@Mock private VideoDataDao videoDataDao;
	@Mock private GraphicContentDao graphicContentDao;
	@Mock private AuthorProfileDao authorProfileDao;
	@Mock private AuthorNameHistoryDao authorNameHistoryDao;
	@Mock private CollectdDataDao collectDataDao;
	@Mock private BlockedWorkService blockedWorkService;
	@Mock private AdminMediaFileService mediaFileService;
	@Mock private HlsTranscodeService hlsTranscodeService;
	@Mock private QuartzTaskService quartzTaskService;

	private AdminMediaManagementService service;

	@BeforeEach
	void setUp() {
		service = new AdminMediaManagementService(videoDataDao, graphicContentDao, authorProfileDao,
				authorNameHistoryDao, collectDataDao, blockedWorkService, mediaFileService,
				hlsTranscodeService, quartzTaskService);
	}

	@AfterEach
	void tearDown() {
		service.shutdown();
	}

	@Test
	void deletesVideoThroughTheUnifiedSafetyPath() {
		VideoDataEntity video = video(7);
		when(videoDataDao.findById(7)).thenReturn(Optional.of(video));
		when(hlsTranscodeService.beginVideoDeletion(7)).thenReturn(true);
		AdminDeleteWorkRequest request = deleteRequest("video", 7);

		AdminMediaManagementService.DeleteWorkResult result = service.deleteWork(request);

		assertThat(result.mediaKey()).isEqualTo("video:7");
		verify(hlsTranscodeService).beginVideoDeletion(7);
		verify(hlsTranscodeService).endVideoDeletion(7);
		verify(mediaFileService).deleteVideoMedia(video);
		verify(blockedWorkService).blockWork("抖音", "work-7", "video", "Title", "Author",
				"MS4-author", "https://example/work-7", "manual-delete");
		verify(videoDataDao).deleteById(7);
	}

	@Test
	void refusesDeletionWhileHlsIsWritingTheWork() {
		VideoDataEntity video = video(7);
		when(videoDataDao.findById(7)).thenReturn(Optional.of(video));
		when(hlsTranscodeService.beginVideoDeletion(7)).thenReturn(false);

		assertThatThrownBy(() -> service.deleteWork(deleteRequest("video", 7)))
				.isInstanceOf(WorkMetadataValidationException.class)
				.hasMessageContaining("transcoded");
		verify(mediaFileService, never()).deleteVideoMedia(any());
		verify(videoDataDao, never()).deleteById(any());
	}

	@Test
	void rejectsNumericDouyinUidForAuthorDeletion() {
		AdminAuthorDeletionRequest request = authorRequest("抖音", "123456");

		assertThatThrownBy(() -> service.previewAuthorDeletion(request))
				.isInstanceOf(WorkMetadataValidationException.class)
				.hasMessageContaining("MS4");
	}

	@Test
	void disablesDirectTaskAndDeletesAuthorDataAsynchronously() throws Exception {
		VideoDataEntity video = video(9);
		CollectDataEntity directTask = new CollectDataEntity();
		directTask.setId(3);
		directTask.setPlatform("抖音");
		directTask.setTaskname("Author works");
		directTask.setOriginaladdress("postMS4-author");
		directTask.setTaskenabled("Y");
		CollectDataEntity sharedTask = new CollectDataEntity();
		sharedTask.setId(4);
		sharedTask.setPlatform("抖音");
		sharedTask.setOriginaladdress("collect123");
		AuthorProfileEntity profile = new AuthorProfileEntity();
		profile.setId(12);
		profile.setPlatform("抖音");
		profile.setPlatformkey("douyin");
		profile.setAuthoruid("MS4-author");
		when(videoDataDao.findAll(ArgumentMatchers.<Specification<VideoDataEntity>>any()))
				.thenReturn(List.of(video));
		when(graphicContentDao.findAll(ArgumentMatchers.<Specification<GraphicContentEntity>>any()))
				.thenReturn(List.of());
		when(collectDataDao.findAll(ArgumentMatchers.<Specification<CollectDataEntity>>any()))
				.thenReturn(List.of(directTask));
		when(videoDataDao.findById(9)).thenReturn(Optional.of(video));
		when(hlsTranscodeService.beginVideoDeletion(9)).thenReturn(true);
		when(authorProfileDao.findByAuthoruid("MS4-author")).thenReturn(List.of(profile));
		AdminAuthorDeletionRequest request = authorRequest("抖音", "MS4-author");
		AdminMediaManagementService.AuthorDeletionPreview preview = service.previewAuthorDeletion(request);
		request.setConfirmationToken(preview.confirmationToken());

		AdminMediaManagementService.AuthorDeletionStart start = service.startAuthorDeletion(request);
		AdminMediaManagementService.AuthorDeletionStatus status = waitForTerminal(start.jobId());

		assertThat(status.state()).isEqualTo("completed");
		assertThat(status.deletedVideos()).isEqualTo(1);
		assertThat(status.disabledTasks()).isEqualTo(1);
		assertThat(directTask.getTaskenabled()).isEqualTo("N");
		assertThat(sharedTask.getTaskenabled()).isNull();
		verify(quartzTaskService).removeTaskSchedule(3);
		verify(videoDataDao).deleteById(9);
		verify(authorNameHistoryDao).deleteByAuthorprofileid(12);
		verify(authorProfileDao).delete(profile);
	}

	private AdminMediaManagementService.AuthorDeletionStatus waitForTerminal(String jobId) throws Exception {
		for (int i = 0; i < 100; i++) {
			AdminMediaManagementService.AuthorDeletionStatus status = service.authorDeletionStatus(jobId);
			if (!"queued".equals(status.state()) && !"running".equals(status.state())) {
				return status;
			}
			Thread.sleep(10L);
		}
		throw new AssertionError("author deletion did not finish");
	}

	private VideoDataEntity video(int id) {
		VideoDataEntity video = new VideoDataEntity();
		video.setId(id);
		video.setPlatformkey("douyin");
		video.setVideoplatform("抖音");
		video.setVideoid("work-" + id);
		video.setVideoname("Title");
		video.setVideoauthor("Author");
		video.setAuthoruid("MS4-author");
		video.setSecuid("MS4-author");
		video.setSourceurl("https://example/work-" + id);
		return video;
	}

	private AdminDeleteWorkRequest deleteRequest(String type, int id) {
		AdminDeleteWorkRequest request = new AdminDeleteWorkRequest();
		request.setWorkType(type);
		request.setId(id);
		return request;
	}

	private AdminAuthorDeletionRequest authorRequest(String platform, String uid) {
		AdminAuthorDeletionRequest request = new AdminAuthorDeletionRequest();
		request.setPlatform(platform);
		request.setAuthoruid(uid);
		return request;
	}
}
