package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;

@ExtendWith(MockitoExtension.class)
class WorkDeduplicationServiceTest {

	@Mock
	private VideoDataDao videoDataDao;

	@Mock
	private GraphicContentDao graphicContentDao;

	@Mock
	private BlockedWorkService blockedWorkService;

	private WorkDeduplicationService service;

	@BeforeEach
	void setUp() {
		service = new WorkDeduplicationService(videoDataDao, graphicContentDao, blockedWorkService);
	}

	@Test
	void findsCanonicalMatchBeforeLegacyFallback() {
		VideoDataEntity existing = new VideoDataEntity();
		existing.setId(7);
		existing.setContenttype("video");
		when(videoDataDao.findByPlatformkeyAndVideoid("youtube", "work-1")).thenReturn(List.of(existing));

		assertThat(service.findExisting(metadata()).orElseThrow().video()).isSameAs(existing);
	}

	@Test
	void findsLegacyAliasMatchForOldRows() {
		VideoDataEntity existing = new VideoDataEntity();
		existing.setId(8);
		when(videoDataDao.findByPlatformkeyAndVideoid("youtube", "work-1")).thenReturn(List.of());
		when(videoDataDao.findByVideoidAndVideoplatformIn("work-1",
				List.of("youtube", "YouTube", "youtu.be"))).thenReturn(List.of(existing));

		assertThat(service.findExisting(metadata()).orElseThrow().video()).isSameAs(existing);
	}

	@Test
	void checksBlockedWorkAcrossPlatformAliases() {
		when(blockedWorkService.isBlocked("youtube", "work-1", "video")).thenReturn(false);
		when(blockedWorkService.isBlocked("YouTube", "work-1", "video")).thenReturn(true);

		assertThatThrownBy(() -> service.assertNotBlocked(metadata()))
				.isInstanceOf(WorkMetadataValidationException.class)
				.hasMessageContaining("blocked");
	}

	@Test
	void genericWorkWithoutIdStillDeduplicatesByPlatformAndOriginalAddress() {
		VideoDataEntity existing = new VideoDataEntity();
		existing.setId(9);
		WorkMetadata generic = WorkMetadata.builder()
				.platformKey("vimeo")
				.platformDisplayName("Vimeo")
				.supportTier(com.flower.spirit.platform.PlatformSupportTier.GENERIC)
				.contentType(WorkContentType.VIDEO)
				.originalAddress("https://vimeo.com/123")
				.build();
		when(videoDataDao.findByOriginaladdressAndVideoplatformIn("https://vimeo.com/123",
				List.of("vimeo", "Vimeo"))).thenReturn(List.of(existing));

		assertThat(service.findExisting(generic).orElseThrow().video()).isSameAs(existing);
	}

	private WorkMetadata metadata() {
		return WorkMetadata.builder()
				.platform(PlatformCatalog.requireByKey("youtube"))
				.workId("work-1")
				.contentType(WorkContentType.VIDEO)
				.build();
	}
}
