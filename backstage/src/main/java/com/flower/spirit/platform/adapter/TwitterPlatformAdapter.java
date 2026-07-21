package com.flower.spirit.platform.adapter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.platform.WorkParseRequest;
import com.flower.spirit.utils.YtDlpUtil;

@Component
public class TwitterPlatformAdapter implements PlatformWorkAdapter {

	private final PlatformResolver resolver;
	private final YtDlpMetadataParser parser;
	private final Gateway gateway;

	@Autowired
	public TwitterPlatformAdapter(PlatformResolver resolver, YtDlpMetadataParser parser) {
		this(resolver, parser, systemGateway());
	}

	TwitterPlatformAdapter(PlatformResolver resolver, YtDlpMetadataParser parser, Gateway gateway) {
		this.resolver = resolver;
		this.parser = parser;
		this.gateway = gateway;
	}

	@Override public String platformKey() { return "twitter"; }

	@Override
	public boolean supports(String input) {
		return resolver.resolve(input).map(value -> "twitter".equals(value.platform().getKey())).orElse(false);
	}

	@Override
	public WorkMetadata parse(WorkParseRequest request) {
		try {
			WorkMetadata metadata = parser.parseSingle(gateway.metadata(request.getUrl()), request.getInput(),
					request.getUrl(), true);
			if (!"twitter".equals(metadata.getPlatformKey())) {
				throw new WorkMetadataValidationException("Twitter extractor identity does not match");
			}
			return metadata;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			throw new WorkMetadataValidationException("Twitter parsing failed", e);
		}
	}

	@Override
	public DownloadResult download(WorkMetadata metadata, WorkDownloadRequest request) {
		try {
			List<WorkMediaResource> downloaded = new ArrayList<>();
			for (int i = 0; i < metadata.getMediaResources().size(); i++) {
				WorkMediaResource source = metadata.getMediaResources().get(i);
				String extension = source.getExpectedExtension() == null ? "mp4" : source.getExpectedExtension();
				Path target = request.getOutputDirectory().resolve(safeName(metadata.getWorkId())
						+ "-index-" + i + "." + extension);
				Path local = gateway.download(source.getSourceUrl(), target, source);
				downloaded.add(new WorkMediaResource(i, WorkMediaResource.Type.VIDEO, source.getSourceUrl(),
						local, extension, source.getRequestHeaders()));
			}
			return DownloadResult.completed(downloaded);
		} catch (IOException e) {
			throw new WorkMetadataValidationException("Twitter download failed", e);
		}
	}

	private String safeName(String value) {
		String safe = value == null ? "twitter-work" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
		return safe.isEmpty() ? "twitter-work" : safe;
	}

	static Gateway systemGateway() {
		return new Gateway() {
			@Override public String metadata(String url) throws IOException, InterruptedException {
				return YtDlpUtil.execSingleMetadata(url, "twitter");
			}
			@Override public Path download(String url, Path destination, WorkMediaResource source) throws IOException {
				return HttpMediaDownloader.download(url, destination, null, source.getRequestHeaders());
			}
		};
	}

	interface Gateway {
		String metadata(String url) throws IOException, InterruptedException;
		Path download(String url, Path destination, WorkMediaResource source) throws IOException;
	}
}
