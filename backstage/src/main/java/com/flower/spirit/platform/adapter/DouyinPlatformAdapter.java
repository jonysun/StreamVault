package com.flower.spirit.platform.adapter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.config.Global;
import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.DouyinGlobalCooldownException;
import com.flower.spirit.platform.DouyinWorkFetchException;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.platform.WorkParseRequest;
import com.flower.spirit.service.PlatformCookieService;
import com.flower.spirit.service.DouyinF2RequestCoordinator;
import com.flower.spirit.utils.AuthorIdentityUtil;
import com.flower.spirit.utils.DouUtil;
import com.flower.spirit.utils.EmbyMetadataGenerator;
import com.flower.spirit.utils.DouyinSourceUrlUtil;

@Component
public class DouyinPlatformAdapter implements PlatformWorkAdapter {
	private static final Logger logger = LoggerFactory.getLogger(DouyinPlatformAdapter.class);

	private final PlatformResolver resolver;
	private final PlatformCookieService cookieService;
	private final Gateway gateway;
	private final DouyinF2RequestCoordinator requestCoordinator;
	private final ThreadLocal<String> operationCookie = new ThreadLocal<>();

	@Autowired
	public DouyinPlatformAdapter(PlatformResolver resolver, PlatformCookieService cookieService,
			DouyinF2RequestCoordinator requestCoordinator) {
		this(resolver, cookieService, systemGateway(), requestCoordinator);
	}

	DouyinPlatformAdapter(PlatformResolver resolver, PlatformCookieService cookieService, Gateway gateway) {
		this(resolver, cookieService, gateway, new DouyinF2RequestCoordinator());
	}

	DouyinPlatformAdapter(PlatformResolver resolver, PlatformCookieService cookieService, Gateway gateway,
			DouyinF2RequestCoordinator requestCoordinator) {
		this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
		this.cookieService = java.util.Objects.requireNonNull(cookieService, "cookieService");
		this.gateway = java.util.Objects.requireNonNull(gateway, "gateway");
		this.requestCoordinator = java.util.Objects.requireNonNull(requestCoordinator, "requestCoordinator");
	}

	@Override
	public String platformKey() {
		return "douyin";
	}

	@Override
	public boolean supports(String input) {
		return resolver.resolve(input)
				.map(value -> "douyin".equals(value.platform().getKey()))
				.orElse(false);
	}

	@Override
	public OperationScope openOperationScope(String purpose) {
		String previous = operationCookie.get();
		if (previous != null && !previous.isBlank()) return OperationScope.NOOP;
		String cookie = requireCookie(purpose == null || purpose.isBlank() ? "work_operation" : purpose);
		operationCookie.set(cookie);
		return operationCookie::remove;
	}

	@Override
	public WorkMetadata parse(WorkParseRequest request) {
		String requestWorkId = DouUtil.extractWorkId(request.getUrl());
		if (request.getRawMetadata() != null && !request.getRawMetadata().isBlank()
				&& requestWorkId != null && !requestWorkId.isBlank()) {
			try {
				return parseSnapshot(request.getRawMetadata(), requestWorkId, request.getInput(), request.getUrl());
			} catch (RuntimeException error) {
				logger.warn("[DouyinSnapshot] rejected list snapshot workId={} reason={}; falling back to detail request",
						requestWorkId, error.getMessage());
			}
		}
		String cookie = requireCookie("single_work_parse");
		try (DouyinF2RequestCoordinator.Permit ignored = requestCoordinator.acquire()) {
			if (cookieService.isDouyinGlobalRiskCooldownActive()) {
				throw new DouyinGlobalCooldownException("Douyin global cooldown is active",
						cookieService.douyinGlobalRiskCooldownRetryAt(Duration.ofSeconds(5)));
			}
			if (cookieService.isDouyinDetailSoftBackoffActive()) {
				throw new DouyinGlobalCooldownException("Douyin detail endpoint soft backoff is active",
						cookieService.douyinDetailSoftBackoffRetryAt(Duration.ofSeconds(5)));
			}
			String resolvedUrl = gateway.resolve(request.getUrl());
			String inputWorkId = DouUtil.extractWorkId(resolvedUrl);
			if (inputWorkId == null || inputWorkId.isBlank()) {
				throw new WorkMetadataValidationException("Douyin input does not contain a video or note work ID");
			}
			String raw;
			try {
				raw = gateway.fetch(inputWorkId, cookie);
			} catch (DouyinWorkFetchException e) {
				if ("F2_UPSTREAM_SOFT_BLOCK".equals(e.errorCode())) {
					cookieService.reportDetailSoftBlock("douyin", e.errorCode());
					throw new DouyinGlobalCooldownException(e.getMessage(),
							cookieService.douyinDetailSoftBackoffRetryAt(Duration.ofSeconds(5)), true, e);
				}
				if (e.cooldownApplied()) {
					cookieService.reportRisk("\u6296\u97f3", cookie, e.errorCode());
					throw new DouyinGlobalCooldownException(riskFailureMessage(e.getMessage(), "parsing"),
							cookieService.douyinGlobalRiskCooldownRetryAt(Duration.ofSeconds(5)), true, e);
				}
				throw new WorkMetadataValidationException("Douyin parsing failed: " + e.errorCode(), e);
			}
			WorkMetadata metadata = parseRaw(raw, inputWorkId, request.getInput(), resolvedUrl);
			cookieService.reportSuccess("抖音", cookie);
			return metadata;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new WorkMetadataValidationException("Douyin F2 request coordination was interrupted", e);
		} catch (IOException e) {
			if (reportRisk(cookie, e.getMessage(), "parse request failed")) {
				throw new DouyinGlobalCooldownException(riskFailureMessage(e.getMessage(), "parsing"),
						cookieService.douyinGlobalRiskCooldownRetryAt(Duration.ofSeconds(5)));
			}
			throw new WorkMetadataValidationException("Douyin parsing failed", e);
		} catch (WorkMetadataValidationException e) {
			if (!(e instanceof DouyinGlobalCooldownException)) {
				reportRisk(cookie, e.getMessage(), "parse response rejected");
			}
			throw e;
		}
	}

	private WorkMetadata parseSnapshot(String raw, String expectedWorkId, String originalInput, String resolvedUrl) {
		JSONObject root = parseObject(raw);
		JSONObject detail = DouUtil.findAwemeDetail(root);
		String snapshotWorkId = detail == null ? null : detail.getString("aweme_id");
		if (snapshotWorkId == null || snapshotWorkId.isBlank()) {
			throw new WorkMetadataValidationException("Douyin list snapshot has no work ID");
		}
		if (!expectedWorkId.equals(snapshotWorkId.trim())) {
			throw new WorkMetadataValidationException("Douyin list snapshot work ID does not match request");
		}
		WorkMetadata metadata = parseRaw(raw, expectedWorkId, originalInput, resolvedUrl);
		if (!expectedWorkId.equals(metadata.getWorkId())) {
			throw new WorkMetadataValidationException("Douyin list snapshot parsed a different work ID");
		}
		logger.info("[DouyinSnapshot] accepted list snapshot workId={} contentType={} resources={}", expectedWorkId,
				metadata.getContentType(), metadata.getMediaResources().size());
		return metadata;
	}

	@Override
	public DownloadResult download(WorkMetadata metadata, WorkDownloadRequest request) {
		if (metadata == null || request == null) {
			throw new IllegalArgumentException("metadata and download request are required");
		}
		if (!"douyin".equals(metadata.getPlatformKey())) {
			throw new WorkMetadataValidationException("Douyin adapter cannot download another platform work");
		}
		String cookie = requireCookie("single_work_download");
		try {
			List<WorkMediaResource> downloaded = new ArrayList<>();
			for (WorkMediaResource source : metadata.getMediaResources()) {
				if (source.getType() == WorkMediaResource.Type.AUDIO) continue;
				String extension = extension(source);
				Path target = request.getOutputDirectory().resolve(safeName(metadata.getWorkId())
						+ "-index-" + source.getOrder() + "." + extension);
				Path local = gateway.download(source, target, cookie);
				downloaded.add(new WorkMediaResource(source.getOrder(), source.getType(), source.getSourceUrl(),
						local, extension, source.getRequestHeaders()));
			}
			if (metadata.getContentType() == WorkContentType.VIDEO && metadata.getCoverUrl() != null
					&& !metadata.getCoverUrl().isBlank()) {
				try {
					WorkMediaResource coverSource = resource(downloaded.size(), WorkMediaResource.Type.IMAGE,
							metadata.getCoverUrl(), "jpg");
					Path cover = request.getOutputDirectory().resolve(safeName(metadata.getWorkId()) + ".jpg");
					Path local = gateway.download(coverSource, cover, cookie);
					downloaded.add(new WorkMediaResource(coverSource.getOrder(), WorkMediaResource.Type.IMAGE,
							metadata.getCoverUrl(), local, "jpg", coverSource.getRequestHeaders()));
				} catch (IOException error) {
					if (reportRisk(cookie, error.getMessage(), "cover download request failed")) {
						throw new DouyinGlobalCooldownException(
								riskFailureMessage(error.getMessage(), "cover download"),
								cookieService.douyinGlobalRiskCooldownRetryAt(Duration.ofSeconds(5)));
					}
				}
			}
			if (downloaded.isEmpty()) {
				throw new WorkMetadataValidationException("Douyin work has no downloadable visual media");
			}
			cookieService.reportSuccess("抖音", cookie);
			return DownloadResult.completed(downloaded);
		} catch (IOException e) {
			if (reportRisk(cookie, e.getMessage(), "download request failed")) {
				throw new DouyinGlobalCooldownException(riskFailureMessage(e.getMessage(), "download"),
						cookieService.douyinGlobalRiskCooldownRetryAt(Duration.ofSeconds(5)));
			}
			throw new WorkMetadataValidationException("Douyin download failed", e);
		}
	}

	@Override
	public void postProcessDownloaded(WorkMetadata metadata, Path outputDirectory,
			List<WorkMediaResource> downloadedResources) {
		if (!Global.getGeneratenfo || metadata.getContentType() != WorkContentType.VIDEO) return;
		String cover = downloadedResources.stream().filter(value -> value.getType() == WorkMediaResource.Type.IMAGE)
				.map(value -> value.getLocalPath().getFileName().toString()).findFirst().orElse(metadata.getCoverUrl());
		EmbyMetadataGenerator.createDouNfo(metadata.getAuthorName(), metadata.getAuthorId(), metadata.getAuthorAvatar(),
				metadata.getPublishTime(), metadata.getWorkId(), metadata.getTitle(), metadata.getDescription(), cover,
				outputDirectory.toString());
	}

	WorkMetadata parseRaw(String raw, String fallbackWorkId, String originalInput, String resolvedUrl) {
		JSONObject root = parseObject(raw);
		JSONObject detail = DouUtil.findAwemeDetail(root);
		if (detail == null) {
			throw new WorkMetadataValidationException("Douyin response has no aweme detail");
		}
		String workId = firstText(detail.getString("aweme_id"), fallbackWorkId);
		JSONObject author = detail.getJSONObject("author");
		String secUid = author == null ? null : author.getString("sec_uid");
		String uid = author == null ? null : author.getString("uid");
		String authorId = AuthorIdentityUtil.canonicalAuthorUid("douyin", uid, secUid);
		List<WorkMediaResource> resources = new ArrayList<>();
		JSONArray images = detail.getJSONArray("images");
		boolean hasImages = images != null && !images.isEmpty();
		boolean hasImageResource = false;
		boolean hasVideoResource = false;
		if (hasImages) {
			for (int i = 0; i < images.size(); i++) {
				JSONObject item = images.getJSONObject(i);
				JSONObject itemVideo = item == null ? null : item.getJSONObject("video");
				if (itemVideo != null) {
					String url = mediaUrl(itemVideo.getJSONObject("play_addr"));
					if (url == null) throw new WorkMetadataValidationException("Douyin mixed item has no video URL");
					resources.add(resource(i, WorkMediaResource.Type.VIDEO, url, "mp4"));
					hasVideoResource = true;
				} else {
					String url = mediaUrl(item);
					if (url == null) throw new WorkMetadataValidationException("Douyin image item has no image URL");
					resources.add(resource(i, WorkMediaResource.Type.IMAGE, url, imageExtension(url)));
					hasImageResource = true;
				}
			}
		} else {
			JSONObject video = detail.getJSONObject("video");
			String url = video == null ? null : mediaUrl(video.getJSONObject("play_addr"));
			if (url == null) throw new WorkMetadataValidationException("Douyin video has no playable URL");
			resources.add(resource(0, WorkMediaResource.Type.VIDEO, url, "mp4"));
			hasVideoResource = true;
		}

		WorkContentType contentType = hasImages
				? (hasImageResource && !hasVideoResource ? WorkContentType.GRAPHIC : WorkContentType.MIXED)
				: WorkContentType.VIDEO;
		String sourceUrl = contentType == WorkContentType.VIDEO
				? DouyinSourceUrlUtil.video(workId)
				: firstText(DouyinSourceUrlUtil.graphic(authorId, workId), DouyinSourceUrlUtil.note(workId));
		return WorkMetadata.builder()
				.platform(PlatformCatalog.requireByKey("douyin"))
				.workId(workId)
				.contentType(contentType)
				.title(detail.getString("desc"))
				.description(detail.getString("desc"))
				.authorId(authorId)
				.authorUsername(author == null ? null : author.getString("unique_id"))
				.authorName(author == null ? null : author.getString("nickname"))
				.authorAvatar(DouUtil.extractAvatar(author))
				.authorHomepage(AuthorIdentityUtil.douyinHomepage(authorId))
				.authorSignature(author == null ? null : author.getString("signature"))
				.publishTime(detail.getString("create_time"))
				.sourceUrl(firstText(sourceUrl, resolvedUrl))
				.originalAddress(originalInput)
				.coverUrl(coverUrl(detail, images))
				.mediaResources(resources)
				.rawMetadata(root.toJSONString())
				.build();
	}

	private WorkMediaResource resource(int order, WorkMediaResource.Type type, String url, String extension) {
		return new WorkMediaResource(order, type, url, null, extension,
				Map.of("Referer", DouUtil.referer, "User-Agent", DouUtil.ua));
	}

	private String coverUrl(JSONObject detail, JSONArray images) {
		JSONObject video = detail.getJSONObject("video");
		if (video != null) {
			String cover = mediaUrl(video.getJSONObject("cover"));
			if (cover == null) cover = mediaUrl(video.getJSONObject("origin_cover"));
			if (cover != null) return cover;
		}
		return images == null || images.isEmpty() ? null : mediaUrl(images.getJSONObject(0));
	}

	private String mediaUrl(JSONObject object) {
		if (object == null) return null;
		JSONArray urls = object.getJSONArray("url_list");
		if ((urls == null || urls.isEmpty()) && object.getJSONObject("download_url_list") != null) {
			urls = object.getJSONObject("download_url_list").getJSONArray("url_list");
		}
		if (urls == null || urls.isEmpty()) return object.getString("url");
		for (int i = urls.size() - 1; i >= 0; i--) {
			String value = urls.getString(i);
			if (value != null && !value.isBlank()) return value;
		}
		return null;
	}

	private JSONObject parseObject(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			throw new WorkMetadataValidationException("Douyin returned no metadata");
		}
		try {
			JSONObject object = JSONObject.parseObject(raw);
			if (object == null) throw new IllegalArgumentException();
			return object;
		} catch (RuntimeException e) {
			throw new WorkMetadataValidationException("Douyin returned invalid metadata", e);
		}
	}

	private String requireCookie(String purpose) {
		String leased = operationCookie.get();
		if (leased != null && !leased.isBlank()) return leased;
		if (!cookieService.hasConfiguredDouyinCookie()) {
			throw new WorkMetadataValidationException("Douyin cookie is not configured");
		}
		String cookie = cookieService.currentDouyinCookie(purpose);
		if (cookie == null || cookie.trim().isEmpty()) {
			if (cookieService.isDouyinGlobalRiskCooldownActive()) {
				throw new DouyinGlobalCooldownException("Douyin global cooldown is active",
						cookieService.douyinGlobalRiskCooldownRetryAt(Duration.ofSeconds(5)));
			}
			throw new WorkMetadataValidationException("Douyin cookie is not configured");
		}
		return cookie;
	}

	private boolean reportRisk(String cookie, String signal, String fallback) {
		if (cookieService.isRiskSignal(signal)) {
			cookieService.reportRisk("抖音", cookie, fallback);
			return true;
		}
		return false;
	}

	private String riskFailureMessage(String signal, String operation) {
		String lower = signal == null ? "" : signal.toLowerCase(java.util.Locale.ROOT);
		if (lower.contains("429") || lower.contains("too many requests")
				|| lower.contains("rate limit") || lower.contains("ratelimit")) {
			return "Douyin upstream rate limit rejected " + operation + " (HTTP 429)";
		}
		return "Douyin authentication or risk control rejected " + operation;
	}

	private String extension(WorkMediaResource source) {
		if (source.getExpectedExtension() != null) return source.getExpectedExtension();
		return source.getType() == WorkMediaResource.Type.IMAGE ? "jpg" : "mp4";
	}

	private String imageExtension(String url) {
		String lower = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
		return lower.contains(".png") ? "png" : "jpg";
	}

	private String safeName(String value) {
		String safe = value == null ? "douyin-work" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
		return safe.isEmpty() ? "douyin-work" : safe;
	}

	private String firstText(String first, String second) {
		return first != null && !first.trim().isEmpty() ? first.trim()
				: second == null || second.trim().isEmpty() ? null : second.trim();
	}

	static Gateway systemGateway() {
		return new Gateway() {
			@Override public String resolve(String url) throws IOException {
				return DouUtil.resolveWorkUrl(url);
			}
			@Override public String fetch(String workId, String cookie) throws IOException {
				String raw = DouUtil.fetchWorkDataJson(workId, cookie);
				if (raw == null) throw new IOException("empty f2 work response");
				return raw;
			}
			@Override public Path download(WorkMediaResource source, Path destination, String cookie) throws IOException {
				return HttpMediaDownloader.download(source.getSourceUrl(), destination, cookie,
						source.getRequestHeaders());
			}
		};
	}

	interface Gateway {
		String resolve(String url) throws IOException;
		String fetch(String workId, String cookie) throws IOException;
		Path download(WorkMediaResource source, Path destination, String cookie) throws IOException;
	}
}
