package com.flower.spirit.platform.adapter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.flower.spirit.config.Global;
import com.flower.spirit.executor.WeiBoExecutor;
import com.flower.spirit.executor.WeiBoExecutor.ParsedMedia;
import com.flower.spirit.executor.WeiBoExecutor.ParsedPost;
import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.platform.WorkParseRequest;
import com.flower.spirit.utils.DouUtil;

@Component
public class WeiboPlatformAdapter implements PlatformWorkAdapter {

	private static final String PLATFORM_KEY = "weibo";
	private static final String REFERER = "https://weibo.com/";
	private static final DateTimeFormatter WEIBO_TIME =
			DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);

	private final PlatformResolver resolver;
	private final Gateway gateway;

	@Autowired
	public WeiboPlatformAdapter(PlatformResolver resolver) {
		this(resolver, systemGateway());
	}

	WeiboPlatformAdapter(PlatformResolver resolver, Gateway gateway) {
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
		String workId = WeiBoExecutor.extractWeiboId(request.getUrl());
		if (workId == null) throw new WorkMetadataValidationException("Weibo work ID cannot be resolved");
		String cookie = cookie();
		if (cookie == null || cookie.trim().isEmpty()) {
			throw new WorkMetadataValidationException("Weibo cookie is not configured");
		}
		try {
			ParsedPost post = gateway.parse(workId, request.getUrl(), cookie);
			if (post.media().isEmpty()) throw new WorkMetadataValidationException("Weibo work has no visual media");
			Map<String, String> headers = Map.of("User-Agent", DouUtil.ua, "Referer", REFERER);
			List<WorkMediaResource> resources = new ArrayList<>();
			for (int i = 0; i < post.media().size(); i++) {
				ParsedMedia media = post.media().get(i);
				resources.add(new WorkMediaResource(i, media.video() ? WorkMediaResource.Type.VIDEO
						: WorkMediaResource.Type.IMAGE, media.url(), null, media.extension(), headers));
			}
			return WorkMetadata.builder()
					.platform(PlatformCatalog.requireByKey(PLATFORM_KEY))
					.workId(post.workId())
					.contentType(contentType(post))
					.title(post.description())
					.description(post.description())
					.authorId(post.authorId())
					.authorUsername(post.authorId())
					.authorName(post.authorName())
					.authorAvatar(post.authorAvatar())
					.authorHomepage(post.authorHomepage())
					.publishTime(publishTime(post.publishTime()))
					.sourceUrl(post.sourceUrl())
					.originalAddress(request.getInput())
					.coverUrl(firstImage(post))
					.mediaResources(resources)
					.rawMetadata(post.rawMetadata())
					.build();
		} catch (IOException e) {
			throw new WorkMetadataValidationException("Weibo parsing failed", e);
		}
	}

	@Override
	public DownloadResult download(WorkMetadata metadata, WorkDownloadRequest request) {
		try {
			String cookie = cookie();
			List<WorkMediaResource> downloaded = new ArrayList<>();
			for (int i = 0; i < metadata.getMediaResources().size(); i++) {
				WorkMediaResource source = metadata.getMediaResources().get(i);
				String extension = source.getExpectedExtension() == null
						? source.getType() == WorkMediaResource.Type.VIDEO ? "mp4" : "jpeg"
						: source.getExpectedExtension();
				Path target = request.getOutputDirectory().resolve(safeName(metadata.getWorkId())
						+ "-index-" + i + "." + extension);
				Path local = gateway.download(source.getSourceUrl(), target, cookie, source.getRequestHeaders());
				downloaded.add(new WorkMediaResource(i, source.getType(), source.getSourceUrl(), local,
						extension, source.getRequestHeaders()));
			}
			return DownloadResult.completed(downloaded);
		} catch (IOException e) {
			throw new WorkMetadataValidationException("Weibo download failed", e);
		}
	}

	private WorkContentType contentType(ParsedPost post) {
		long videos = post.media().stream().filter(ParsedMedia::video).count();
		return videos == 1 && post.media().size() == 1 ? WorkContentType.VIDEO
				: videos > 0 ? WorkContentType.MIXED : WorkContentType.GRAPHIC;
	}

	private String firstImage(ParsedPost post) {
		return post.media().stream().filter(media -> !media.video()).map(ParsedMedia::url).findFirst().orElse(null);
	}

	private String publishTime(String raw) {
		if (raw == null || raw.trim().isEmpty()) return null;
		try { return ZonedDateTime.parse(raw, WEIBO_TIME).toInstant().toString(); }
		catch (RuntimeException ignored) { return null; }
	}

	private String cookie() {
		return Global.cookie_manage == null ? null : Global.cookie_manage.getWeibocookie();
	}

	private String safeName(String value) {
		String safe = value == null ? "weibo-work" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
		return safe.isEmpty() ? "weibo-work" : safe;
	}

	static Gateway systemGateway() {
		return new Gateway() {
			@Override public ParsedPost parse(String workId, String sourceUrl, String cookie) throws IOException {
				String json = WeiBoExecutor.fetchWeiboDetailStrict(workId, cookie);
				return WeiBoExecutor.parseDetailJson(json, sourceUrl, workId);
			}
			@Override public Path download(String url, Path destination, String cookie,
					Map<String, String> headers) throws IOException {
				return HttpMediaDownloader.download(url, destination, cookie, headers);
			}
		};
	}

	interface Gateway {
		ParsedPost parse(String workId, String sourceUrl, String cookie) throws IOException;
		Path download(String url, Path destination, String cookie, Map<String, String> headers) throws IOException;
	}
}
