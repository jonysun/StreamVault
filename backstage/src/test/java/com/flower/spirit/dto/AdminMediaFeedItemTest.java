package com.flower.spirit.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.beans.Introspector;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class AdminMediaFeedItemTest {

	@Test
	void dtoContainsMixedFeedFieldsAndExcludesLargeFields() throws Exception {
		AdminMediaFeedItem item = new AdminMediaFeedItem();
		item.setType("graphic");
		item.setId(7);
		item.setMediaKey("graphic:7");
		item.setTitle("title");
		item.setSlides(List.of(new AdminMediaSlide("image", "/cover.jpeg")));

		Set<String> props = Arrays.stream(Introspector.getBeanInfo(AdminMediaFeedItem.class).getPropertyDescriptors())
				.map(descriptor -> descriptor.getName())
				.collect(Collectors.toSet());

		assertThat(item.getMediaKey()).isEqualTo("graphic:7");
		assertThat(item.getSlides()).extracting(AdminMediaSlide::getType).containsExactly("image");
		assertThat(props).contains("type", "mediaKey", "title", "desc", "author", "slides", "playurl", "fallbackUrl");
		assertThat(props).doesNotContain("jsonData", "videoinfo", "videoaddr");
	}

	@Test
	void nullSlidesBecomeEmptyList() {
		AdminMediaFeedItem item = new AdminMediaFeedItem();

		item.setSlides(null);

		assertThat(item.getSlides()).isEmpty();
	}
}
