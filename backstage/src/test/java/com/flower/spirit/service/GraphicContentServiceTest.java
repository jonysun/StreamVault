package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.entity.GraphicContentEntity;

import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
class GraphicContentServiceTest {

	@Mock
	private GraphicContentDao graphicContentDao;

	@Mock
	private EntityManager entityManager;

	@Mock
	private BlockedWorkService blockedWorkService;

	@Mock
	private DouyinWorkMaintenanceService douyinWorkMaintenanceService;

	@InjectMocks
	private GraphicContentService service;

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void findPageSanitizesDouyinIdentityWithoutMutatingStoredEntity() {
		GraphicContentEntity stored = new GraphicContentEntity();
		stored.setId(7);
		stored.setPlatform("douyin");
		stored.setAuthoruid("84583932458");
		stored.setSecuid("MS4wLjABAAAAstable");
		stored.setUniqueid("public_handle");
		when(graphicContentDao.findAll(any(Specification.class), any(Pageable.class)))
				.thenAnswer(invocation -> new PageImpl<>(List.of(stored), invocation.getArgument(1), 1));

		Page<GraphicContentEntity> response = (Page<GraphicContentEntity>) service
				.findPage(new GraphicContentEntity())
				.getRecord();

		assertThat(response.getContent()).hasSize(1);
		GraphicContentEntity item = response.getContent().get(0);
		assertThat(item).isNotSameAs(stored);
		assertThat(item.getAuthoruid()).isEqualTo("MS4wLjABAAAAstable");
		assertThat(item.getSecuid()).isEqualTo("MS4wLjABAAAAstable");
		assertThat(item.getAuthorusername()).isEqualTo("public_handle");
		assertThat(item.getUniqueid()).isEqualTo("public_handle");
		assertThat(stored.getAuthoruid()).isEqualTo("84583932458");
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void findPageSuppressesNumericDouyinUidWithoutCanonicalSecUid() {
		GraphicContentEntity stored = new GraphicContentEntity();
		stored.setPlatform("douyin");
		stored.setAuthoruid("84583932458");
		when(graphicContentDao.findAll(any(Specification.class), any(Pageable.class)))
				.thenAnswer(invocation -> new PageImpl<>(List.of(stored), invocation.getArgument(1), 1));

		Page<GraphicContentEntity> response = (Page<GraphicContentEntity>) service
				.findPage(new GraphicContentEntity())
				.getRecord();

		assertThat(response.getContent().get(0).getAuthoruid()).isNull();
		assertThat(response.getContent().get(0).getSecuid()).isNull();
	}

	@Test
	void findGraphicDetailExtractsMusicFieldsAndPlaybackUrls() {
		GraphicContentEntity graphic = new GraphicContentEntity();
		graphic.setId(11686);
		graphic.setVideoid("7695790567728543458");
		graphic.setPlatform("抖音");
		graphic.setJsonData("{\"aweme_detail\":{\"music\":{\"id\":\"music-1\","
				+ "\"title\":\"Track\",\"author\":\"Artist\",\"duration\":123,"
				+ "\"play_url\":{\"url_list\":[\"https://media.example/a.mp3\","
				+ "\"https://backup.example/a.mp3\"]}}}}");
		when(graphicContentDao.findById(11686)).thenReturn(Optional.of(graphic));

		Map<String, Object> detail = service.findGraphicDetail(11686);

		assertThat(detail).containsEntry("musicName", "Track")
				.containsEntry("musicAuthor", "Artist")
				.containsEntry("musicId", "music-1")
				.containsEntry("musicDuration", 123)
				.containsEntry("musicPlayUrl", List.of("https://media.example/a.mp3",
						"https://backup.example/a.mp3"));
	}

	@Test
	void findGraphicDetailReturnsEmptyMusicWhenRawMetadataHasNoMusic() {
		GraphicContentEntity graphic = new GraphicContentEntity();
		graphic.setId(11686);
		graphic.setJsonData("{\"aweme_detail\":{\"desc\":\"no music\"}}");
		when(graphicContentDao.findById(11686)).thenReturn(Optional.of(graphic));

		Map<String, Object> detail = service.findGraphicDetail(11686);

		assertThat(detail).containsEntry("music", null)
				.containsEntry("musicPlayUrl", List.of());
	}

	@Test
	void findGraphicDetailExtractsMusicFromNestedProviderPayload() {
		GraphicContentEntity graphic = new GraphicContentEntity();
		graphic.setId(11687);
		graphic.setJsonData("{\"data\":{\"aweme_detail\":{\"music\":{"
				+ "\"id\":\"nested-music\",\"title\":\"Nested Track\","
				+ "\"play_url\":{\"url_list\":[\"https://media.example/nested.mp3\"]}}}}}");
		when(graphicContentDao.findById(11687)).thenReturn(Optional.of(graphic));

		Map<String, Object> detail = service.findGraphicDetail(11687);

		assertThat(detail).containsEntry("musicName", "Nested Track")
				.containsEntry("musicId", "nested-music")
				.containsEntry("musicPlayUrl", List.of("https://media.example/nested.mp3"));
	}

	@Test
	void findGraphicDetailExtractsMusicFromDownloadSnapshotFallback() {
		GraphicContentEntity graphic = new GraphicContentEntity();
		graphic.setId(11688);
		graphic.setJsonData("{\"download_snapshot\":{\"music\":{"
				+ "\"id\":\"snapshot-music\",\"title\":\"Snapshot Track\"}}}");
		when(graphicContentDao.findById(11688)).thenReturn(Optional.of(graphic));

		Map<String, Object> detail = service.findGraphicDetail(11688);

		assertThat(detail).containsEntry("musicName", "Snapshot Track")
				.containsEntry("musicId", "snapshot-music");
	}
}
