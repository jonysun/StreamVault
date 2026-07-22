package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.entity.AuthorProfileEntity;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;

@ExtendWith(MockitoExtension.class)
class PlatformMetadataCompatibilityServiceTest {

	@Mock
	private AuthorProfileDao authorProfileDao;

	@Test
	void enrichesLegacyVideoWithoutChangingLegacyPlatform() {
		VideoDataEntity video = new VideoDataEntity();
		video.setVideoplatform("YouTube");

		new PlatformMetadataCompatibilityService(authorProfileDao).enrichVideo(video);

		assertThat(video.getVideoplatform()).isEqualTo("YouTube");
		assertThat(video.getPlatformkey()).isEqualTo("youtube");
		assertThat(video.getContenttype()).isEqualTo("video");
	}

	@Test
	void infersGraphicAndMixedContentFromStructuredMediaList() {
		GraphicContentEntity graphic = new GraphicContentEntity();
		graphic.setPlatform("rednote");
		graphic.setImages("[\"/one.jpg\",\"/two.webp\"]");
		PlatformMetadataCompatibilityService service = new PlatformMetadataCompatibilityService(authorProfileDao);

		service.enrichGraphic(graphic);
		assertThat(graphic.getPlatform()).isEqualTo("rednote");
		assertThat(graphic.getPlatformkey()).isEqualTo("xiaohongshu");
		assertThat(graphic.getContenttype()).isEqualTo("graphic");

		graphic.setContenttype(null);
		graphic.setImages("[\"/one.jpg\",\"/two.mp4\"]");
		service.enrichGraphic(graphic);
		assertThat(graphic.getContenttype()).isEqualTo("mixed");
	}

	@Test
	void fillsAuthorHomepageOnlyFromMatchingCanonicalProfile() {
		AuthorProfileEntity profile = new AuthorProfileEntity();
		profile.setHomepage("https://www.youtube.com/@author");
		when(authorProfileDao.findAllByPlatformkeyAndAuthoruidOrderByUpdatetimeDescIdDesc("youtube", "channel-1"))
				.thenReturn(List.of(profile));
		VideoDataEntity video = new VideoDataEntity();
		video.setVideoplatform("YouTube");
		video.setAuthoruid("channel-1");

		new PlatformMetadataCompatibilityService(authorProfileDao).enrichVideo(video);

		assertThat(video.getAuthorhomepage()).isEqualTo("https://www.youtube.com/@author");
	}

	@Test
	void toleratesDuplicateProfilesByUsingTheFirstOrderedRecord() {
		AuthorProfileEntity newest = new AuthorProfileEntity();
		newest.setHomepage("https://www.youtube.com/@newest");
		AuthorProfileEntity older = new AuthorProfileEntity();
		older.setHomepage("https://www.youtube.com/@older");
		when(authorProfileDao.findAllByPlatformkeyAndAuthoruidOrderByUpdatetimeDescIdDesc("youtube", "channel-1"))
				.thenReturn(List.of(newest, older));
		VideoDataEntity video = new VideoDataEntity();
		video.setVideoplatform("YouTube");
		video.setAuthoruid("channel-1");

		new PlatformMetadataCompatibilityService(authorProfileDao).enrichVideo(video);

		assertThat(video.getAuthorhomepage()).isEqualTo("https://www.youtube.com/@newest");
	}

	@Test
	void preservesExplicitCanonicalValuesAndHomepage() {
		VideoDataEntity video = new VideoDataEntity();
		video.setVideoplatform("Legacy Label");
		video.setPlatformkey("custom_platform");
		video.setContenttype("video");
		video.setAuthorhomepage("https://example.com/author");

		new PlatformMetadataCompatibilityService(authorProfileDao).enrichVideo(video);

		assertThat(video.getPlatformkey()).isEqualTo("custom_platform");
		assertThat(video.getContenttype()).isEqualTo("video");
		assertThat(video.getAuthorhomepage()).isEqualTo("https://example.com/author");
	}

	@Test
	void expandsKnownPlatformFiltersAndPreservesUnknownFilters() {
		assertThat(PlatformMetadataCompatibilityService.resolveFilterAliases("rednote"))
				.contains("xiaohongshu", "rednote", "xhs", "\u5c0f\u7ea2\u4e66");
		assertThat(PlatformMetadataCompatibilityService.resolveFilterAliases("Custom Source"))
				.containsExactly("custom source");
	}
}
