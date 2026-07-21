package com.flower.spirit.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkParseRequest;

class PlatformAdapterRegistryTest {

	@Test
	void selectsExactlyOneAdapterForFormalPlatformAndInput() {
		PlatformWorkAdapter youtube = adapter("youtube", "youtu");
		PlatformAdapterRegistry registry = new PlatformAdapterRegistry(List.of(youtube, adapter("douyin", "douyin")));

		assertThat(registry.requireByPlatformKey("YouTube")).isSameAs(youtube);
		assertThat(registry.findSupporting("https://youtu.be/1")).contains(youtube);
		assertThat(registry.size()).isEqualTo(2);
	}

	@Test
	void rejectsDuplicateCanonicalPlatformRegistrations() {
		assertThatThrownBy(() -> new PlatformAdapterRegistry(List.of(adapter("youtube", "youtu"),
				adapter("YouTube", "youtube"))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("youtube");
	}

	@Test
	void rejectsAmbiguousInputOwnership() {
		PlatformAdapterRegistry registry = new PlatformAdapterRegistry(
				List.of(adapter("youtube", "video"), adapter("twitter", "video")));

		assertThatThrownBy(() -> registry.findSupporting("https://example.com/video"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("multiple platform adapters");
	}

	@Test
	void fallsBackToGenericOnlyForUnknownStoredPlatformKeys() {
		PlatformWorkAdapter generic = adapter("generic", "generic-host");
		PlatformAdapterRegistry registry = new PlatformAdapterRegistry(List.of(generic));

		assertThat(registry.requireByPlatformKey("vimeo_on_demand")).isSameAs(generic);
		assertThatThrownBy(() -> registry.requireByPlatformKey("youtube"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("youtube");
	}

	private PlatformWorkAdapter adapter(String platformKey, String marker) {
		return new PlatformWorkAdapter() {
			@Override
			public String platformKey() {
				return platformKey;
			}

			@Override
			public boolean supports(String input) {
				return input != null && input.contains(marker);
			}

			@Override
			public WorkMetadata parse(WorkParseRequest request) {
				throw new UnsupportedOperationException();
			}

			@Override
			public DownloadResult download(WorkMetadata metadata, WorkDownloadRequest request) {
				throw new UnsupportedOperationException();
			}
		};
	}
}
