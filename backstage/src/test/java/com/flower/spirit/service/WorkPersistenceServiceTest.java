package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.alibaba.fastjson.JSON;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataNormalizer;
import com.flower.spirit.service.WorkDeduplicationService.ExistingWork;

@ExtendWith(MockitoExtension.class)
class WorkPersistenceServiceTest {

	@Mock private WorkDeduplicationService deduplicationService;
	@Mock private VideoDataDao videoDataDao;
	@Mock private GraphicContentDao graphicContentDao;
	@Mock private AuthorProfileService authorProfileService;

	private WorkPersistenceService service;

	@BeforeEach
	void setUp() {
		service = new WorkPersistenceService(new WorkMetadataNormalizer(ZoneId.of("UTC")), deduplicationService,
				videoDataDao, graphicContentDao, authorProfileService);
		when(deduplicationService.findExisting(any())).thenReturn(Optional.empty());
	}

	@Test
	void routesVideoAndMapsCanonicalAndLegacyFields() {
		when(videoDataDao.save(any())).thenAnswer(invocation -> {
			VideoDataEntity entity = invocation.getArgument(0);
			entity.setId(11);
			return entity;
		});

		WorkPersistenceService.PersistenceResult result = service.persist(metadata(WorkContentType.VIDEO,
				List.of(resource(0, WorkMediaResource.Type.VIDEO, "C:/media/video.mp4"))));

		VideoDataEntity video = result.video();
		assertThat(result.created()).isTrue();
		assertThat(video.getPlatformkey()).isEqualTo("youtube");
		assertThat(video.getVideoplatform()).isEqualTo("YouTube");
		assertThat(video.getVideoid()).isEqualTo("work-1");
		assertThat(video.getContenttype()).isEqualTo("video");
		assertThat(video.getVideoname()).isEqualTo("title");
		assertThat(video.getVideodesc()).isEqualTo("description");
		assertThat(video.getVideoauthor()).isEqualTo("author");
		assertThat(video.getAuthorhomepage()).isEqualTo("https://youtube.com/@author");
		assertThat(video.getSourceurl()).isEqualTo("https://youtube.com/watch?v=work-1");
		assertThat(video.getJsonData()).isEqualTo("{\"id\":\"work-1\"}");
		assertThat(video.getVideoaddr()).endsWith("video.mp4");
		verify(authorProfileService).upsertCanonicalAuthor("youtube", "YouTube", "author-1", "author-name",
				"author", "https://cdn.example/avatar.jpg", "https://youtube.com/@author");
	}

	@Test
	void routesMixedMediaToGraphicAndPreservesOrder() {
		when(graphicContentDao.save(any())).thenAnswer(invocation -> {
			GraphicContentEntity entity = invocation.getArgument(0);
			entity.setId(12);
			return entity;
		});

		WorkPersistenceService.PersistenceResult result = service.persist(metadata(WorkContentType.MIXED, List.of(
				resource(1, WorkMediaResource.Type.VIDEO, "C:/media/second.mp4"),
				resource(0, WorkMediaResource.Type.IMAGE, "C:/media/first.jpg"))));

		GraphicContentEntity graphic = result.graphic();
		assertThat(result.contentType()).isEqualTo(WorkContentType.MIXED);
		assertThat(graphic.getContenttype()).isEqualTo("mixed");
		assertThat(JSON.parseArray(graphic.getImages(), String.class))
				.containsExactly("C:\\media\\first.jpg", "C:\\media\\second.mp4");
		assertThat(graphic.getMarkroute()).isEqualTo("C:\\media");
	}

	@Test
	void updatePreservesLocalStateAndOverrides() {
		VideoDataEntity existing = new VideoDataEntity();
		existing.setId(20);
		existing.setVideotag("local-tag");
		existing.setVideoprivacy("1");
		existing.setFavorite("1");
		existing.setMetadataoverrides("{\"title\":\"manual\"}");
		existing.setVideoaddr("C:/old/video.mp4");
		when(deduplicationService.findExisting(any())).thenReturn(Optional.of(ExistingWork.video(existing)));
		when(videoDataDao.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		WorkPersistenceService.PersistenceResult result = service.persist(metadata(WorkContentType.VIDEO,
				List.of(remoteVideoResource())));

		assertThat(result.created()).isFalse();
		assertThat(existing.getVideotag()).isEqualTo("local-tag");
		assertThat(existing.getVideoprivacy()).isEqualTo("1");
		assertThat(existing.getFavorite()).isEqualTo("1");
		assertThat(existing.getMetadataoverrides()).contains("manual");
		assertThat(existing.getVideoaddr()).isEqualTo("C:/old/video.mp4");
	}

	private WorkMetadata metadata(WorkContentType type, List<WorkMediaResource> resources) {
		return WorkMetadata.builder()
				.platform(PlatformCatalog.requireByKey("youtube"))
				.workId("work-1")
				.contentType(type)
				.title("title")
				.description("description")
				.authorId("author-1")
				.authorUsername("author-name")
				.authorName("author")
				.authorAvatar("https://cdn.example/avatar.jpg")
				.authorHomepage("https://youtube.com/@author")
				.publishTime("20240101")
				.sourceUrl("https://youtube.com/watch?v=work-1")
				.originalAddress("shared text")
				.coverUrl("https://cdn.example/cover.jpg")
				.mediaResources(resources)
				.rawMetadata("{\"id\":\"work-1\"}")
				.build();
	}

	private WorkMediaResource resource(int order, WorkMediaResource.Type type, String path) {
		return new WorkMediaResource(order, type, null, Path.of(path), null, Map.of());
	}

	private WorkMediaResource remoteVideoResource() {
		return new WorkMediaResource(0, WorkMediaResource.Type.VIDEO, "https://cdn.example/video.mp4", null,
				"mp4", Map.of());
	}
}
