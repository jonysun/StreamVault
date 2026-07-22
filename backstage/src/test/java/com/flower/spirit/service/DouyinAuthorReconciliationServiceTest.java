package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;

@ExtendWith(MockitoExtension.class)
class DouyinAuthorReconciliationServiceTest {

	@Mock private VideoDataDao videoDataDao;
	@Mock private GraphicContentDao graphicContentDao;
	@Mock private AuthorProfileDao authorProfileDao;
	@Mock private AuthorProfileService authorProfileService;
	@Mock private JdbcTemplate jdbcTemplate;

	private DouyinAuthorReconciliationService service;

	@BeforeEach
	void setUp() {
		service = new DouyinAuthorReconciliationService(videoDataDao, graphicContentDao, authorProfileDao,
				authorProfileService, jdbcTemplate);
	}

	@AfterEach
	void tearDown() {
		service.shutdown();
	}

	@Test
	void previewOnlyReadsAggregateCounts() {
		stubHealthyPreview();
		AjaxEntity response = service.preview();

		assertThat(response.getResCode()).isEqualTo("000001");
		DouyinAuthorReconciliationService.ReconcilePreview preview =
				(DouyinAuthorReconciliationService.ReconcilePreview) response.getRecord();
		assertThat(preview.videoCandidates()).isEqualTo(1);
		assertThat(preview.safeToRun()).isTrue();
		verify(videoDataDao, never()).save(any(VideoDataEntity.class));
		verify(graphicContentDao, never()).save(any(GraphicContentEntity.class));
		verify(videoDataDao, never()).findAll();
		verify(graphicContentDao, never()).findAll();
	}

	@Test
	void startRefusesToWriteWhenDatabaseIntegrityFails() {
		when(jdbcTemplate.queryForList("PRAGMA quick_check(1)", String.class))
				.thenReturn(List.of("database disk image is malformed"));

		AjaxEntity response = service.start();

		assertThat(response.getResCode()).isNotEqualTo("000001");
		verify(videoDataDao, never()).findByVideoplatformInAndIdGreaterThanOrderByIdAsc(
				anyList(), anyInt(), any(Pageable.class));
		verify(graphicContentDao, never()).findByPlatformInAndIdGreaterThanOrderByIdAsc(
				anyList(), anyInt(), any(Pageable.class));
	}

	@Test
	void repairUsesStableIdPagesAndCompletes() throws Exception {
		stubHealthyPreview();
		VideoDataEntity video = new VideoDataEntity();
		video.setId(10);
		GraphicContentEntity graphic = new GraphicContentEntity();
		graphic.setId(20);
		when(videoDataDao.findByVideoplatformInAndIdGreaterThanOrderByIdAsc(anyList(), anyInt(), any(Pageable.class)))
				.thenReturn(List.of(video), List.of());
		when(graphicContentDao.findByPlatformInAndIdGreaterThanOrderByIdAsc(anyList(), anyInt(), any(Pageable.class)))
				.thenReturn(List.of(graphic), List.of());
		when(authorProfileService.reconcileDouyinVideo(any(VideoDataEntity.class), any()))
				.thenReturn(new AuthorProfileService.WorkAuthorReconcileResult(true, false, true, false, false));
		when(authorProfileService.reconcileDouyinGraphic(any(GraphicContentEntity.class), any()))
				.thenReturn(new AuthorProfileService.WorkAuthorReconcileResult(true, false, true, false, false));

		assertThat(service.start().getResCode()).isEqualTo("000001");
		DouyinAuthorReconciliationService.ReconcileStatus status = awaitFinished();

		assertThat(status.state()).isEqualTo("completed");
		assertThat(status.scannedVideos()).isEqualTo(1);
		assertThat(status.scannedGraphics()).isEqualTo(1);
		assertThat(status.updatedVideos()).isEqualTo(1);
		assertThat(status.updatedGraphics()).isEqualTo(1);
		verify(videoDataDao, never()).findAll();
		verify(graphicContentDao, never()).findAll();
	}

	@Test
	void oneRowFailureIsCountedAndDoesNotAbortRemainingPages() throws Exception {
		stubHealthyPreview();
		VideoDataEntity video = new VideoDataEntity();
		video.setId(11);
		when(videoDataDao.findByVideoplatformInAndIdGreaterThanOrderByIdAsc(anyList(), anyInt(), any(Pageable.class)))
				.thenReturn(List.of(video), List.of());
		when(graphicContentDao.findByPlatformInAndIdGreaterThanOrderByIdAsc(anyList(), anyInt(), any(Pageable.class)))
				.thenReturn(List.of());
		when(authorProfileService.reconcileDouyinVideo(any(VideoDataEntity.class), any()))
				.thenThrow(new IllegalStateException("row failure"));

		assertThat(service.start().getResCode()).isEqualTo("000001");
		DouyinAuthorReconciliationService.ReconcileStatus status = awaitFinished();

		assertThat(status.state()).isEqualTo("completed");
		assertThat(status.scannedVideos()).isEqualTo(1);
		assertThat(status.failedRows()).isEqualTo(1);
		verify(graphicContentDao).findByPlatformInAndIdGreaterThanOrderByIdAsc(anyList(), anyInt(), any(Pageable.class));
	}

	private void stubHealthyPreview() {
		when(jdbcTemplate.queryForList("PRAGMA quick_check(1)", String.class)).thenReturn(List.of("ok"));
		when(videoDataDao.countByVideoplatformIn(anyList())).thenReturn(1L);
		when(graphicContentDao.countByPlatformIn(anyList())).thenReturn(1L);
		when(videoDataDao.countDouyinAuthorRepairCandidates(anyList())).thenReturn(1L);
		when(graphicContentDao.countDouyinAuthorRepairCandidates(anyList())).thenReturn(1L);
		when(authorProfileDao.countDouyinProfiles(anyList())).thenReturn(1L);
		when(authorProfileDao.countLegacyDouyinProfiles(anyList())).thenReturn(0L);
	}

	private DouyinAuthorReconciliationService.ReconcileStatus awaitFinished() throws InterruptedException {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(3));
		while (Instant.now().isBefore(deadline)) {
			DouyinAuthorReconciliationService.ReconcileStatus current =
					(DouyinAuthorReconciliationService.ReconcileStatus) service.getStatus().getRecord();
			if (!"running".equals(current.state())) {
				return current;
			}
			Thread.sleep(20);
		}
		return (DouyinAuthorReconciliationService.ReconcileStatus) service.getStatus().getRecord();
	}
}
