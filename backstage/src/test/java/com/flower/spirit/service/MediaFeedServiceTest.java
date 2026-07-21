package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dto.AdminMediaFeedItem;
import com.flower.spirit.dto.AdminMediaSlide;
import com.flower.spirit.dto.AdminVideoListItem;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;

@ExtendWith(MockitoExtension.class)
class MediaFeedServiceTest {

	@Mock
	private VideoDataService videoDataService;

	@Mock
	private GraphicContentService graphicContentService;

	@InjectMocks
	private MediaFeedService service;

	@Test
	void parseGraphicSlidesDetectsImageAndVideoExtensions() {
		List<AdminMediaSlide> slides = service.parseGraphicSlidesForTest("[\"/a.jpeg\",\"/b.mp4\",\"/c.webp\"]");

		assertThat(slides).extracting(AdminMediaSlide::getType).containsExactly("image", "video", "image");
	}

	@Test
	void parseGraphicSlidesReturnsEmptyListForMalformedJson() {
		assertThat(service.parseGraphicSlidesForTest("not-json")).isEmpty();
	}

	@Test
	void graphicWithSlidesMapsToFeedItem() {
		GraphicContentEntity graphic = new GraphicContentEntity();
		graphic.setId(9);
		graphic.setTitle("graphic title");
		graphic.setAuthor("author");
		graphic.setPlatform("rednote");
		graphic.setImages("[\"/first.jpeg\",\"/second.mp4\"]");
		graphic.setPrivacy("1");
		graphic.setFavorite("1");

		AdminMediaFeedItem item = service.toGraphicFeedItemForTest(graphic);

		assertThat(item.getType()).isEqualTo("graphic");
		assertThat(item.getMediaKey()).isEqualTo("graphic:9");
		assertThat(item.getCover()).isEqualTo("/first.jpeg");
		assertThat(item.getSlides()).hasSize(2);
		assertThat(item.getPlatform()).isEqualTo("小红书");
		assertThat(item.getPlatformkey()).isEqualTo("xiaohongshu");
		assertThat(item.getContenttype()).isEqualTo("mixed");
		assertThat(item.getPrivacy()).isEqualTo("1");
		assertThat(item.getFavorite()).isEqualTo("1");
	}

	@Test
	void videoMapsToUnifiedFeedItemWithoutLargeFields() {
		VideoDataEntity video = new VideoDataEntity();
		video.setId(3);
		video.setVideoname("video title");
		video.setVideodesc("video desc");
		video.setVideoauthor("author");
		video.setVideoplatform("douyin");
		video.setVideocover("/cover.jpg");
		video.setVideounrealaddr("/video.mp4");
		video.setPlayurl("/video.m3u8");
		video.setVideoprivacy("1");

		AdminMediaFeedItem item = service.toVideoFeedItemForTest(video);

		assertThat(item.getType()).isEqualTo("video");
		assertThat(item.getMediaKey()).isEqualTo("video:3");
		assertThat(item.getTitle()).isEqualTo("video title");
		assertThat(item.getDesc()).isEqualTo("video desc");
		assertThat(item.getCover()).isEqualTo("/cover.jpg");
		assertThat(item.getPlayurl()).isEqualTo("/video.m3u8");
		assertThat(item.getFallbackUrl()).isEqualTo("/video.mp4");
		assertThat(item.getPrivacy()).isEqualTo("1");
		assertThat(item.getPlatform()).isEqualTo("抖音");
		assertThat(item.getPlatformkey()).isEqualTo("douyin");
		assertThat(item.getContenttype()).isEqualTo("video");
		assertThat(item.getSlides()).isEmpty();
	}

	@Test
	void douyinFeedUsesSecUidAndKeepsUsernameSeparate() {
		VideoDataEntity video = new VideoDataEntity();
		video.setVideoplatform("抖音");
		video.setAuthoruid("84583932458");
		video.setSecuid("MS4wLjABAAAAstable");
		video.setAuthorusername("public_handle");
		video.setUniqueid("raw_handle");

		AdminMediaFeedItem item = service.toVideoFeedItemForTest(video);

		assertThat(item.getAuthoruid()).isEqualTo("MS4wLjABAAAAstable");
		assertThat(item.getSecuid()).isEqualTo("MS4wLjABAAAAstable");
		assertThat(item.getAuthorusername()).isEqualTo("public_handle");
		assertThat(item.getUniqueid()).isEqualTo("public_handle");
	}

	@Test
	void douyinFeedDoesNotExposeNumericUidWhenSecUidIsMissing() {
		VideoDataEntity video = new VideoDataEntity();
		video.setVideoplatform("douyin");
		video.setAuthoruid("84583932458");

		AdminMediaFeedItem item = service.toVideoFeedItemForTest(video);

		assertThat(item.getAuthoruid()).isNull();
		assertThat(item.getSecuid()).isNull();
	}

	@Test
	void findPageMergesVideosAndGraphicsByPublishTime() {
		VideoDataEntity request = new VideoDataEntity();
		request.setPageNo(1);
		request.setPageSize(2);
		AdminVideoListItem video = new AdminVideoListItem();
		video.setId(1);
		video.setVideoname("video");
		video.setPublishtime("2026-07-08 10:00:00");
		GraphicContentEntity graphic = new GraphicContentEntity();
		graphic.setId(2);
		graphic.setTitle("graphic");
		graphic.setPublishtime("2026-07-09 10:00:00");
		graphic.setImages("[\"/graphic.jpeg\"]");
		when(videoDataService.findPage(any(VideoDataEntity.class), eq(true)))
				.thenReturn(new AjaxEntity(Global.ajax_success, "success", new PageImpl<>(List.of(video), Pageable.ofSize(2), 1)));
		when(graphicContentService.findLitePage(any(GraphicContentEntity.class)))
				.thenReturn(new AjaxEntity(Global.ajax_success, "success", new PageImpl<>(List.of(graphic), Pageable.ofSize(2), 1)));

		AjaxEntity response = service.findPage(request);

		assertThat(response.getResCode()).isEqualTo(Global.ajax_success);
		assertThat(response.getRecord()).isInstanceOf(Page.class);
		Page<?> page = (Page<?>) response.getRecord();
		assertThat(page.getTotalElements()).isEqualTo(2);
		assertThat(page.getContent()).extracting("mediaKey").containsExactly("graphic:2", "video:1");
	}

	@Test
	void findPagePropagatesAuthorUidToVideoAndGraphicQueries() {
		VideoDataEntity request = new VideoDataEntity();
		request.setPageNo(1);
		request.setPageSize(2);
		request.setMediaType("mixed");
		request.setAuthoruid("MS4wLjABAAAAstable");
		request.setSecuid("MS4wLjABAAAAstable");
		when(videoDataService.findPage(any(VideoDataEntity.class), eq(true)))
				.thenReturn(new AjaxEntity(Global.ajax_success, "success", new PageImpl<>(List.of(), Pageable.ofSize(2), 0)));
		when(graphicContentService.findLitePage(any(GraphicContentEntity.class)))
				.thenReturn(new AjaxEntity(Global.ajax_success, "success", new PageImpl<>(List.of(), Pageable.ofSize(2), 0)));

		service.findPage(request);

		ArgumentCaptor<VideoDataEntity> videoCaptor = ArgumentCaptor.forClass(VideoDataEntity.class);
		ArgumentCaptor<GraphicContentEntity> graphicCaptor = ArgumentCaptor.forClass(GraphicContentEntity.class);
		verify(videoDataService).findPage(videoCaptor.capture(), eq(true));
		verify(graphicContentService).findLitePage(graphicCaptor.capture());
		assertThat(videoCaptor.getValue().getAuthoruid()).isEqualTo("MS4wLjABAAAAstable");
		assertThat(videoCaptor.getValue().getSecuid()).isEqualTo("MS4wLjABAAAAstable");
		assertThat(graphicCaptor.getValue().getAuthoruid()).isEqualTo("MS4wLjABAAAAstable");
		assertThat(graphicCaptor.getValue().getSecuid()).isEqualTo("MS4wLjABAAAAstable");
	}

	@Test
	void findPageVideoOnlySkipsGraphics() {
		VideoDataEntity request = new VideoDataEntity();
		request.setPageNo(1);
		request.setPageSize(2);
		request.setMediaType("video");
		AdminVideoListItem video = new AdminVideoListItem();
		video.setId(1);
		when(videoDataService.findPage(any(VideoDataEntity.class), eq(true)))
				.thenReturn(new AjaxEntity(Global.ajax_success, "success", new PageImpl<>(List.of(video), Pageable.ofSize(2), 1)));

		Page<?> page = (Page<?>) service.findPage(request).getRecord();

		assertThat(page.getContent()).extracting("mediaKey").containsExactly("video:1");
		verify(graphicContentService, never()).findLitePage(any(GraphicContentEntity.class));
	}

	@Test
	void findPageGraphicOnlySkipsVideos() {
		VideoDataEntity request = new VideoDataEntity();
		request.setPageNo(1);
		request.setPageSize(2);
		request.setMediaType("graphic");
		GraphicContentEntity graphic = new GraphicContentEntity();
		graphic.setId(2);
		graphic.setImages("[\"/graphic.jpeg\"]");
		when(graphicContentService.findLitePage(any(GraphicContentEntity.class)))
				.thenReturn(new AjaxEntity(Global.ajax_success, "success", new PageImpl<>(List.of(graphic), Pageable.ofSize(2), 1)));

		Page<?> page = (Page<?>) service.findPage(request).getRecord();

		assertThat(page.getContent()).extracting("mediaKey").containsExactly("graphic:2");
		verify(videoDataService, never()).findPage(any(VideoDataEntity.class), eq(true));
	}

	@Test
	void findPageUsesCreateTimeAndIdAsTieBreakers() {
		VideoDataEntity request = new VideoDataEntity();
		request.setPageNo(1);
		request.setPageSize(3);
		AdminVideoListItem olderVideo = new AdminVideoListItem();
		olderVideo.setId(10);
		olderVideo.setPublishtime("2026-07-08 10:00:00");
		olderVideo.setCreatetime(new Date(1_000L));
		AdminVideoListItem newerVideo = new AdminVideoListItem();
		newerVideo.setId(11);
		newerVideo.setPublishtime("2026-07-08 10:00:00");
		newerVideo.setCreatetime(new Date(2_000L));
		GraphicContentEntity graphic = new GraphicContentEntity();
		graphic.setId(12);
		graphic.setPublishtime("2026-07-08 10:00:00");
		graphic.setCreatetime(new Date(2_000L));
		graphic.setImages("[\"/graphic.jpeg\"]");
		when(videoDataService.findPage(any(VideoDataEntity.class), eq(true)))
				.thenReturn(new AjaxEntity(Global.ajax_success, "success", new PageImpl<>(List.of(olderVideo, newerVideo), Pageable.ofSize(3), 2)));
		when(graphicContentService.findLitePage(any(GraphicContentEntity.class)))
				.thenReturn(new AjaxEntity(Global.ajax_success, "success", new PageImpl<>(List.of(graphic), Pageable.ofSize(3), 1)));

		Page<?> page = (Page<?>) service.findPage(request).getRecord();

		assertThat(page.getContent()).extracting("mediaKey").containsExactly("graphic:12", "video:11", "video:10");
	}

	@Test
	void findPageIncludesNewestCreateTimeCandidatesWhenPublishTimeIsMissing() {
		VideoDataEntity request = new VideoDataEntity();
		request.setPageNo(1);
		request.setPageSize(2);
		request.setSortField("publishtime");
		request.setSortOrder("desc");

		AdminVideoListItem publishedVideo = new AdminVideoListItem();
		publishedVideo.setId(20);
		publishedVideo.setPublishtime("2026-07-08 10:00:00");
		publishedVideo.setCreatetime(new Date(1_000L));

		AdminVideoListItem freshMissingPublishTime = new AdminVideoListItem();
		freshMissingPublishTime.setId(21);
		freshMissingPublishTime.setCreatetime(Date.from(java.time.Instant.parse("2026-07-12T00:00:00Z")));

		when(videoDataService.findPage(any(VideoDataEntity.class), eq(true)))
				.thenReturn(new AjaxEntity(Global.ajax_success, "success",
						new PageImpl<>(List.of(publishedVideo), Pageable.ofSize(2), 2)))
				.thenReturn(new AjaxEntity(Global.ajax_success, "success",
						new PageImpl<>(List.of(freshMissingPublishTime), Pageable.ofSize(2), 2)));
		when(graphicContentService.findLitePage(any(GraphicContentEntity.class)))
				.thenReturn(new AjaxEntity(Global.ajax_success, "success", new PageImpl<>(List.of(), Pageable.ofSize(2), 0)))
				.thenReturn(new AjaxEntity(Global.ajax_success, "success", new PageImpl<>(List.of(), Pageable.ofSize(2), 0)));

		Page<?> page = (Page<?>) service.findPage(request).getRecord();

		assertThat(page.getContent()).extracting("mediaKey").containsExactly("video:21", "video:20");
	}
}
