package com.flower.spirit.platform.adapter;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
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
import com.flower.spirit.utils.BiliUtil;
import com.flower.spirit.utils.HttpUtil;
import com.flower.spirit.utils.EmbyMetadataGenerator;

@Component
public class BilibiliPlatformAdapter implements PlatformWorkAdapter {

	private static final String BILIBILI_ORIGIN = "https://www.bilibili.com";

	private final PlatformResolver resolver;
	private final Gateway gateway;

	@Autowired
	public BilibiliPlatformAdapter(PlatformResolver resolver) {
		this(resolver, systemGateway());
	}

	BilibiliPlatformAdapter(PlatformResolver resolver, Gateway gateway) {
		this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
		this.gateway = java.util.Objects.requireNonNull(gateway, "gateway");
	}

	@Override
	public String platformKey() {
		return "bilibili";
	}

	@Override
	public boolean supports(String input) {
		return resolver.resolve(input)
				.map(value -> "bilibili".equals(value.platform().getKey()))
				.orElse(false);
	}

	@Override
	public WorkMetadata parse(WorkParseRequest request) {
		ParsedView view = loadView(request);
		int requestedPage = requestedPage(request.getUrl());
		if (requestedPage > view.parts().size()) {
			throw new WorkMetadataValidationException("Bilibili part does not exist: p=" + requestedPage);
		}
		return parsePart(view, view.parts().get(requestedPage - 1), request);
	}

	/** Returns every part as an independent cid-based work for compatibility with existing Bilibili rows. */
	@Override
	public List<WorkMetadata> parseAll(WorkParseRequest request) {
		return hasExplicitPart(request.getUrl()) ? List.of(parse(request)) : parseParts(request);
	}

	public List<WorkMetadata> parseParts(WorkParseRequest request) {
		ParsedView view = loadView(request);
		List<WorkMetadata> works = new ArrayList<>();
		for (Map<String, String> part : view.parts()) {
			works.add(parsePart(view, part, request));
		}
		return List.copyOf(works);
	}

	private boolean hasExplicitPart(String url) {
		return url != null && url.matches("(?i).*[?&]p=\\d+.*");
	}

	@Override
	public DownloadResult download(WorkMetadata metadata, WorkDownloadRequest request) {
		if (metadata == null || request == null) {
			throw new IllegalArgumentException("metadata and download request are required");
		}
		if (!"bilibili".equals(metadata.getPlatformKey())) {
			throw new WorkMetadataValidationException("Bilibili adapter cannot download another platform work");
		}
		List<WorkMediaResource> videos = metadata.getMediaResources().stream()
				.filter(item -> item.getType() == WorkMediaResource.Type.VIDEO).toList();
		List<WorkMediaResource> audios = metadata.getMediaResources().stream()
				.filter(item -> item.getType() == WorkMediaResource.Type.AUDIO).toList();
		if (videos.isEmpty() || audios.size() > 1 || (!audios.isEmpty() && videos.size() != 1)) {
			throw new WorkMetadataValidationException("Bilibili work has an invalid stream set");
		}

		String name = safeName(metadata.getWorkId());
		Path output = request.getOutputDirectory().resolve(name + ".mp4");
		Path videoTemp = null;
		Path audioTemp = null;
		List<Path> segmentTemps = new ArrayList<>();
		try {
			if (audios.isEmpty()) {
				if (videos.size() == 1) {
					Path local = gateway.download(videos.get(0), output, cookie());
					return completedWithCover(metadata, request, cookie(), localVideo(videos.get(0), local));
				}
				for (int i = 0; i < videos.size(); i++) {
					Path segment = request.getOutputDirectory().resolve(name + "-segment-" + i + ".mp4");
					segmentTemps.add(gateway.download(videos.get(i), segment, cookie()));
				}
				Path local = gateway.concat(segmentTemps, output);
				return completedWithCover(metadata, request, cookie(), localVideo(videos.get(0), local));
			}
			videoTemp = request.getOutputDirectory().resolve(name + "-video.m4s");
			audioTemp = request.getOutputDirectory().resolve(name + "-audio.m4s");
			gateway.download(videos.get(0), videoTemp, cookie());
			gateway.download(audios.get(0), audioTemp, cookie());
			Path merged = gateway.merge(videoTemp, audioTemp, output);
			return completedWithCover(metadata, request, cookie(), localVideo(videos.get(0), merged));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new WorkMetadataValidationException("Bilibili download was interrupted", e);
		} catch (IOException e) {
			throw new WorkMetadataValidationException("Bilibili download failed", e);
		} finally {
			deleteQuietly(videoTemp);
			deleteQuietly(audioTemp);
			segmentTemps.forEach(this::deleteQuietly);
		}
	}

	private DownloadResult completedWithCover(WorkMetadata metadata, WorkDownloadRequest request, String cookie,
			WorkMediaResource video) {
		List<WorkMediaResource> resources = new ArrayList<>();
		resources.add(video);
		if (metadata.getCoverUrl() != null && !metadata.getCoverUrl().isBlank()) {
			try {
				Path cover = request.getOutputDirectory().resolve(safeName(metadata.getWorkId()) + ".jpg");
				Path local = HttpMediaDownloader.download(metadata.getCoverUrl(), cover, cookie,
						mediaHeaders(metadata.getSourceUrl()));
				resources.add(new WorkMediaResource(1, WorkMediaResource.Type.IMAGE, metadata.getCoverUrl(), local,
						"jpg", mediaHeaders(metadata.getSourceUrl())));
			} catch (IOException ignored) {
			}
		}
		return DownloadResult.completed(resources);
	}

	@Override
	public void postProcessDownloaded(WorkMetadata metadata, Path outputDirectory,
			List<WorkMediaResource> downloadedResources) {
		Map<String, String> part = Map.of();
		try {
			part = BiliUtil.parseVideoDataInfo(metadata.getRawMetadata()).stream()
					.filter(value -> metadata.getWorkId().equals(value.get("cid"))).findFirst().orElse(Map.of());
		} catch (RuntimeException ignored) {
		}
		String cover = downloadedResources.stream().filter(value -> value.getType() == WorkMediaResource.Type.IMAGE)
				.map(value -> value.getLocalPath().getFileName().toString()).findFirst().orElse(metadata.getCoverUrl());
		if (Global.getGeneratenfo) {
			EmbyMetadataGenerator.createBillNfo(metadata.getAuthorName(), metadata.getAuthorAvatar(),
					metadata.getAuthorId(), metadata.getPublishTime(), metadata.getWorkId(), metadata.getTitle(),
					metadata.getDescription(), cover, outputDirectory.toString());
		}
		if (Global.danmudown && Global.biliodddmm) {
			try {
				int duration = Integer.parseInt(part.getOrDefault("duration", "0"));
				BiliUtil.biliDanmaku("1", metadata.getWorkId(), part.get("aid"), duration,
						outputDirectory.resolve(safeName(metadata.getWorkId()) + ".ass").toString(), metadata.getTitle());
			} catch (RuntimeException ignored) {
			}
		}
	}

	private ParsedView loadView(WorkParseRequest request) {
		rejectUnsupported(request.getUrl());
		try {
			String entry = gateway.resolveEntry(request.getUrl());
			if (!isVideoEntry(entry)) {
				throw new WorkMetadataValidationException("Bilibili input is not an ordinary uploaded video");
			}
			String response = gateway.view(entry);
			List<Map<String, String>> parts = BiliUtil.parseVideoDataInfo(response);
			if (parts == null || parts.isEmpty()) {
				throw new WorkMetadataValidationException("Bilibili returned no video parts");
			}
			return new ParsedView(entry, response, parts);
		} catch (IOException e) {
			throw new WorkMetadataValidationException("Bilibili metadata parsing failed", e);
		}
	}

	private WorkMetadata parsePart(ParsedView view, Map<String, String> part, WorkParseRequest request) {
		try {
			String sourceUrl = canonicalPartUrl(view.entry(), part, view.parts().size());
			List<WorkMediaResource> resources = parsePlayResources(gateway.play(part, cookie()), sourceUrl);
			JSONObject owner = JSONObject.parseObject(part.get("owner"));
			String ownerId = owner == null ? null : owner.getString("mid");
			return WorkMetadata.builder()
					.platform(PlatformCatalog.requireByKey("bilibili"))
					.workId(part.get("cid"))
					.contentType(WorkContentType.VIDEO)
					.title(part.get("title"))
					.description(part.get("desc"))
					.authorId(ownerId)
					.authorUsername(ownerId)
					.authorName(owner == null ? null : owner.getString("name"))
					.authorAvatar(owner == null ? null : owner.getString("face"))
					.authorHomepage(ownerId == null ? null : "https://space.bilibili.com/" + ownerId)
					.publishTime(part.get("ctime"))
					.sourceUrl(sourceUrl)
					.originalAddress(request.getInput())
					.coverUrl(part.get("pic"))
					.mediaResources(resources)
					.rawMetadata(view.rawResponse())
					.build();
		} catch (IOException e) {
			throw new WorkMetadataValidationException("Bilibili stream parsing failed", e);
		}
	}

	private List<WorkMediaResource> parsePlayResources(String response, String referer) {
		JSONObject root = parseObject(response, "Bilibili returned invalid stream metadata");
		if (root.getIntValue("code") != 0 || root.getJSONObject("data") == null) {
			throw new WorkMetadataValidationException("Bilibili stream metadata request was rejected");
		}
		JSONObject data = root.getJSONObject("data");
		Map<String, String> headers = mediaHeaders(referer);
		JSONObject dash = data.getJSONObject("dash");
		if (dash != null) {
			String video = firstMediaUrl(dash.getJSONArray("video"), true);
			String audio = firstMediaUrl(dash.getJSONArray("audio"), true);
			if (video == null || audio == null) {
				throw new WorkMetadataValidationException("Bilibili DASH response is missing video or audio");
			}
			return List.of(
					new WorkMediaResource(0, WorkMediaResource.Type.VIDEO, video, null, "m4s", headers),
					new WorkMediaResource(1, WorkMediaResource.Type.AUDIO, audio, null, "m4s", headers));
		}
		JSONArray durl = data.getJSONArray("durl");
		List<WorkMediaResource> segments = new ArrayList<>();
		if (durl != null) {
			for (int i = 0; i < durl.size(); i++) {
				List<String> choices = BiliUtil.choiceMediaAddr(durl, i, false, Global.cdnsort);
				String video = choices == null || choices.isEmpty() ? null : choices.get(0);
				if (video != null) segments.add(new WorkMediaResource(i, WorkMediaResource.Type.VIDEO,
						video, null, "mp4", headers));
			}
		}
		if (segments.isEmpty()) {
			throw new WorkMetadataValidationException("Bilibili response has no downloadable video stream");
		}
		return List.copyOf(segments);
	}

	private String firstMediaUrl(JSONArray values, boolean dash) {
		if (values == null || values.isEmpty()) return null;
		List<String> choices = BiliUtil.choiceMediaAddr(values, 0, dash, Global.cdnsort);
		return choices == null || choices.isEmpty() ? null : choices.get(0);
	}

	private Map<String, String> mediaHeaders(String referer) {
		String userAgent = Global.useragent == null || Global.useragent.isBlank()
				? "Mozilla/5.0" : Global.useragent;
		return Map.of("Referer", referer, "Origin", BILIBILI_ORIGIN, "User-Agent", userAgent);
	}

	private int requestedPage(String url) {
		try {
			String query = URI.create(url).getRawQuery();
			if (query == null) return 1;
			for (String parameter : query.split("&")) {
				String[] pair = parameter.split("=", 2);
				if (pair.length == 2 && "p".equalsIgnoreCase(pair[0])) {
					int page = Integer.parseInt(pair[1]);
					if (page < 1) throw new NumberFormatException();
					return page;
				}
			}
			return 1;
		} catch (IllegalArgumentException e) {
			throw new WorkMetadataValidationException("Bilibili part parameter is invalid", e);
		}
	}

	private String canonicalPartUrl(String entry, Map<String, String> part, int partCount) {
		String bvid = part.get("bvid");
		String canonicalEntry = bvid == null || bvid.isBlank() ? entry : bvid;
		String base = BILIBILI_ORIGIN + "/video/" + canonicalEntry + "/";
		String page = part.get("page");
		return partCount > 1 && page != null ? base + "?p=" + page : base;
	}

	private void rejectUnsupported(String url) {
		String normalized = url == null ? "" : url.toLowerCase(Locale.ROOT);
		if (normalized.contains("live.bilibili.com") || normalized.contains("/bangumi/")
				|| normalized.contains("/cheese/") || normalized.contains("/movie/")) {
			throw new WorkMetadataValidationException(
					"Bilibili bangumi, film/TV and live inputs are not supported");
		}
	}

	private boolean isVideoEntry(String entry) {
		return entry != null && (entry.regionMatches(true, 0, "BV", 0, 2)
				|| entry.regionMatches(true, 0, "av", 0, 2));
	}

	private WorkMediaResource localVideo(WorkMediaResource source, Path local) {
		return new WorkMediaResource(0, WorkMediaResource.Type.VIDEO, source.getSourceUrl(), local,
				"mp4", source.getRequestHeaders());
	}

	private String cookie() {
		return Global.bilicookies;
	}

	private String safeName(String value) {
		String safe = value == null ? "bilibili-work" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
		return safe.isEmpty() ? "bilibili-work" : safe;
	}

	private void deleteQuietly(Path path) {
		if (path == null) return;
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
		}
	}

	private JSONObject parseObject(String value, String error) {
		try {
			JSONObject object = JSONObject.parseObject(value);
			if (object == null) throw new IllegalArgumentException();
			return object;
		} catch (RuntimeException e) {
			throw new WorkMetadataValidationException(error, e);
		}
	}

	static Gateway systemGateway() {
		return new Gateway() {
			@Override
			public String resolveEntry(String url) {
				return BiliUtil.parseEntry(url);
			}

			@Override
			public String view(String entry) throws IOException {
				String api;
				if (entry.regionMatches(true, 0, "BV", 0, 2)) {
					api = "https://api.bilibili.com/x/web-interface/view?bvid=" + entry;
				} else if (entry.regionMatches(true, 0, "av", 0, 2)) {
					api = "https://api.bilibili.com/x/web-interface/view?aid=" + entry.substring(2);
				} else {
					throw new IOException("unsupported Bilibili entry");
				}
				String response = HttpUtil.getSerchPersion(api, "UTF-8");
				if (response == null || response.isBlank()) throw new IOException("empty Bilibili view response");
				return response;
			}

			@Override
			public String play(Map<String, String> part, String cookie) throws IOException {
				String api = BiliUtil.buildInterfaceWbiAddress(part.get("aid"), part.get("cid"), cookie,
						part.get("quality"));
				String response = HttpUtil.httpGetBili(api, "UTF-8", cookie);
				if (response == null || response.isBlank()) throw new IOException("empty Bilibili play response");
				return response;
			}

			@Override
			public Path download(WorkMediaResource source, Path destination, String cookie) throws IOException {
				return HttpMediaDownloader.download(source.getSourceUrl(), destination, cookie,
						source.getRequestHeaders());
			}

			@Override
			public Path merge(Path video, Path audio, Path destination) throws IOException, InterruptedException {
				Files.createDirectories(destination.toAbsolutePath().normalize().getParent());
				Process process = new ProcessBuilder("ffmpeg", "-y", "-i", video.toString(), "-i", audio.toString(),
						"-c:v", "copy", "-c:a", "copy", "-f", "mp4", destination.toString())
						.redirectOutput(ProcessBuilder.Redirect.DISCARD)
						.redirectError(ProcessBuilder.Redirect.DISCARD)
						.start();
				int exitCode = process.waitFor();
				if (exitCode != 0 || !Files.isRegularFile(destination) || Files.size(destination) <= 0) {
					throw new IOException("ffmpeg failed to merge Bilibili DASH streams");
				}
				return destination;
			}

			@Override
			public Path concat(List<Path> segments, Path destination) throws IOException, InterruptedException {
				Files.createDirectories(destination.toAbsolutePath().normalize().getParent());
				Path manifest = destination.resolveSibling(destination.getFileName() + ".concat.txt");
				try {
					List<String> lines = segments.stream()
							.map(path -> "file '" + path.toAbsolutePath().normalize().toString()
									.replace('\\', '/').replace("'", "'\\''") + "'")
							.toList();
					Files.write(manifest, lines);
					Process process = new ProcessBuilder("ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i",
							manifest.toString(), "-c", "copy", destination.toString())
							.redirectOutput(ProcessBuilder.Redirect.DISCARD)
							.redirectError(ProcessBuilder.Redirect.DISCARD).start();
					int exitCode = process.waitFor();
					if (exitCode != 0 || !Files.isRegularFile(destination) || Files.size(destination) <= 0) {
						throw new IOException("ffmpeg failed to concatenate Bilibili DURL segments");
					}
					return destination;
				} finally {
					Files.deleteIfExists(manifest);
				}
			}
		};
	}

	interface Gateway {
		String resolveEntry(String url) throws IOException;
		String view(String entry) throws IOException;
		String play(Map<String, String> part, String cookie) throws IOException;
		Path download(WorkMediaResource source, Path destination, String cookie) throws IOException;
		Path merge(Path video, Path audio, Path destination) throws IOException, InterruptedException;
		default Path concat(List<Path> segments, Path destination) throws IOException, InterruptedException {
			if (segments.size() != 1) throw new IOException("Bilibili segment concatenation is not available");
			return Files.move(segments.get(0), destination, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private record ParsedView(String entry, String rawResponse, List<Map<String, String>> parts) {
	}
}
