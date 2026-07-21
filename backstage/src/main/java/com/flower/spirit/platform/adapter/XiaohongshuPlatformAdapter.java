package com.flower.spirit.platform.adapter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.flower.spirit.config.Global;
import com.flower.spirit.executor.HongShuExecutor;
import com.flower.spirit.executor.HongShuExecutor.ParsedMedia;
import com.flower.spirit.executor.HongShuExecutor.ParsedNote;
import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.platform.WorkParseRequest;
import com.flower.spirit.utils.HttpUtil;

@Component
public class XiaohongshuPlatformAdapter implements PlatformWorkAdapter {

	private static final String PLATFORM_KEY = "xiaohongshu";
	private static final String REFERER = "https://www.xiaohongshu.com/";

	private final PlatformResolver resolver;
	private final Gateway gateway;

	@Autowired
	public XiaohongshuPlatformAdapter(PlatformResolver resolver) {
		this(resolver, systemGateway());
	}

	XiaohongshuPlatformAdapter(PlatformResolver resolver, Gateway gateway) {
		this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
		this.gateway = java.util.Objects.requireNonNull(gateway, "gateway");
	}

	@Override public String platformKey() { return PLATFORM_KEY; }

	@Override
	public boolean supports(String input) {
		return resolver.resolve(input).map(value -> PLATFORM_KEY.equals(value.platform().getKey())).orElse(false);
	}

	@Override
	public WorkMetadata parse(WorkParseRequest request) {
		try {
			ParsedNote note = gateway.parse(request.getUrl(), cookie());
			if (note.media().isEmpty()) throw new WorkMetadataValidationException("Xiaohongshu work has no visual media");
			Map<String, String> headers = Map.of("User-Agent", userAgent(), "Referer", REFERER);
			List<WorkMediaResource> resources = new ArrayList<>();
			for (int i = 0; i < note.media().size(); i++) {
				ParsedMedia media = note.media().get(i);
				resources.add(new WorkMediaResource(i, media.video() ? WorkMediaResource.Type.VIDEO
						: WorkMediaResource.Type.IMAGE, media.url(), null, media.extension(), headers));
			}
			return WorkMetadata.builder()
					.platform(PlatformCatalog.requireByKey(PLATFORM_KEY))
					.workId(note.workId())
					.contentType(contentType(note))
					.title(firstText(note.title(), note.description()))
					.description(note.description())
					.authorId(note.authorId())
					.authorUsername(note.authorId())
					.authorName(note.authorName())
					.authorAvatar(note.authorAvatar())
					.authorHomepage(note.authorHomepage())
					.publishTime(note.publishTime())
					.sourceUrl(note.sourceUrl())
					.originalAddress(request.getInput())
					.coverUrl(note.coverUrl())
					.mediaResources(resources)
					.rawMetadata(note.rawMetadata())
					.build();
		} catch (IOException | RuntimeException e) {
			if (e instanceof WorkMetadataValidationException validation) throw validation;
			throw new WorkMetadataValidationException("Xiaohongshu parsing failed", e);
		}
	}

	@Override
	public DownloadResult download(WorkMetadata metadata, WorkDownloadRequest request) {
		try {
			List<WorkMediaResource> downloaded = new ArrayList<>();
			String cookie = cookie();
			for (int i = 0; i < metadata.getMediaResources().size(); i++) {
				WorkMediaResource source = metadata.getMediaResources().get(i);
				String extension = firstText(source.getExpectedExtension(),
						source.getType() == WorkMediaResource.Type.VIDEO ? "mp4" : "jpeg");
				Path target = request.getOutputDirectory().resolve(safeName(metadata.getWorkId())
						+ "-index-" + i + "." + extension);
				Path local = gateway.download(source.getSourceUrl(), target, cookie, source.getRequestHeaders());
				downloaded.add(new WorkMediaResource(i, source.getType(), source.getSourceUrl(), local,
						extension, source.getRequestHeaders()));
			}
			return DownloadResult.completed(downloaded);
		} catch (IOException e) {
			throw new WorkMetadataValidationException("Xiaohongshu download failed", e);
		}
	}

	private WorkContentType contentType(ParsedNote note) {
		if (note.singleVideo()) return WorkContentType.VIDEO;
		boolean hasVideo = note.media().stream().anyMatch(ParsedMedia::video);
		return hasVideo ? WorkContentType.MIXED : WorkContentType.GRAPHIC;
	}

	private String cookie() {
		return Global.cookie_manage == null ? null : Global.cookie_manage.getRednotecookie();
	}

	private String userAgent() {
		return Global.useragent == null || Global.useragent.trim().isEmpty()
				? "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" : Global.useragent;
	}

	private String safeName(String value) {
		String safe = value == null ? "xiaohongshu-work" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
		return safe.isEmpty() ? "xiaohongshu-work" : safe;
	}

	private static String firstText(String first, String second) {
		return first != null && !first.trim().isEmpty() ? first.trim()
				: second == null || second.trim().isEmpty() ? null : second.trim();
	}

	static Gateway systemGateway() {
		return new Gateway() {
			@Override public ParsedNote parse(String url, String cookie) throws IOException {
				String html = HttpUtil.getPage(url, cookie, REFERER);
				if (html == null) throw new IOException("Xiaohongshu page request failed");
				return HongShuExecutor.parsePage(html, url);
			}
			@Override public Path download(String url, Path destination, String cookie,
					Map<String, String> headers) throws IOException {
				return HttpMediaDownloader.download(url, destination, cookie, headers);
			}
		};
	}

	interface Gateway {
		ParsedNote parse(String url, String cookie) throws IOException;
		Path download(String url, Path destination, String cookie, Map<String, String> headers) throws IOException;
	}
}
