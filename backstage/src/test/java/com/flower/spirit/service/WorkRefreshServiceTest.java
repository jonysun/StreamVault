package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.dto.WorkOperationRequest;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataNormalizer;
import com.flower.spirit.platform.WorkParseRequest;
import com.flower.spirit.platform.adapter.PlatformAdapterRegistry;
import com.flower.spirit.platform.adapter.PlatformWorkAdapter;
import com.flower.spirit.service.WorkPersistenceService.PersistenceResult;

@ExtendWith(MockitoExtension.class)
class WorkRefreshServiceTest {

	@Mock private VideoDataDao videoDataDao;
	@Mock private GraphicContentDao graphicContentDao;
	@Mock private PlatformAdapterRegistry adapterRegistry;
	@Mock private PlatformWorkAdapter adapter;
	@Mock private WorkPersistenceService persistenceService;
	@Mock private WorkMetadataEditService editService;

	private WorkRefreshService service;

	@BeforeEach
	void setUp() {
		service = new WorkRefreshService(videoDataDao, graphicContentDao, adapterRegistry,
				new WorkMetadataNormalizer(ZoneId.of("UTC")), persistenceService, editService);
	}

	@Test
	void refreshReparsesWithoutDownloadLocksIdentityAndReappliesOverrides() {
		VideoDataEntity existing = new VideoDataEntity();
		existing.setId(7);
		existing.setPlatformkey("youtube");
		existing.setVideoplatform("YouTube");
		existing.setVideoid("locked-id");
		existing.setSourceurl("https://youtube.com/watch?v=locked-id");
		existing.setOriginaladdress("shared text");
		existing.setVideotag("local-tag");
		existing.setFavorite("1");
		when(videoDataDao.findById(7)).thenReturn(Optional.of(existing));
		when(adapterRegistry.requireByPlatformKey("youtube")).thenReturn(adapter);
		when(adapter.parse(any())).thenReturn(latestMetadata());
		when(persistenceService.persist(any())).thenReturn(PersistenceResult.video(false, existing));
		when(videoDataDao.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		PersistenceResult result = service.refresh(request("video", 7));

		assertThat(result.id()).isEqualTo(7);
		ArgumentCaptor<WorkParseRequest> parseRequest = ArgumentCaptor.forClass(WorkParseRequest.class);
		verify(adapter).parse(parseRequest.capture());
		assertThat(parseRequest.getValue().getUrl()).isEqualTo(existing.getSourceurl());
		assertThat(parseRequest.getValue().isPreview()).isTrue();
		verify(adapter, never()).download(any(), any());
		ArgumentCaptor<WorkMetadata> metadata = ArgumentCaptor.forClass(WorkMetadata.class);
		verify(persistenceService).persist(metadata.capture());
		assertThat(metadata.getValue().getWorkId()).isEqualTo("locked-id");
		assertThat(metadata.getValue().getPlatformKey()).isEqualTo("youtube");
		assertThat(metadata.getValue().getTitle()).isEqualTo("latest title");
		assertThat(metadata.getValue().getOriginalAddress()).isEqualTo("shared text");
		verify(editService).reapplyStoredOverrides(existing);
		assertThat(existing.getVideotag()).isEqualTo("local-tag");
		assertThat(existing.getFavorite()).isEqualTo("1");
	}

	private WorkMetadata latestMetadata() {
		return WorkMetadata.builder()
				.platform(PlatformCatalog.requireByKey("youtube"))
				.workId("parser-id")
				.contentType(WorkContentType.VIDEO)
				.title("latest title")
				.sourceUrl("https://youtube.com/watch?v=locked-id")
				.mediaResources(List.of(new WorkMediaResource(0, WorkMediaResource.Type.VIDEO,
						"https://cdn.example/video.mp4", null, "mp4", Map.of())))
				.rawMetadata("latest-json")
				.build();
	}

	private WorkOperationRequest request(String type, int id) {
		WorkOperationRequest request = new WorkOperationRequest();
		request.setWorkType(type);
		request.setId(id);
		return request;
	}
}
