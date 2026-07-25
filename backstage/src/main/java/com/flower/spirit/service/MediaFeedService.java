package com.flower.spirit.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.dao.MediaFeedQueryDao;
import com.flower.spirit.dto.AdminMediaFeedItem;
import com.flower.spirit.dto.AdminMediaSlide;
import com.flower.spirit.dto.AdminVideoListItem;
import com.flower.spirit.dto.FeedCursor;
import com.flower.spirit.dto.MediaFeedCursorPage;
import com.flower.spirit.dto.MediaFeedRequest;
import com.flower.spirit.dto.MediaFeedRow;
import com.flower.spirit.entity.AuthorProfileEntity;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.utils.AuthorIdentityUtil;

@Service
public class MediaFeedService {

	private static final Logger logger = LoggerFactory.getLogger(MediaFeedService.class);
	private static final List<String> IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp", ".gif");
	private static final List<String> VIDEO_EXTENSIONS = List.of(".mp4", ".webm", ".mov", ".m4v");
	private static final List<DateTimeFormatter> PUBLISH_TIME_FORMATTERS = List.of(
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"));

	@Autowired
	private VideoDataService videoDataService;

	@Autowired
	private GraphicContentService graphicContentService;

	@Autowired
	private AuthorProfileDao authorProfileDao;

	@Autowired
	private MediaFeedQueryDao mediaFeedQueryDao;

	@Autowired
	private FeedCursorCodec feedCursorCodec;

	@Autowired
	private HlsTranscodeService hlsTranscodeService;

	@Value("${streamvault.feed.keyset.enabled:true}")
	private boolean keysetEnabled;

	public MediaFeedCursorPage findCursorPage(MediaFeedRequest source) {
		if (!keysetEnabled) throw new IllegalArgumentException("keyset media feed is disabled");
		MediaFeedRequest request = normalizeCursorRequest(source);
		String filterHash = cursorFilterHash(request);
		FeedCursor cursor = feedCursorCodec.decode(request.getCursor());
		validateCursor(cursor, request, filterHash);
		int limit = request.getLimit();
		List<MediaFeedRow> rows = new ArrayList<>(mediaFeedQueryDao.find(request, cursor, limit + 1));
		boolean hasMore = rows.size() > limit;
		if (hasMore) rows.remove(rows.size() - 1);
		Set<Integer> queuedIds = hlsTranscodeService == null ? Set.of() : hlsTranscodeService.queuedIdsSnapshot();
		Set<Integer> runningIds = hlsTranscodeService == null ? Set.of() : hlsTranscodeService.runningVideoIdsSnapshot();
		List<AdminMediaFeedItem> items = rows.stream()
				.map(row -> toCursorFeedItem(row, queuedIds, runningIds))
				.toList();
		enrichDisplayAuthors(items);
		String nextCursor = null;
		if (hasMore && !rows.isEmpty()) {
			MediaFeedRow last = rows.get(rows.size() - 1);
			nextCursor = feedCursorCodec.encode(new FeedCursor(Instant.ofEpochMilli(last.sortTimeMillis()),
					last.mediaType(), last.internalId(), request.getOrder(), filterHash));
		}
		return new MediaFeedCursorPage(items, nextCursor, hasMore);
	}

	public boolean isKeysetEnabled() {
		return keysetEnabled;
	}

	private MediaFeedRequest normalizeCursorRequest(MediaFeedRequest source) {
		MediaFeedRequest request = new MediaFeedRequest();
		String type = source == null ? null : source.getType();
		String order = source == null ? null : source.getOrder();
		request.setType(normalizeMediaType(type));
		request.setOrder("asc".equalsIgnoreCase(order) ? "asc" : "desc");
		request.setLimit(Math.min(Math.max(source == null || source.getLimit() == null ? 20 : source.getLimit(), 1), 100));
		request.setCursor(source == null ? null : trimToNull(source.getCursor()));
		String platformKey = source == null ? null : trimToNull(source.getPlatformKey());
		request.setPlatformKey(platformKey == null ? null : PlatformCatalog.canonicalKey(platformKey, platformKey));
		request.setAuthorUid(source == null ? null : trimToNull(source.getAuthorUid()));
		if (request.getAuthorUid() != null && request.getPlatformKey() == null) {
			throw new IllegalArgumentException("platformKey is required when authorUid is provided");
		}
		return request;
	}

	private void validateCursor(FeedCursor cursor, MediaFeedRequest request, String filterHash) {
		if (cursor == null) return;
		if (!request.getOrder().equals(cursor.order()) || !filterHash.equals(cursor.filterHash())) {
			throw new IllegalArgumentException("feed cursor does not match the current filters");
		}
		if (cursor.sortTime() == null || cursor.internalId() <= 0
				|| !("video".equals(cursor.mediaType()) || "graphic".equals(cursor.mediaType()))) {
			throw new IllegalArgumentException("feed cursor is incomplete");
		}
	}

	private String cursorFilterHash(MediaFeedRequest request) {
		String value = String.join("|", request.getType(), request.getOrder(),
				String.valueOf(request.getPlatformKey()), String.valueOf(request.getAuthorUid()));
		try {
			return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}

	private AdminMediaFeedItem toCursorFeedItem(MediaFeedRow row, Set<Integer> queuedIds,
			Set<Integer> runningIds) {
		AdminMediaFeedItem item = new AdminMediaFeedItem();
		item.setType(row.mediaType());
		item.setId(row.internalId());
		item.setMediaKey(row.mediaKey());
		item.setVideoid(row.workId());
		String platformKey = PlatformMetadataCompatibilityService.resolvePlatformKey(row.platformKey(),
				row.platformDisplayName());
		item.setPlatformkey(platformKey);
		item.setPlatform(PlatformMetadataCompatibilityService.resolveDisplayName(platformKey,
				row.platformDisplayName()));
		item.setAuthor(row.authorDisplayName());
		item.setAuthoruid(row.authorUid());
		item.setSecuid(row.authorUid());
		item.setAuthorusername(row.authorUsername());
		item.setUniqueid(row.authorUsername());
		item.setAuthoravatar(row.authorAvatar());
		item.setAuthorhomepage(row.authorHomepage());
		item.setTitle(row.title());
		item.setDesc(row.summary());
		item.setPublishTime(row.publishTime() == null ? null : row.publishTime().toString());
		item.setCreateTime(row.downloadedAt() == null ? null : Date.from(row.downloadedAt()));
		item.setCover(row.coverUrl());
		item.setSourceurl(row.sourceUrl());
		item.setOriginaladdress(row.originalAddress());
		item.setFavorite(row.favorite());
		item.setPrivacy(row.privacy());
		item.setContenttype(row.contentType());
		item.setFallbackUrl(row.fallbackUrl());
		item.setSlides(row.slides().stream().map(slide -> new AdminMediaSlide(slide.type(), slide.url())).toList());
		if ("video".equals(row.mediaType())) applyCursorPlayback(item, row, queuedIds, runningIds);
		if (!"video".equals(row.mediaType()) && !hasText(item.getContenttype())) {
			item.setContenttype(item.getSlides().stream().anyMatch(slide -> "video".equals(slide.getType()))
					? "mixed" : "graphic");
		}
		normalizeAuthorIdentity(item);
		return item;
	}

	private void applyCursorPlayback(AdminMediaFeedItem item, MediaFeedRow row, Set<Integer> queuedIds,
			Set<Integer> runningIds) {
		String playUrl = row.fallbackUrl();
		VideoDataEntity video = new VideoDataEntity();
		video.setId(row.internalId());
		video.setVideoaddr(row.localVideoPath());
		video.setVideounrealaddr(row.fallbackUrl());
		boolean hasHls = Global.hlsEnable && hlsTranscodeService != null && hlsTranscodeService.hasHls(video);
		if (hasHls) {
			String hls = hlsTranscodeService.buildHlsPlayUrl(video);
			if (hasText(hls)) playUrl = hls;
		}
		item.setPlayurl(playUrl);
		if (!Global.hlsEnable) item.setHlsstatus("关闭");
		else if (hasHls) item.setHlsstatus("已完成");
		else if (runningIds.contains(row.internalId())) item.setHlsstatus("转码中");
		else if (queuedIds.contains(row.internalId())) item.setHlsstatus("排队中");
		else item.setHlsstatus("未完成");
	}

	public AjaxEntity findPage(VideoDataEntity query) {
		VideoDataEntity videoQuery = copyVideoQuery(query);
		GraphicContentEntity graphicQuery = toGraphicQuery(query);
		String mediaType = normalizeMediaType(videoQuery.getMediaType());
		int pageNo = videoQuery.getPageNo();
		int pageSize = Math.max(1, videoQuery.getPageSize());
		int fetchSize = Math.max(pageSize, (pageNo + 1) * pageSize);
		boolean randomMode = "1".equals(String.valueOf(videoQuery.getRandomMode()));

		Map<String, AdminMediaFeedItem> mergedItems = new LinkedHashMap<>();
		long totalElements = 0;

		if (!"graphic".equals(mediaType)) {
			totalElements += appendVideoCandidates(mergedItems, videoQuery, fetchSize, true);
		}
		if (!"graphic".equals(mediaType) && shouldFetchCreateTimeCandidates(videoQuery)) {
			appendVideoCandidates(mergedItems, videoQuery, fetchSize, false);
		}

		if (!"video".equals(mediaType) && !"1".equals(videoQuery.getFavorite())) {
			totalElements += appendGraphicCandidates(mergedItems, graphicQuery, fetchSize, true);
			if (shouldFetchCreateTimeCandidates(videoQuery)) {
				appendGraphicCandidates(mergedItems, graphicQuery, fetchSize, false);
			}
		}

		List<AdminMediaFeedItem> orderedItems = new ArrayList<>(mergedItems.values());

		if (randomMode) {
			stabilizeRandomSourceOrder(orderedItems);
			String randomSeed = videoQuery.getRandomSeed();
			long seed = randomSeed == null ? System.nanoTime() : randomSeed.hashCode();
			Collections.shuffle(orderedItems, new java.util.Random(seed));
		} else {
			orderedItems.sort(feedComparator("asc".equalsIgnoreCase(videoQuery.getSortOrder())));
		}
		int from = Math.min(pageNo * pageSize, orderedItems.size());
		int to = Math.min(from + pageSize, orderedItems.size());
		List<AdminMediaFeedItem> pageItems = from >= to ? List.of() : new ArrayList<>(orderedItems.subList(from, to));
		enrichDisplayAuthors(pageItems);
		Page<AdminMediaFeedItem> page = new PageImpl<>(pageItems, PageRequest.of(pageNo, pageSize), totalElements);
		return new AjaxEntity(Global.ajax_success, "success", page);
	}

	private long appendVideoCandidates(Map<String, AdminMediaFeedItem> mergedItems, VideoDataEntity baseQuery,
			int fetchSize, boolean countTotal) {
		VideoDataEntity query = copyVideoQuery(baseQuery);
		query.setPageNo(1);
		query.setPageSize(fetchSize);
		if (!countTotal) {
			query.setSortField("createtime");
			query.setSortOrder("desc");
		}
		AjaxEntity videoResponse = videoDataService.findPage(query, true);
		Page<?> videoPage = pageFrom(videoResponse);
		if (videoPage == null) {
			return 0;
		}
		for (Object row : videoPage.getContent()) {
			AdminMediaFeedItem item = toVideoFeedItem(row);
			putMediaItem(mergedItems, item);
		}
		return countTotal ? videoPage.getTotalElements() : 0;
	}

	private long appendGraphicCandidates(Map<String, AdminMediaFeedItem> mergedItems, GraphicContentEntity baseQuery,
			int fetchSize, boolean countTotal) {
		GraphicContentEntity query = copyGraphicQuery(baseQuery);
		query.setPageNo(1);
		query.setPageSize(fetchSize);
		if (!countTotal) {
			query.setSortField("createtime");
			query.setSortOrder("desc");
		}
		AjaxEntity graphicResponse = graphicContentService.findLitePage(query);
		Page<?> graphicPage = pageFrom(graphicResponse);
		if (graphicPage == null) {
			return 0;
		}
		for (Object row : graphicPage.getContent()) {
			if (row instanceof GraphicContentEntity graphic) {
				AdminMediaFeedItem item = toGraphicFeedItemForTest(graphic);
				if (!item.getSlides().isEmpty()) {
					putMediaItem(mergedItems, item);
				}
			}
		}
		return countTotal ? graphicPage.getTotalElements() : 0;
	}

	private void putMediaItem(Map<String, AdminMediaFeedItem> items, AdminMediaFeedItem item) {
		if (item == null || item.getMediaKey() == null) {
			return;
		}
		items.putIfAbsent(item.getMediaKey(), item);
	}

	private boolean shouldFetchCreateTimeCandidates(VideoDataEntity query) {
		return query != null && !"1".equals(String.valueOf(query.getRandomMode()))
				&& "publishtime".equals(query.getSortField());
	}

	public List<AdminMediaSlide> parseGraphicSlidesForTest(String rawImages) {
		List<AdminMediaSlide> slides = new ArrayList<>();
		if (rawImages == null || rawImages.trim().isEmpty()) {
			return slides;
		}
		try {
			List<String> urls = JSON.parseArray(rawImages, String.class);
			if (urls == null) {
				return slides;
			}
			for (String url : urls) {
				String type = detectSlideType(url);
				if (type != null) {
					slides.add(new AdminMediaSlide(type, url));
				}
			}
		} catch (Exception e) {
			logger.warn("Failed to parse graphic images JSON for media feed: {}", rawImages, e);
			return List.of();
		}
		return slides;
	}

	public AdminMediaFeedItem toGraphicFeedItemForTest(GraphicContentEntity graphic) {
		AdminMediaFeedItem item = new AdminMediaFeedItem();
		if (graphic == null) {
			return item;
		}
		List<AdminMediaSlide> slides = parseGraphicSlidesForTest(graphic.getImages());
		PlatformMetadataCompatibilityService.enrichCanonicalGraphic(graphic);
		item.setType("graphic");
		item.setId(graphic.getId());
		item.setMediaKey("graphic:" + graphic.getId());
		item.setVideoid(graphic.getVideoid());
		item.setPlatformkey(graphic.getPlatformkey());
		item.setPlatform(PlatformMetadataCompatibilityService.resolveDisplayName(graphic.getPlatformkey(), graphic.getPlatform()));
		item.setContenttype(graphic.getContenttype());
		item.setAuthor(graphic.getAuthor());
		item.setAuthoruid(graphic.getAuthoruid());
		item.setSecuid(graphic.getSecuid());
		item.setAuthorusername(graphic.getAuthorusername());
		item.setUniqueid(graphic.getUniqueid());
		item.setAuthoravatar(graphic.getAuthoravatar());
		item.setAuthorhomepage(graphic.getAuthorhomepage());
		item.setTitle(graphic.getTitle());
		item.setDesc(graphic.getContent());
		item.setPublishTime(graphic.getPublishtime());
		item.setCreateTime(graphic.getCreatetime());
		item.setCover(slides.isEmpty() ? null : slides.get(0).getUrl());
		item.setSourceurl(graphic.getSourceurl());
		item.setOriginaladdress(graphic.getOriginaladdress());
		item.setFavorite(graphic.getFavorite());
		item.setPrivacy(graphic.getPrivacy());
		item.setSlides(slides);
		normalizeAuthorIdentity(item);
		return item;
	}

	public AdminMediaFeedItem toVideoFeedItemForTest(VideoDataEntity video) {
		return toVideoFeedItem(AdminVideoListItem.from(video));
	}

	private AdminMediaFeedItem toVideoFeedItem(Object row) {
		if (row instanceof AdminVideoListItem video) {
			AdminMediaFeedItem item = new AdminMediaFeedItem();
			item.setType("video");
			item.setId(video.getId());
			item.setMediaKey("video:" + video.getId());
			item.setVideoid(video.getVideoid());
			item.setPlatformkey(video.getPlatformkey());
			item.setPlatform(video.getPlatformDisplayName() == null ? video.getVideoplatform() : video.getPlatformDisplayName());
			item.setContenttype(video.getContenttype());
			item.setAuthor(video.getVideoauthor());
			item.setAuthoruid(video.getAuthoruid());
			item.setSecuid(video.getSecuid());
			item.setAuthorusername(video.getAuthorusername());
			item.setUniqueid(video.getUniqueid());
			item.setAuthoravatar(video.getAuthoravatar());
			item.setAuthorhomepage(video.getAuthorhomepage());
			item.setTitle(video.getVideoname());
			item.setDesc(video.getVideodesc());
			item.setPublishTime(video.getPublishtime());
			item.setCreateTime(video.getCreatetime());
			item.setCover(video.getVideocover());
			item.setPlayurl(video.getPlayurl());
			item.setFallbackUrl(video.getVideounrealaddr());
			item.setHlsstatus(video.getHlsstatus());
			item.setSourceurl(video.getSourceurl());
			item.setOriginaladdress(video.getOriginaladdress());
			item.setFavorite(video.getFavorite());
			item.setPrivacy(video.getVideoprivacy());
			normalizeAuthorIdentity(item);
			return item;
		}
		if (row instanceof VideoDataEntity video) {
			return toVideoFeedItemForTest(video);
		}
		return null;
	}

	private String detectSlideType(String url) {
		if (url == null || url.trim().isEmpty()) {
			return null;
		}
		String lower = stripQuery(url).toLowerCase(Locale.ROOT);
		if (hasAnyExtension(lower, IMAGE_EXTENSIONS)) {
			return "image";
		}
		if (hasAnyExtension(lower, VIDEO_EXTENSIONS)) {
			return "video";
		}
		return null;
	}

	private boolean hasAnyExtension(String value, List<String> extensions) {
		for (String extension : extensions) {
			if (value.endsWith(extension)) {
				return true;
			}
		}
		return false;
	}

	private String stripQuery(String url) {
		int queryIndex = url.indexOf('?');
		int hashIndex = url.indexOf('#');
		int end = url.length();
		if (queryIndex >= 0) {
			end = Math.min(end, queryIndex);
		}
		if (hashIndex >= 0) {
			end = Math.min(end, hashIndex);
		}
		return url.substring(0, end);
	}

	private Page<?> pageFrom(AjaxEntity response) {
		if (response != null && response.getRecord() instanceof Page<?> page) {
			return page;
		}
		return null;
	}

	private VideoDataEntity copyVideoQuery(VideoDataEntity source) {
		VideoDataEntity target = new VideoDataEntity();
		if (source == null) {
			return target;
		}
		target.setPageNo(source.getPageNo() + 1);
		target.setPageSize(source.getPageSize());
		target.setVideoname(source.getVideoname());
		target.setVideodesc(source.getVideodesc());
		target.setVideoplatform(source.getVideoplatform());
		target.setExcludePlatform(source.getExcludePlatform());
		target.setVideotag(source.getVideotag());
		target.setVideoauthor(source.getVideoauthor());
		target.setAuthoruid(source.getAuthoruid());
		target.setSecuid(source.getSecuid());
		target.setPublishStart(source.getPublishStart());
		target.setPublishEnd(source.getPublishEnd());
		target.setSortField(source.getSortField());
		target.setSortOrder(source.getSortOrder());
		target.setFavorite(source.getFavorite());
		target.setRandomMode(source.getRandomMode());
		target.setRandomSeed(source.getRandomSeed());
		target.setMediaType(source.getMediaType());
		return target;
	}

	private String normalizeMediaType(String mediaType) {
		if (mediaType == null) {
			return "mixed";
		}
		String normalized = mediaType.trim().toLowerCase(Locale.ROOT);
		if ("video".equals(normalized) || "graphic".equals(normalized)) {
			return normalized;
		}
		return "mixed";
	}

	private void stabilizeRandomSourceOrder(List<AdminMediaFeedItem> items) {
		items.sort(Comparator
				.comparing(AdminMediaFeedItem::getType, Comparator.nullsLast(String::compareTo))
				.thenComparing(AdminMediaFeedItem::getId, Comparator.nullsLast(Integer::compareTo)));
	}

	private GraphicContentEntity toGraphicQuery(VideoDataEntity videoQuery) {
		GraphicContentEntity graphicQuery = new GraphicContentEntity();
		if (videoQuery == null) {
			return graphicQuery;
		}
		graphicQuery.setTitle(videoQuery.getVideoname());
		graphicQuery.setContent(videoQuery.getVideodesc());
		graphicQuery.setPlatform(videoQuery.getVideoplatform());
		graphicQuery.setAuthor(videoQuery.getVideoauthor());
		graphicQuery.setAuthoruid(videoQuery.getAuthoruid());
		graphicQuery.setSecuid(videoQuery.getSecuid());
		graphicQuery.setPublishStart(videoQuery.getPublishStart());
		graphicQuery.setPublishEnd(videoQuery.getPublishEnd());
		graphicQuery.setSortField(toGraphicSortField(videoQuery.getSortField()));
		graphicQuery.setSortOrder(videoQuery.getSortOrder());
		return graphicQuery;
	}

	private GraphicContentEntity copyGraphicQuery(GraphicContentEntity source) {
		GraphicContentEntity target = new GraphicContentEntity();
		if (source == null) {
			return target;
		}
		target.setTitle(source.getTitle());
		target.setContent(source.getContent());
		target.setPlatform(source.getPlatform());
		target.setAuthor(source.getAuthor());
		target.setAuthoruid(source.getAuthoruid());
		target.setSecuid(source.getSecuid());
		target.setPublishStart(source.getPublishStart());
		target.setPublishEnd(source.getPublishEnd());
		target.setSortField(source.getSortField());
		target.setSortOrder(source.getSortOrder());
		return target;
	}

	private String toGraphicSortField(String videoSortField) {
		if ("videoauthor".equals(videoSortField)) {
			return "author";
		}
		if ("publishtime".equals(videoSortField) || "createtime".equals(videoSortField)) {
			return videoSortField;
		}
		return null;
	}

	private Comparator<AdminMediaFeedItem> feedComparator(boolean ascending) {
		return (left, right) -> {
			int timeCompare = compareFeedTime(left, right, ascending);
			if (timeCompare != 0) {
				return timeCompare;
			}
			int createCompare = compareNullable(left.getCreateTime(), right.getCreateTime(), ascending);
			if (createCompare != 0) {
				return createCompare;
			}
			return compareNullable(left.getId(), right.getId(), ascending);
		};
	}

	private int compareFeedTime(AdminMediaFeedItem left, AdminMediaFeedItem right, boolean ascending) {
		Long leftTime = feedTimeMillis(left);
		Long rightTime = feedTimeMillis(right);
		if (leftTime == null && rightTime == null) {
			return 0;
		}
		if (leftTime == null) {
			return 1;
		}
		if (rightTime == null) {
			return -1;
		}
		int result = leftTime.compareTo(rightTime);
		return ascending ? result : -result;
	}

	private Long feedTimeMillis(AdminMediaFeedItem item) {
		if (item == null) {
			return null;
		}
		Long publishMillis = parsePublishTimeMillis(item.getPublishTime());
		if (publishMillis != null) {
			return publishMillis;
		}
		return item.getCreateTime() == null ? null : item.getCreateTime().getTime();
	}

	private Long parsePublishTimeMillis(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		String text = value.trim();
		try {
			return Instant.parse(text).toEpochMilli();
		} catch (Exception e) {
		}
		for (DateTimeFormatter formatter : PUBLISH_TIME_FORMATTERS) {
			try {
				return LocalDateTime.parse(text, formatter)
						.atZone(ZoneId.systemDefault())
						.toInstant()
						.toEpochMilli();
			} catch (Exception e) {
			}
		}
		return null;
	}

	private <T extends Comparable<T>> int compareNullable(T left, T right, boolean ascending) {
		if (left == null && right == null) {
			return 0;
		}
		if (left == null) {
			return 1;
		}
		if (right == null) {
			return -1;
		}
		int result = left.compareTo(right);
		return ascending ? result : -result;
	}

	private void enrichDisplayAuthors(List<AdminMediaFeedItem> items) {
		if (items == null || items.isEmpty() || authorProfileDao == null) {
			return;
		}
		Set<String> authorUids = new LinkedHashSet<>();
		for (AdminMediaFeedItem item : items) {
			normalizeAuthorIdentity(item);
			if (item != null && item.getAuthoruid() != null) {
				authorUids.add(item.getAuthoruid());
			}
		}
		if (authorUids.isEmpty()) {
			return;
		}
		Map<String, AuthorProfileEntity> profiles = new LinkedHashMap<>();
		for (AuthorProfileEntity profile : authorProfileDao.findByAuthoruidIn(authorUids)) {
			if (profile == null || profile.getAuthoruid() == null) {
				continue;
			}
			String key = authorProfileKey(profile.getPlatformkey(), profile.getPlatform(), profile.getAuthoruid());
			profiles.merge(key, profile, this::newerProfile);
		}
		for (AdminMediaFeedItem item : items) {
			if (item == null || item.getAuthoruid() == null) {
				continue;
			}
			String key = authorProfileKey(item.getPlatformkey(), item.getPlatform(), item.getAuthoruid());
			AuthorProfileEntity profile = profiles.get(key);
			if (profile != null) {
				applyAuthorProfile(item, item.getAuthoruid(), profile);
			}
		}
	}

	void enrichDisplayAuthorsForTest(List<AdminMediaFeedItem> items) {
		enrichDisplayAuthors(items);
	}

	private AuthorProfileEntity newerProfile(AuthorProfileEntity left, AuthorProfileEntity right) {
		if (left.getUpdatetime() == null) return right;
		if (right.getUpdatetime() == null) return left;
		return right.getUpdatetime().after(left.getUpdatetime()) ? right : left;
	}

	private String authorProfileKey(String platformKey, String platform, String authorUid) {
		String canonicalKey = AuthorIdentityUtil.canonicalPlatformKey(platformKey, platform);
		canonicalKey = canonicalKey == null ? "" : canonicalKey;
		return canonicalKey + "\u0001" + authorUid;
	}

	private void applyAuthorProfile(AdminMediaFeedItem item, String canonicalUid, AuthorProfileEntity profile) {
		if (profile == null) {
			return;
		}
		item.setProfileAuthorUid(canonicalUid);
		String name = profile.getDisplayname();
		if (name != null && !name.trim().isEmpty()) {
			item.setDisplayAuthor(name.trim());
		}
		if (profile.getUsername() != null && !profile.getUsername().trim().isEmpty()) {
			item.setAuthorusername(profile.getUsername().trim());
			item.setUniqueid(profile.getUsername().trim());
		}
		if (profile.getAvatar() != null && !profile.getAvatar().trim().isEmpty()) {
			item.setAuthoravatar(profile.getAvatar().trim());
		}
		if (profile.getHomepage() != null && !profile.getHomepage().trim().isEmpty()) {
			item.setAuthorhomepage(profile.getHomepage().trim());
		}
	}

	private void normalizeAuthorIdentity(AdminMediaFeedItem item) {
		if (item == null) {
			return;
		}
		String canonicalPlatformKey = AuthorIdentityUtil.canonicalPlatformKey(item.getPlatformkey(), item.getPlatform());
		String canonicalUid = AuthorIdentityUtil.canonicalAuthorUid(canonicalPlatformKey,
				item.getAuthoruid(), item.getSecuid());
		String canonicalUsername = AuthorIdentityUtil.canonicalUsername(item.getAuthorusername(), item.getUniqueid());
		item.setPlatformkey(canonicalPlatformKey);
		item.setAuthoruid(canonicalUid);
		item.setSecuid(canonicalUid);
		item.setAuthorusername(canonicalUsername);
		item.setUniqueid(canonicalUsername);
	}

	private String trimToNull(String value) {
		return hasText(value) ? value.trim() : null;
	}

	private boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
}
