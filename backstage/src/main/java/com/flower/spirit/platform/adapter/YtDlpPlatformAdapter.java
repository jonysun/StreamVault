package com.flower.spirit.platform.adapter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.platform.PlatformSupportTier;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.platform.WorkParseRequest;
import com.flower.spirit.utils.YtDlpUtil;

public class YtDlpPlatformAdapter implements PlatformWorkAdapter {

	private final String registeredPlatformKey;
	private final YtDlpMetadataParser parser;
	private final PlatformResolver resolver;
	private final Gateway gateway;

	public YtDlpPlatformAdapter(String registeredPlatformKey, YtDlpMetadataParser parser,
			PlatformResolver resolver, Gateway gateway) {
		if (registeredPlatformKey == null || registeredPlatformKey.trim().isEmpty()) {
			throw new IllegalArgumentException("registered platform key is required");
		}
		this.registeredPlatformKey = registeredPlatformKey.trim().toLowerCase(Locale.ROOT);
		this.parser = java.util.Objects.requireNonNull(parser, "parser");
		this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
		this.gateway = java.util.Objects.requireNonNull(gateway, "gateway");
	}

	@Override
	public String platformKey() {
		return registeredPlatformKey;
	}

	@Override
	public boolean supports(String input) {
		return resolver.resolve(input)
				.map(resolution -> registeredPlatformKey.equals(resolution.platform().getKey()))
				.orElse(false);
	}

	@Override
	public WorkMetadata parse(WorkParseRequest request) {
		try {
			String output = gateway.metadata(request.getUrl(), cookiePlatform());
			WorkMetadata metadata = parser.parseSingle(output, request.getInput(), request.getUrl());
			validateOwnership(metadata);
			return metadata;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new WorkMetadataValidationException("yt-dlp metadata parsing was interrupted", e);
		} catch (IOException e) {
			throw new WorkMetadataValidationException("yt-dlp metadata parsing failed: " + e.getMessage(), e);
		}
	}

	@Override
	public DownloadResult download(WorkMetadata metadata, WorkDownloadRequest request) {
		if (metadata == null || request == null) {
			throw new IllegalArgumentException("metadata and download request are required");
		}
		validateOwnership(metadata);
		try {
			List<Path> files = gateway.download(metadata.getSourceUrl(), request.getOutputDirectory(),
					downloadPlatform(metadata));
			if (files == null || files.isEmpty()) {
				throw new WorkMetadataValidationException("yt-dlp returned no downloaded video files");
			}
			List<WorkMediaResource> resources = new ArrayList<>();
			for (int i = 0; i < files.size(); i++) {
				Path file = files.get(i);
				resources.add(new WorkMediaResource(i, WorkMediaResource.Type.VIDEO, null, file,
						extension(file), java.util.Map.of()));
			}
			return DownloadResult.completed(resources);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new WorkMetadataValidationException("yt-dlp video download was interrupted", e);
		} catch (IOException e) {
			throw new WorkMetadataValidationException("yt-dlp video download failed: " + e.getMessage(), e);
		}
	}

	private void validateOwnership(WorkMetadata metadata) {
		if ("generic".equals(registeredPlatformKey)) {
			if (metadata.getSupportTier() != PlatformSupportTier.GENERIC) {
				throw new WorkMetadataValidationException("generic yt-dlp adapter cannot own a formal platform work");
			}
			return;
		}
		if (!registeredPlatformKey.equals(metadata.getPlatformKey())) {
			throw new WorkMetadataValidationException("yt-dlp extractor does not match adapter platform: "
					+ registeredPlatformKey);
		}
	}

	private String cookiePlatform() {
		return "generic".equals(registeredPlatformKey) ? null : registeredPlatformKey;
	}

	private String downloadPlatform(WorkMetadata metadata) {
		return "generic".equals(registeredPlatformKey) ? metadata.getPlatformKey() : registeredPlatformKey;
	}

	private String extension(Path path) {
		String name = path.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot < 0 || dot == name.length() - 1 ? null : name.substring(dot + 1);
	}

	public static Gateway systemGateway() {
		return new Gateway() {
			@Override
			public String metadata(String url, String platform) throws IOException, InterruptedException {
				return YtDlpUtil.execSingleMetadata(url, platform);
			}

			@Override
			public List<Path> download(String url, Path outputDirectory, String platform)
					throws IOException, InterruptedException {
				return YtDlpUtil.downloadSingleVideo(url, outputDirectory, platform);
			}
		};
	}

	public interface Gateway {
		String metadata(String url, String platform) throws IOException, InterruptedException;

		List<Path> download(String url, Path outputDirectory, String platform)
				throws IOException, InterruptedException;
	}
}
