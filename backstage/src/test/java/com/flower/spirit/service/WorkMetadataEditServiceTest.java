package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.dto.UpdateWorkMetadataRequest;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.WorkMetadataNormalizer;
import com.flower.spirit.platform.WorkMetadataValidationException;

@ExtendWith(MockitoExtension.class)
class WorkMetadataEditServiceTest {

	@Mock private VideoDataDao videoDataDao;
	@Mock private GraphicContentDao graphicContentDao;
	@Mock private AuthorProfileService authorProfileService;

	private WorkMetadataEditService service;

	@BeforeEach
	void setUp() {
		service = new WorkMetadataEditService(videoDataDao, graphicContentDao, authorProfileService,
				new WorkMetadataNormalizer(ZoneId.of("UTC")));
	}

	@Test
	void updatesVideoEditableFieldsPreservesLockedFieldsAndSyncsAuthorExplicitly() {
		VideoDataEntity video = video();
		when(videoDataDao.findById(7)).thenReturn(Optional.of(video));
		when(videoDataDao.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("title", "edited title");
		values.put("description", null);
		values.put("publishTime", "20240102");
		values.put("sourceUrl", "https://youtube.com/watch?v=work-1");
		values.put("privacy", "1");
		values.put("favorite", "1");
		UpdateWorkMetadataRequest request = request("video", 7, values, true);

		WorkMetadataEditService.EditResult result = service.update(request, "admin");

		assertThat(result.profileSynced()).isTrue();
		assertThat(video.getVideoname()).isEqualTo("edited title");
		assertThat(video.getVideodesc()).isNull();
		assertThat(video.getPublishtime()).isEqualTo("2024-01-02 00:00:00");
		assertThat(video.getVideoprivacy()).isEqualTo("1");
		assertThat(video.getFavorite()).isEqualTo("1");
		assertThat(video.getPlatformkey()).isEqualTo("youtube");
		assertThat(video.getVideoid()).isEqualTo("work-1");
		assertThat(video.getVideoaddr()).isEqualTo("C:/media/video.mp4");
		assertThat(video.getJsonData()).isEqualTo("raw-json");
		assertThat(video.getMetadataeditedby()).isEqualTo("admin");
		JSONObject stored = JSON.parseObject(video.getMetadataoverrides());
		assertThat(stored.containsKey("description")).isTrue();
		assertThat(stored.get("description")).isNull();
		assertThat(stored.getString("title")).isEqualTo("edited title");
		verify(authorProfileService).upsertCanonicalAuthor("youtube", "YouTube", "channel-1", "creator",
				"Creator", "avatar.jpg", "https://youtube.com/@creator");
	}

	@Test
	void updatesGraphicFieldsWithoutImplicitAuthorSync() {
		GraphicContentEntity graphic = new GraphicContentEntity();
		graphic.setId(8);
		graphic.setPlatformkey("xiaohongshu");
		graphic.setPlatform("小红书");
		graphic.setAuthoruid("author-8");
		graphic.setImages("[\"C:/media/one.jpg\"]");
		when(graphicContentDao.findById(8)).thenReturn(Optional.of(graphic));
		when(graphicContentDao.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.update(request("graphic", 8, Map.of("description", "edited", "tags", "tag-a",
				"author", "New Author"), false), "admin");

		assertThat(graphic.getContent()).isEqualTo("edited");
		assertThat(graphic.getTags()).isEqualTo("tag-a");
		assertThat(graphic.getAuthor()).isEqualTo("New Author");
		assertThat(graphic.getImages()).contains("one.jpg");
		verify(authorProfileService, never()).upsertCanonicalAuthor(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void rejectsLockedFieldsAndInvalidUrlsBeforeSaving() {
		assertThatThrownBy(() -> service.update(request("video", 7, Map.of("platformkey", "twitter"), false), "admin"))
				.isInstanceOf(WorkMetadataValidationException.class)
				.hasMessageContaining("not editable");
		VideoDataEntity video = video();
		when(videoDataDao.findById(7)).thenReturn(Optional.of(video));
		assertThatThrownBy(() -> service.update(request("video", 7, Map.of("sourceUrl", "file:///tmp/video"), false), "admin"))
				.isInstanceOf(WorkMetadataValidationException.class)
				.hasMessageContaining("HTTP(S)");
		verify(videoDataDao, never()).save(any());
	}

	@Test
	void keepsWorkEditWhenExplicitAuthorProfileSyncFails() {
		VideoDataEntity video = video();
		when(videoDataDao.findById(7)).thenReturn(Optional.of(video));
		when(videoDataDao.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		doThrow(new IllegalStateException("profile unavailable")).when(authorProfileService)
				.upsertCanonicalAuthor(any(), any(), any(), any(), any(), any(), any());

		WorkMetadataEditService.EditResult result = service.update(request("video", 7,
				Map.of("title", "saved despite profile failure"), true), "admin");

		assertThat(result.profileSynced()).isFalse();
		assertThat(video.getVideoname()).isEqualTo("saved despite profile failure");
		verify(videoDataDao).save(video);
	}

	@Test
	void rejectsLockedFieldsStoredInMetadataOverrides() {
		VideoDataEntity video = video();
		video.setMetadataoverrides("{\"platformkey\":\"twitter\"}");

		assertThatThrownBy(() -> service.reapplyStoredOverrides(video))
				.isInstanceOf(WorkMetadataValidationException.class)
				.hasMessageContaining("stored metadata overrides are invalid");
		assertThat(video.getPlatformkey()).isEqualTo("youtube");
	}

	private VideoDataEntity video() {
		VideoDataEntity video = new VideoDataEntity();
		video.setId(7);
		video.setPlatformkey("youtube");
		video.setVideoplatform("YouTube");
		video.setVideoid("work-1");
		video.setVideoauthor("Creator");
		video.setAuthoruid("channel-1");
		video.setAuthorusername("creator");
		video.setAuthoravatar("avatar.jpg");
		video.setAuthorhomepage("https://youtube.com/@creator");
		video.setVideoaddr("C:/media/video.mp4");
		video.setJsonData("raw-json");
		video.setMetadataoverrides("{\"tags\":\"existing\"}");
		return video;
	}

	private UpdateWorkMetadataRequest request(String type, int id, Map<String, Object> values, boolean sync) {
		UpdateWorkMetadataRequest request = new UpdateWorkMetadataRequest();
		request.setWorkType(type);
		request.setId(id);
		request.setOverrides(values);
		request.setSyncAuthorProfile(sync);
		return request;
	}
}
