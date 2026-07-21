package com.flower.spirit.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.platform.WorkParseRequest;
import com.flower.spirit.service.PlatformCookieService;
import com.flower.spirit.utils.KuaishouParser.VideoInfo;

@ExtendWith(MockitoExtension.class)
class KuaishouPlatformAdapterTest {

	@TempDir Path tempDir;
	@Mock private PlatformCookieService cookieService;

	private FakeGateway gateway;
	private KuaishouPlatformAdapter adapter;

	@BeforeEach
	void setUp() {
		gateway = new FakeGateway(video());
		adapter = new KuaishouPlatformAdapter(new PlatformResolver(), cookieService, gateway);
	}

	@Test
	void recognizesShortAndCanonicalKuaishouLinksOnly() {
		assertThat(adapter.supports("https://v.kuaishou.com/fixture")).isTrue();
		assertThat(adapter.supports("https://www.kuaishou.com/short-video/ks-video-1")).isTrue();
		assertThat(adapter.supports("https://youtube.com/watch?v=1")).isFalse();
	}

	@Test
	void previewPrefersH265MapsAuthorIdentityAndDoesNotExposeCookie() {
		when(cookieService.currentKuaishouCookie("single_work_parse")).thenReturn("secret-cookie");

		WorkMetadata metadata = adapter.parse(new WorkParseRequest("shared text",
				"https://v.kuaishou.com/fixture", true));

		assertThat(metadata.getPlatformKey()).isEqualTo("kuaishou");
		assertThat(metadata.getWorkId()).isEqualTo("ks-video-1");
		assertThat(metadata.getSourceUrl()).isEqualTo("https://www.kuaishou.com/short-video/ks-video-1");
		assertThat(metadata.getAuthorAvatar()).isEqualTo("https://media.example/ks-avatar.jpg");
		assertThat(metadata.getAuthorHomepage()).isEqualTo("https://www.kuaishou.com/profile/ks-author-1");
		assertThat(metadata.getCoverUrl()).isEqualTo("https://media.example/ks-cover.jpg");
		assertThat(metadata.getMediaResources()).singleElement().satisfies(resource -> {
			assertThat(resource.getSourceUrl()).isEqualTo("https://media.example/ks-h265.mp4");
			assertThat(resource.getRequestHeaders()).containsEntry("Referer", "https://www.kuaishou.com/");
			assertThat(resource.getRequestHeaders()).doesNotContainKeys("Cookie", "cookie");
		});
		assertThat(gateway.parseCookie).isEqualTo("secret-cookie");
		assertThat(gateway.downloadCalls).isZero();
		verify(cookieService).reportSuccess(anyString(), eq("secret-cookie"));
	}

	@Test
	void fallsBackToH264AndDownloadsSynchronouslyWithCookieOutOfBand() throws Exception {
		VideoInfo h264Only = video();
		h264Only.setH265Url(null);
		gateway.video = h264Only;
		when(cookieService.currentKuaishouCookie("single_work_parse")).thenReturn("parse-cookie");
		when(cookieService.currentKuaishouCookie("single_work_download")).thenReturn("download-cookie");
		WorkMetadata metadata = adapter.parse(new WorkParseRequest("input",
				"https://www.kuaishou.com/short-video/ks-video-1", false));

		DownloadResult result = adapter.download(metadata, new WorkDownloadRequest(tempDir, false));

		assertThat(gateway.downloadUrl).isEqualTo("https://media.example/ks-h264.mp4");
		assertThat(gateway.downloadCookie).isEqualTo("download-cookie");
		assertThat(gateway.downloadHeaders).doesNotContainKeys("Cookie", "cookie");
		assertThat(result.getStatus()).isEqualTo(DownloadResult.Status.COMPLETED);
		assertThat(result.getMediaResources()).singleElement().extracting(WorkMediaResource::getLocalPath)
				.isEqualTo(tempDir.resolve("ks-video-1.mp4"));
		assertThat(Files.readString(tempDir.resolve("ks-video-1.mp4"))).isEqualTo("video");
		verify(cookieService).reportSuccess(anyString(), eq("download-cookie"));
	}

	@Test
	void reportsRiskSignalsAndRejectsMissingCookie() throws Exception {
		when(cookieService.currentKuaishouCookie("single_work_parse")).thenReturn("risk-cookie");
		when(cookieService.isRiskSignal("Need captcha verify")).thenReturn(true);
		gateway.parseFailure = new IOException("Need captcha verify");

		assertThatThrownBy(() -> adapter.parse(new WorkParseRequest("input",
				"https://v.kuaishou.com/fixture", true)))
				.isInstanceOf(WorkMetadataValidationException.class).hasMessageContaining("verification required");
		verify(cookieService).reportRisk(anyString(), eq("risk-cookie"), eq("verification required"));
		verify(cookieService, never()).reportSuccess(anyString(), eq("risk-cookie"));

		when(cookieService.currentKuaishouCookie("single_work_parse")).thenReturn("");
		assertThatThrownBy(() -> adapter.parse(new WorkParseRequest("input",
				"https://v.kuaishou.com/fixture", true)))
				.isInstanceOf(WorkMetadataValidationException.class).hasMessageContaining("not configured");
	}

	private VideoInfo video() {
		VideoInfo value = new VideoInfo();
		value.setVideoId("ks-video-1");
		value.setTitle("Kuaishou fixture");
		value.setAuthor("Fixture Author");
		value.setAuthorId("ks-author-1");
		value.setAuthorAvatar("https://media.example/ks-avatar.jpg");
		value.setAuthorHomepage("https://www.kuaishou.com/profile/ks-author-1");
		value.setCoverUrl("https://media.example/ks-cover.jpg");
		value.setVideoUrl("https://media.example/ks-h264.mp4");
		value.setH265Url("https://media.example/ks-h265.mp4");
		value.setTimestamp(1710000000000L);
		value.setSourceUrl("https://www.kuaishou.com/short-video/ks-video-1");
		return value;
	}

	private static class FakeGateway implements KuaishouPlatformAdapter.Gateway {
		private VideoInfo video;
		private IOException parseFailure;
		private String parseCookie;
		private int downloadCalls;
		private String downloadUrl;
		private String downloadCookie;
		private Map<String, String> downloadHeaders;

		private FakeGateway(VideoInfo video) {
			this.video = video;
		}

		@Override
		public VideoInfo parse(String url, String cookie) throws IOException {
			parseCookie = cookie;
			if (parseFailure != null) throw parseFailure;
			return video;
		}

		@Override
		public Path download(String url, Path destination, String cookie, Map<String, String> headers)
				throws IOException {
			downloadCalls++;
			downloadUrl = url;
			downloadCookie = cookie;
			downloadHeaders = headers;
			Files.createDirectories(destination.getParent());
			return Files.writeString(destination, "video");
		}
	}
}
