package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.VideoDataEntity;

@ExtendWith(MockitoExtension.class)
class VideoDataServiceFindAllTest {

	@Mock
	private VideoDataDao videoDataDao;

	@Mock
	private HlsTranscodeService hlsTranscodeService;

	@Mock
	private BlockedWorkService blockedWorkService;

	@Mock
	private DouyinWorkMaintenanceService douyinWorkMaintenanceService;

	@InjectMocks
	private VideoDataService videoDataService;

	@Test
	void findAllStabilizesOrderBeforeSeededShuffle() {
		boolean previousHlsEnable = Global.hlsEnable;
		try {
			Global.hlsEnable = false;
			VideoDataEntity request = new VideoDataEntity();
			request.setRandomMode("1");
			request.setRandomSeed("fixed-seed");

			List<VideoDataEntity> firstDaoOrder = new ArrayList<>(List.of(
					video(1, "one.mp4"),
					video(2, "two.mp4"),
					video(3, "three.mp4")));
			List<VideoDataEntity> secondDaoOrder = new ArrayList<>(List.of(
					video(3, "three.mp4"),
					video(1, "one.mp4"),
					video(2, "two.mp4")));
			when(videoDataDao.findAll(any(Specification.class))).thenReturn(firstDaoOrder, secondDaoOrder);
			when(hlsTranscodeService.queuedIdsSnapshot()).thenReturn(new HashSet<>());
			List<VideoDataEntity> expectedOrder = new ArrayList<>(List.of(
					video(3, "three.mp4"),
					video(2, "two.mp4"),
					video(1, "one.mp4")));
			Collections.shuffle(expectedOrder, new java.util.Random("fixed-seed".hashCode()));

			AjaxEntity firstResponse = videoDataService.findAll(request);
			AjaxEntity secondResponse = videoDataService.findAll(request);

			assertThat(firstResponse.getResCode()).isEqualTo(Global.ajax_success);
			assertThat(firstResponse.getMessage()).isEqualTo("查询成功");
			assertThat(firstResponse.getRecord()).isInstanceOf(List.class);
			assertThat((List<?>) firstResponse.getRecord()).extracting("id")
					.containsExactlyElementsOf(expectedOrder.stream().map(VideoDataEntity::getId).toList());
			assertThat((List<?>) secondResponse.getRecord()).extracting("id")
					.containsExactlyElementsOf(expectedOrder.stream().map(VideoDataEntity::getId).toList());
			assertThat((List<VideoDataEntity>) firstResponse.getRecord()).allSatisfy(item -> {
				assertThat(item.getPlayurl()).isEqualTo(item.getVideounrealaddr());
				assertThat(item.getHlsstatus()).isEqualTo("关闭");
			});
		} finally {
			Global.hlsEnable = previousHlsEnable;
		}
	}

	@Test
	void findPageClampsZeroOrNegativePageNumberBeforeSpringPageRequest() {
		VideoDataEntity request = new VideoDataEntity();
		request.setPageNo(0);
		when(videoDataDao.findAll(any(Specification.class), any(Pageable.class)))
				.thenAnswer(invocation -> {
					Pageable pageable = invocation.getArgument(1);
					assertThat(pageable.getPageNumber()).isZero();
					return new PageImpl<>(List.of(), pageable, 0);
				});

		AjaxEntity response = videoDataService.findPage(request);

		assertThat(response.getResCode()).isEqualTo(Global.ajax_success);
	}

	private VideoDataEntity video(Integer id, String playUrl) {
		VideoDataEntity video = new VideoDataEntity();
		video.setId(id);
		video.setVideounrealaddr(playUrl);
		return video;
	}
}
