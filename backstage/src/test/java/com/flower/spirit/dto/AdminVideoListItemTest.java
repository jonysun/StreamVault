package com.flower.spirit.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.beans.Introspector;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.flower.spirit.entity.VideoDataEntity;

class AdminVideoListItemTest {

	@Test
	void fromKeepsAdminIndexFieldsButExcludesLargeAndInternalFields() throws Exception {
		VideoDataEntity video = new VideoDataEntity();
		video.setId(12);
		video.setVideoid("video-1");
		video.setVideoname("title");
		video.setVideodesc("summary");
		video.setVideoplatform("douyin");
		video.setVideocover("cover.jpg");
		video.setVideounrealaddr("/video.mp4");
		video.setPlayurl("/video.m3u8");
		video.setVideoprivacy("0");
		video.setVideotag("tag");
		video.setVideoauthor("author");
		video.setAuthoruid("84583932458");
		video.setSecuid("MS4wLjABAAAAstable");
		video.setUniqueid("author-name");
		video.setPublishtime("2026-06-28 11:17:55");
		video.setCreatetime(new Date(1_000L));
		video.setHlsstatus("ready");
		video.setSourceurl("https://example.test/source");
		video.setFavorite("1");
		video.setOriginaladdress("https://example.test/original");
		video.setJsonData("{large:true}");
		video.setVideoinfo("{large:true}");
		video.setVideoaddr("D:/internal/video.mp4");

		AdminVideoListItem item = AdminVideoListItem.from(video);
		Set<String> properties = Arrays.stream(Introspector.getBeanInfo(AdminVideoListItem.class).getPropertyDescriptors())
				.map(descriptor -> descriptor.getName())
				.collect(Collectors.toSet());

		assertThat(item.getId()).isEqualTo(12);
		assertThat(item.getVideoname()).isEqualTo("title");
		assertThat(item.getVideodesc()).isEqualTo("summary");
		assertThat(item.getVideoauthor()).isEqualTo("author");
		assertThat(item.getAuthoruid()).isEqualTo("MS4wLjABAAAAstable");
		assertThat(item.getSecuid()).isEqualTo("MS4wLjABAAAAstable");
		assertThat(item.getAuthorusername()).isEqualTo("author-name");
		assertThat(item.getUniqueid()).isEqualTo("author-name");
		assertThat(item.getPublishtime()).isEqualTo("2026-06-28 11:17:55");
		assertThat(item.getPlayurl()).isEqualTo("/video.m3u8");
		assertThat(item.getVideounrealaddr()).isEqualTo("/video.mp4");
		assertThat(item.getOriginaladdress()).isEqualTo("https://example.test/original");
		assertThat(properties).doesNotContain("jsonData", "videoinfo", "videoaddr");
	}

	@Test
	void fromSuppressesNumericDouyinUidWithoutCanonicalSecUid() {
		VideoDataEntity video = new VideoDataEntity();
		video.setVideoplatform("douyin");
		video.setAuthoruid("84583932458");

		AdminVideoListItem item = AdminVideoListItem.from(video);

		assertThat(item.getAuthoruid()).isNull();
		assertThat(item.getSecuid()).isNull();
	}
}
