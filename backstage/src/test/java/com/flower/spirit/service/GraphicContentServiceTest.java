package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
}
