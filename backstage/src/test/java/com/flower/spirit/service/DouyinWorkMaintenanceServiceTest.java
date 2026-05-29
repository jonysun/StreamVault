package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;

@ExtendWith(MockitoExtension.class)
class DouyinWorkMaintenanceServiceTest {

	@Mock
	private VideoDataDao videoDataDao;

	@Mock
	private GraphicContentDao graphicContentDao;

	@Mock
	private AuthorProfileService authorProfileService;

	@InjectMocks
	private DouyinWorkMaintenanceService service;

	@Test
	void repairDouyinMetadataBackfillsSourceUrlsWithoutChangingDownloadTime() {
		Date originalDownloadTime = new Date(1710000000000L);
		VideoDataEntity video = new VideoDataEntity();
		video.setId(11);
		video.setVideoid("7312345678901234567");
		video.setVideoplatform("抖音");
		video.setCreatetime(originalDownloadTime);

		GraphicContentEntity graphic = new GraphicContentEntity();
		graphic.setId(22);
		graphic.setVideoid("7412345678901234567");
		graphic.setPlatform("抖音");
		graphic.setCreatetime(originalDownloadTime);

		when(videoDataDao.findByVideoplatform("抖音")).thenReturn(List.of(video));
		when(graphicContentDao.findByPlatform("抖音")).thenReturn(List.of(graphic));

		service.repairDouyinMetadata();

		ArgumentCaptor<VideoDataEntity> videoCaptor = ArgumentCaptor.forClass(VideoDataEntity.class);
		ArgumentCaptor<GraphicContentEntity> graphicCaptor = ArgumentCaptor.forClass(GraphicContentEntity.class);
		verify(videoDataDao, times(1)).save(videoCaptor.capture());
		verify(graphicContentDao, times(1)).save(graphicCaptor.capture());
		assertThat(videoCaptor.getValue().getSourceurl()).isEqualTo("https://www.douyin.com/video/7312345678901234567");
		assertThat(graphicCaptor.getValue().getSourceurl()).isEqualTo("https://www.douyin.com/note/7412345678901234567");
		assertThat(videoCaptor.getValue().getCreatetime()).isEqualTo(originalDownloadTime);
		assertThat(graphicCaptor.getValue().getCreatetime()).isEqualTo(originalDownloadTime);
	}
}
