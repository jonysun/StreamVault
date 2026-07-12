package com.flower.spirit.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dto.AdminMediaFeedItem;
import com.flower.spirit.dto.AdminMediaSlide;
import com.flower.spirit.dto.AdminVideoListItem;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;

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

	public AjaxEntity findPage(VideoDataEntity query) {
		VideoDataEntity videoQuery = copyVideoQuery(query);
		GraphicContentEntity graphicQuery = toGraphicQuery(query);
		int pageNo = videoQuery.getPageNo();
		int pageSize = Math.max(1, videoQuery.getPageSize());
		int fetchSize = Math.max(pageSize, (pageNo + 1) * pageSize);
		boolean randomMode = "1".equals(String.valueOf(videoQuery.getRandomMode()));

		Map<String, AdminMediaFeedItem> mergedItems = new LinkedHashMap<>();
		long totalElements = 0;

		totalElements += appendVideoCandidates(mergedItems, videoQuery, fetchSize, true);
		if (shouldFetchCreateTimeCandidates(videoQuery)) {
			appendVideoCandidates(mergedItems, videoQuery, fetchSize, false);
		}

		if (!"1".equals(videoQuery.getFavorite())) {
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
		AjaxEntity graphicResponse = graphicContentService.findPage(query);
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
		item.setType("graphic");
		item.setId(graphic.getId());
		item.setMediaKey("graphic:" + graphic.getId());
		item.setVideoid(graphic.getVideoid());
		item.setPlatform(graphic.getPlatform());
		item.setAuthor(graphic.getAuthor());
		item.setAuthoruid(graphic.getAuthoruid());
		item.setAuthorusername(graphic.getAuthorusername());
		item.setAuthoravatar(graphic.getAuthoravatar());
		item.setTitle(graphic.getTitle());
		item.setDesc(graphic.getContent());
		item.setPublishTime(graphic.getPublishtime());
		item.setCreateTime(graphic.getCreatetime());
		item.setCover(slides.isEmpty() ? null : slides.get(0).getUrl());
		item.setSourceurl(graphic.getSourceurl());
		item.setOriginaladdress(graphic.getOriginaladdress());
		item.setSlides(slides);
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
			item.setPlatform(video.getVideoplatform());
			item.setAuthor(video.getVideoauthor());
			item.setAuthoruid(video.getAuthoruid());
			item.setAuthorusername(video.getAuthorusername());
			item.setAuthoravatar(video.getAuthoravatar());
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
		target.setPublishStart(source.getPublishStart());
		target.setPublishEnd(source.getPublishEnd());
		target.setSortField(source.getSortField());
		target.setSortOrder(source.getSortOrder());
		target.setFavorite(source.getFavorite());
		target.setRandomMode(source.getRandomMode());
		target.setRandomSeed(source.getRandomSeed());
		return target;
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
}
