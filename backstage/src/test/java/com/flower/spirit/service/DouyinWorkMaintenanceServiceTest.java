package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import com.flower.spirit.config.Global;
import com.flower.spirit.utils.FileUtil;
import com.flower.spirit.utils.FileNameTemplateUtil;
import com.flower.spirit.utils.HttpUtil;

@ExtendWith(MockitoExtension.class)
class DouyinWorkMaintenanceServiceTest {

	@Mock
	private VideoDataDao videoDataDao;

	@Mock
	private GraphicContentDao graphicContentDao;

	@Mock
	private AuthorProfileService authorProfileService;
	@Mock private PlatformCookieService platformCookieService;
	@Mock private DouyinProfileGateway douyinProfileGateway;
	@Mock private DouyinDataProviderService douyinDataProviderService;

	@InjectMocks
	private DouyinWorkMaintenanceService service;

	@Test
	void redownloadWithPartialAuthorResponseDoesNotEraseKnownMetadata() {
		VideoDataEntity video = new VideoDataEntity();
		video.setId(10);
		video.setVideoid("work");
		video.setVideoplatform("抖音");
		video.setAuthoruid("MS4-author");
		video.setSecuid("MS4-author");
		video.setAuthorusername("known-name");
		video.setUniqueid("known-name");
		video.setAuthoravatar("https://media.example/known.jpg");
		video.setVideoauthor("Known Author");
		when(videoDataDao.findById(10)).thenReturn(Optional.of(video));
		when(douyinDataProviderService.fetchDirect(any(), any())).thenReturn(Map.of(
				"awemeid", "work", "desc", "caption", "videoplay", "https://media.example/video.mp4",
				"cover", "https://media.example/cover.jpg", "sec_uid", "MS4-author"));
		String previousDownloader = Global.downtype;
		try (var files = org.mockito.Mockito.mockStatic(FileUtil.class);
				var names = org.mockito.Mockito.mockStatic(FileNameTemplateUtil.class);
				var http = org.mockito.Mockito.mockStatic(HttpUtil.class)) {
			Global.downtype = "http";
			service.redownloadVideo(10);
		} finally {
			Global.downtype = previousDownloader;
		}
		verify(videoDataDao).save(video);
		verify(authorProfileService).prepareVideoAuthorForSave(video);
		assertThat(video.getAuthorusername()).isEqualTo("known-name");
		assertThat(video.getUniqueid()).isEqualTo("known-name");
		assertThat(video.getAuthoravatar()).isEqualTo("https://media.example/known.jpg");
		assertThat(video.getVideoauthor()).isEqualTo("Known Author");
	}

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
		graphic.setAuthoruid("MS4wLjABAAAAstableAuthor");
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
		assertThat(graphicCaptor.getValue().getSourceurl())
				.isEqualTo("https://www.douyin.com/user/MS4wLjABAAAAstableAuthor?modal_id=7412345678901234567");
		assertThat(videoCaptor.getValue().getCreatetime()).isEqualTo(originalDownloadTime);
		assertThat(graphicCaptor.getValue().getCreatetime()).isEqualTo(originalDownloadTime);
	}
}
