package com.flower.spirit.platform.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.flower.spirit.config.Global;
import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.platform.WorkParseRequest;
import com.flower.spirit.service.PlatformCookieService;
import com.flower.spirit.utils.KuaishouParser;
import com.flower.spirit.utils.EmbyMetadataGenerator;
import com.flower.spirit.utils.KuaishouParser.VideoInfo;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Component
public class KuaishouPlatformAdapter implements PlatformWorkAdapter {

	private static final String PLATFORM_KEY = "kuaishou";
	private static final String REFERER = "https://www.kuaishou.com/";

	private final PlatformResolver resolver;
	private final PlatformCookieService cookieService;
	private final Gateway gateway;

	@Autowired
	public KuaishouPlatformAdapter(PlatformResolver resolver, PlatformCookieService cookieService) {
		this(resolver, cookieService, systemGateway());
	}

	KuaishouPlatformAdapter(PlatformResolver resolver, PlatformCookieService cookieService, Gateway gateway) {
		this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
		this.cookieService = java.util.Objects.requireNonNull(cookieService, "cookieService");
		this.gateway = java.util.Objects.requireNonNull(gateway, "gateway");
	}

	@Override
	public String platformKey() {
		return PLATFORM_KEY;
	}

	@Override
	public boolean supports(String input) {
		return resolver.resolve(input).map(result -> PLATFORM_KEY.equals(result.platform().getKey())).orElse(false);
	}

	@Override
	public WorkMetadata parse(WorkParseRequest request) {
		String cookie = requireCookie("single_work_parse");
		try {
			VideoInfo video = gateway.parse(request.getUrl(), cookie);
			cookieService.reportSuccess(platformDisplayName(), cookie);
			String videoUrl = firstText(video.getH265Url(), video.getVideoUrl());
			if (videoUrl == null) throw new WorkMetadataValidationException("Kuaishou work has no video URL");
			Map<String, String> headers = Map.of("User-Agent", KuaishouParser.USER_AGENT, "Referer", REFERER);
			return WorkMetadata.builder()
					.platform(PlatformCatalog.requireByKey(PLATFORM_KEY))
					.workId(video.getVideoId())
					.contentType(WorkContentType.VIDEO)
					.title(video.getTitle())
					.description(video.getTitle())
					.authorId(video.getAuthorId())
					.authorUsername(video.getAuthorId())
					.authorName(video.getAuthor())
					.authorAvatar(video.getAuthorAvatar())
					.authorHomepage(video.getAuthorHomepage())
					.publishTime(video.getTimestamp() == null ? null : String.valueOf(video.getTimestamp()))
					.sourceUrl(firstText(video.getSourceUrl(), request.getUrl()))
					.originalAddress(request.getInput())
					.coverUrl(video.getCoverUrl())
					.mediaResources(List.of(new WorkMediaResource(0, WorkMediaResource.Type.VIDEO,
							videoUrl, null, "mp4", headers)))
					.rawMetadata(video.toString())
					.build();
		} catch (IOException e) {
			String reason = safeFailureReason(e);
			reportRisk(cookie, e, reason);
			throw new WorkMetadataValidationException("Kuaishou parsing failed: " + reason, e);
		}
	}

	@Override
	public DownloadResult download(WorkMetadata metadata, WorkDownloadRequest request) {
		if (metadata == null || request == null) {
			throw new IllegalArgumentException("metadata and download request are required");
		}
		WorkMediaResource source = metadata.getMediaResources().stream()
				.filter(resource -> resource.getType() == WorkMediaResource.Type.VIDEO)
				.findFirst().orElseThrow(() -> new WorkMetadataValidationException("Kuaishou video resource is missing"));
		String cookie = requireCookie("single_work_download");
		try {
			String fileName = safeFileName(metadata.getWorkId()) + ".mp4";
			Path local = gateway.download(source.getSourceUrl(), request.getOutputDirectory().resolve(fileName),
					cookie, source.getRequestHeaders());
			cookieService.reportSuccess(platformDisplayName(), cookie);
			List<WorkMediaResource> downloaded = new ArrayList<>();
			downloaded.add(new WorkMediaResource(0, WorkMediaResource.Type.VIDEO,
					source.getSourceUrl(), local, "mp4", source.getRequestHeaders()));
			if (metadata.getCoverUrl() != null && !metadata.getCoverUrl().isBlank()) {
				try {
					Path cover = gateway.download(metadata.getCoverUrl(), request.getOutputDirectory()
							.resolve(safeFileName(metadata.getWorkId()) + ".jpg"), cookie, source.getRequestHeaders());
					downloaded.add(new WorkMediaResource(1, WorkMediaResource.Type.IMAGE, metadata.getCoverUrl(),
							cover, "jpg", source.getRequestHeaders()));
				} catch (IOException ignored) {
				}
			}
			return DownloadResult.completed(downloaded);
		} catch (IOException e) {
			String reason = safeFailureReason(e);
			reportRisk(cookie, e, reason);
			throw new WorkMetadataValidationException("Kuaishou download failed: " + reason, e);
		}
	}

	@Override
	public void postProcessDownloaded(WorkMetadata metadata, Path outputDirectory,
			List<WorkMediaResource> downloadedResources) {
		if (!Global.getGeneratenfo) return;
		String cover = downloadedResources.stream().filter(value -> value.getType() == WorkMediaResource.Type.IMAGE)
				.map(value -> value.getLocalPath().getFileName().toString()).findFirst().orElse(metadata.getCoverUrl());
		EmbyMetadataGenerator.createKuaiNfo(metadata.getAuthorName(), metadata.getAuthorId(),
				metadata.getPublishTime(), metadata.getWorkId(), metadata.getTitle(), metadata.getDescription(), cover,
				outputDirectory.toString());
	}

	private String requireCookie(String purpose) {
		String cookie = cookieService.currentKuaishouCookie(purpose);
		if (cookie == null || cookie.trim().isEmpty()) {
			throw new WorkMetadataValidationException("Kuaishou cookie is not configured");
		}
		return cookie;
	}

	private void reportRisk(String cookie, IOException error, String reason) {
		if (cookieService.isRiskSignal(error.getMessage())) {
			cookieService.reportRisk(platformDisplayName(), cookie, reason);
		}
	}

	private String safeFailureReason(IOException error) {
		return cookieService.isRiskSignal(error.getMessage()) ? "verification required" : "request failed";
	}

	private String platformDisplayName() {
		return PlatformCatalog.requireByKey(PLATFORM_KEY).getDisplayName();
	}

	private String safeFileName(String value) {
		if (value == null || value.trim().isEmpty()) return "kuaishou-video";
		String safe = value.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
		return safe.isEmpty() ? "kuaishou-video" : safe;
	}

	private static String firstText(String... values) {
		if (values == null) return null;
		for (String value : values) {
			if (value != null && !value.trim().isEmpty()) return value.trim();
		}
		return null;
	}

	static Gateway systemGateway() {
		OkHttpClient client = new OkHttpClient();
		return new Gateway() {
			@Override
			public VideoInfo parse(String url, String cookie) throws IOException {
				return KuaishouParser.parseVideo(url, cookie);
			}

			@Override
			public Path download(String url, Path destination, String cookie, Map<String, String> headers)
					throws IOException {
				Files.createDirectories(destination.toAbsolutePath().normalize().getParent());
				Request.Builder request = new Request.Builder().url(url);
				headers.forEach(request::header);
				request.header("Cookie", cookie);
				try (Response response = client.newCall(request.build()).execute()) {
					if (!response.isSuccessful() || response.body() == null) {
						throw new IOException("Kuaishou media request failed with HTTP " + response.code());
					}
					try (InputStream input = response.body().byteStream()) {
						Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
					}
				}
				return destination;
			}
		};
	}

	interface Gateway {
		VideoInfo parse(String url, String cookie) throws IOException;

		Path download(String url, Path destination, String cookie, Map<String, String> headers) throws IOException;
	}
}
