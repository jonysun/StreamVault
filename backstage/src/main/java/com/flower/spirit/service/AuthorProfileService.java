package com.flower.spirit.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.AuthorNameHistoryDao;
import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.dto.AdminAuthorProfileSummary;
import com.flower.spirit.dto.AdminMediaFeedItem;
import com.flower.spirit.dto.AdminMediaSlide;
import com.flower.spirit.entity.AuthorNameHistoryEntity;
import com.flower.spirit.entity.AuthorProfileEntity;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.utils.DouUtil;
import com.flower.spirit.utils.DouyinSourceUrlUtil;

import jakarta.persistence.criteria.Predicate;

@Service
public class AuthorProfileService {

	private static final List<String> IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp", ".gif");
	private static final List<String> VIDEO_EXTENSIONS = List.of(".mp4", ".webm", ".mov", ".m4v");

	@Autowired
	private AuthorProfileDao authorProfileDao;

	@Autowired
	private AuthorNameHistoryDao authorNameHistoryDao;

	@Autowired
	private VideoDataDao videoDataDao;

	@Autowired
	private GraphicContentDao graphicContentDao;

	public void upsertAuthor(String platform, String authoruid, String username, String displayName, String avatar, String homepage) {
		upsertAuthor(platform, authoruid, username, displayName, avatar, homepage, null);
	}

	public void upsertAuthor(String platform, String authoruid, String username, String displayName, String avatar,
			String homepage, String signature) {
		if (platform == null || platform.trim().isEmpty() || authoruid == null || authoruid.trim().isEmpty()) {
			return;
		}
		String safePlatform = platform.trim();
		String safeUid = authoruid.trim();
		if (isDouyinPlatformValue(safePlatform)) {
			safeUid = preferDouyinAuthorUid(safeUid, null);
			if (safeUid == null) {
				return;
			}
		}
		Date now = new Date();
		Optional<AuthorProfileEntity> opt = authorProfileDao.findByPlatformAndAuthoruid(safePlatform, safeUid);
		AuthorProfileEntity entity = opt.orElseGet(AuthorProfileEntity::new);
		if (entity.getId() == null) {
			entity.setCreatetime(now);
		}
		entity.setPlatform(safePlatform);
		entity.setAuthoruid(safeUid);
		if (username != null && !username.trim().isEmpty()) {
			entity.setUsername(username.trim());
		}
		if (displayName != null && !displayName.trim().isEmpty()) {
			entity.setDisplayname(displayName.trim());
		}
		if (avatar != null && !avatar.trim().isEmpty()) {
			entity.setAvatar(avatar.trim());
		}
		if (homepage != null && !homepage.trim().isEmpty()) {
			entity.setHomepage(homepage.trim());
		}
		if (signature != null && !signature.trim().isEmpty()) {
			entity.setSignature(signature.trim());
		}
		entity.setUpdatetime(now);
		AuthorProfileEntity saved = authorProfileDao.save(entity);
		if (saved.getId() != null && displayName != null && !displayName.trim().isEmpty()) {
			upsertNameHistory(saved.getId(), displayName.trim(), now);
		}
	}

	private void upsertNameHistory(Integer authorProfileId, String displayName, Date now) {
		Optional<AuthorNameHistoryEntity> historyOpt = authorNameHistoryDao.findByAuthorprofileidAndDisplayname(authorProfileId, displayName);
		AuthorNameHistoryEntity history = historyOpt.orElseGet(AuthorNameHistoryEntity::new);
		if (history.getId() == null) {
			history.setAuthorprofileid(authorProfileId);
			history.setDisplayname(displayName);
			history.setFirstseentime(now);
		}
		history.setLastseentime(now);
		authorNameHistoryDao.save(history);
	}

	public AjaxEntity findPage(AuthorProfileEntity queryEntity) {
		PageRequest pageRequest = PageRequest.of(queryEntity.getPageNo(), queryEntity.getPageSize());
		Specification<AuthorProfileEntity> specification = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (queryEntity.getPlatform() != null && !queryEntity.getPlatform().trim().isEmpty()) {
				predicates.add(cb.like(root.get("platform"), "%" + queryEntity.getPlatform().trim() + "%"));
			}
			if (queryEntity.getKeyword() != null && !queryEntity.getKeyword().trim().isEmpty()) {
				String kw = "%" + queryEntity.getKeyword().trim() + "%";
				predicates.add(cb.or(
					cb.like(root.get("displayname"), kw),
					cb.like(root.get("username"), kw),
					cb.like(root.get("authoruid"), kw)
				));
			}
			query.orderBy(cb.desc(root.get("updatetime")), cb.desc(root.get("id")));
			return cb.and(predicates.toArray(new Predicate[0]));
		};
		Page<AuthorProfileEntity> page = authorProfileDao.findAll(specification, pageRequest);
		return new AjaxEntity(Global.ajax_success, "数据获取成功", page);
	}

	public List<AuthorNameHistoryEntity> findNameHistory(Integer authorProfileId) {
		return authorNameHistoryDao.findByAuthorProfileIdOrderByLastSeen(authorProfileId);
	}

	public AjaxEntity findProfileSummary(String platform, String authoruid, String authorusername, String author) {
		Specification<VideoDataEntity> videoSpec = buildVideoAuthorSpec(platform, authoruid, authorusername, author);
		Specification<GraphicContentEntity> graphicSpec = buildGraphicAuthorSpec(platform, authoruid, authorusername, author);
		long videoCount = videoDataDao.count(videoSpec);
		long graphicCount = graphicContentDao.count(graphicSpec);
		AuthorProfileEntity profile = findBestProfile(platform, authoruid, authorusername, author);
		AdminAuthorProfileSummary summary = new AdminAuthorProfileSummary();
		if (profile != null) {
			summary.setId(profile.getId());
			summary.setPlatform(profile.getPlatform());
			summary.setAuthoruid(profile.getAuthoruid());
			summary.setUsername(profile.getUsername());
			summary.setDisplayname(profile.getDisplayname());
			summary.setAvatar(profile.getAvatar());
			summary.setHomepage(profile.getHomepage());
			summary.setSignature(profile.getSignature());
			summary.setUpdatetime(profile.getUpdatetime());
		}
		summary.setPlatform(firstNotBlank(summary.getPlatform(), platform));
		summary.setAuthoruid(firstNotBlank(summary.getAuthoruid(), authoruid));
		summary.setUsername(firstNotBlank(summary.getUsername(), authorusername));
		summary.setDisplayname(firstNotBlank(summary.getDisplayname(), author));
		summary.setVideoCount(videoCount);
		summary.setGraphicCount(graphicCount);
		summary.setTotalCount(videoCount + graphicCount);
		return new AjaxEntity(Global.ajax_success, "数据获取成功", summary);
	}

	public AjaxEntity findProfileWorks(String platform, String authoruid, String authorusername, String author,
			String type, Integer pageNo, Integer pageSize) {
		int actualPageNo = pageNo == null ? 0 : Math.max(0, pageNo.intValue());
		int actualPageSize = pageSize == null ? 24 : Math.max(1, Math.min(100, pageSize.intValue()));
		int fetchSize = Math.max(actualPageSize, (actualPageNo + 1) * actualPageSize);
		List<AdminMediaFeedItem> items = new ArrayList<>();
		long totalElements = 0;
		if (!"graphic".equalsIgnoreCase(type)) {
			Page<VideoDataEntity> videos = videoDataDao.findAll(buildVideoAuthorSpec(platform, authoruid, authorusername, author),
					PageRequest.of(0, fetchSize, mediaSort()));
			totalElements += videos.getTotalElements();
			for (VideoDataEntity video : videos.getContent()) {
				items.add(toVideoFeedItem(video));
			}
		}
		if (!"video".equalsIgnoreCase(type)) {
			Page<GraphicContentEntity> graphics = graphicContentDao.findAll(buildGraphicAuthorSpec(platform, authoruid, authorusername, author),
					PageRequest.of(0, fetchSize, mediaSort()));
			totalElements += graphics.getTotalElements();
			for (GraphicContentEntity graphic : graphics.getContent()) {
				AdminMediaFeedItem item = toGraphicFeedItem(graphic);
				if (!item.getSlides().isEmpty()) {
					items.add(item);
				}
			}
		}
		items.sort(feedComparator());
		int from = Math.min(actualPageNo * actualPageSize, items.size());
		int to = Math.min(from + actualPageSize, items.size());
		List<AdminMediaFeedItem> pageItems = from >= to ? List.of() : new ArrayList<>(items.subList(from, to));
		Page<AdminMediaFeedItem> page = new PageImpl<>(pageItems, PageRequest.of(actualPageNo, actualPageSize), totalElements);
		return new AjaxEntity(Global.ajax_success, "数据获取成功", page);
	}

	public long countNameHistory(Integer authorProfileId) {
		return authorNameHistoryDao.countByAuthorprofileid(authorProfileId);
	}

	private AdminMediaFeedItem toVideoFeedItem(VideoDataEntity video) {
		AdminMediaFeedItem item = new AdminMediaFeedItem();
		if (video == null) {
			return item;
		}
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
		enrichDisplayAuthor(item);
		return item;
	}

	private AdminMediaFeedItem toGraphicFeedItem(GraphicContentEntity graphic) {
		AdminMediaFeedItem item = new AdminMediaFeedItem();
		if (graphic == null) {
			return item;
		}
		List<AdminMediaSlide> slides = parseGraphicSlides(graphic.getImages());
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
		enrichDisplayAuthor(item);
		return item;
	}

	private List<AdminMediaSlide> parseGraphicSlides(String rawImages) {
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
			return List.of();
		}
		return slides;
	}

	private void enrichDisplayAuthor(AdminMediaFeedItem item) {
		if (item == null || trimToNull(item.getPlatform()) == null || trimToNull(item.getAuthoruid()) == null) {
			return;
		}
		authorProfileDao.findByPlatformAndAuthoruid(item.getPlatform().trim(), item.getAuthoruid().trim())
				.map(AuthorProfileEntity::getDisplayname)
				.filter(name -> name != null && !name.trim().isEmpty())
				.ifPresent(name -> {
					item.setDisplayAuthor(name.trim());
					item.setProfileAuthorUid(item.getAuthoruid());
				});
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

	private AuthorProfileEntity findBestProfile(String platform, String authoruid, String authorusername, String author) {
		String safePlatform = trimToNull(platform);
		String safeUid = trimToNull(authoruid);
		if (safePlatform != null && safeUid != null) {
			Optional<AuthorProfileEntity> byUid = authorProfileDao.findByPlatformAndAuthoruid(safePlatform, safeUid);
			if (byUid.isPresent()) {
				return byUid.get();
			}
		}
		List<AuthorProfileEntity> candidates = authorProfileDao.findAll((root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (safePlatform != null) {
				predicates.add(cb.equal(root.get("platform"), safePlatform));
			}
			List<Predicate> identity = new ArrayList<>();
			String safeUsername = trimToNull(authorusername);
			String safeAuthor = trimToNull(author);
			if (safeUid != null) {
				identity.add(cb.equal(root.get("authoruid"), safeUid));
			} else if (safeUsername != null) {
				identity.add(cb.equal(root.get("username"), safeUsername));
			} else if (safeAuthor != null) {
				identity.add(cb.equal(root.get("displayname"), safeAuthor));
			}
			if (!identity.isEmpty()) {
				predicates.add(cb.or(identity.toArray(new Predicate[0])));
			}
			query.orderBy(cb.desc(root.get("updatetime")), cb.desc(root.get("id")));
			return cb.and(predicates.toArray(new Predicate[0]));
		}, PageRequest.of(0, 1)).getContent();
		return candidates.isEmpty() ? null : candidates.get(0);
	}

	private Specification<VideoDataEntity> buildVideoAuthorSpec(String platform, String authoruid, String authorusername, String author) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (trimToNull(platform) != null) {
				predicates.add(cb.equal(root.get("videoplatform"), platform.trim()));
			}
			List<Predicate> identity = new ArrayList<>();
			String safeUid = trimToNull(authoruid);
			String safeUsername = trimToNull(authorusername);
			String safeAuthor = trimToNull(author);
			if (safeUid != null) {
				identity.add(cb.equal(root.get("authoruid"), safeUid));
				identity.add(cb.equal(root.get("secuid"), safeUid));
			} else if (safeUsername != null) {
				identity.add(cb.equal(root.get("authorusername"), safeUsername));
				identity.add(cb.equal(root.get("uniqueid"), safeUsername));
			} else if (safeAuthor != null) {
				identity.add(cb.equal(root.get("videoauthor"), safeAuthor));
			}
			if (!identity.isEmpty()) {
				predicates.add(cb.or(identity.toArray(new Predicate[0])));
			}
			return cb.and(predicates.toArray(new Predicate[0]));
		};
	}

	private Specification<GraphicContentEntity> buildGraphicAuthorSpec(String platform, String authoruid, String authorusername, String author) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (trimToNull(platform) != null) {
				predicates.add(cb.equal(root.get("platform"), platform.trim()));
			}
			List<Predicate> identity = new ArrayList<>();
			String safeUid = trimToNull(authoruid);
			String safeUsername = trimToNull(authorusername);
			String safeAuthor = trimToNull(author);
			if (safeUid != null) {
				identity.add(cb.equal(root.get("authoruid"), safeUid));
				identity.add(cb.equal(root.get("secuid"), safeUid));
			} else if (safeUsername != null) {
				identity.add(cb.equal(root.get("authorusername"), safeUsername));
				identity.add(cb.equal(root.get("uniqueid"), safeUsername));
			} else if (safeAuthor != null) {
				identity.add(cb.equal(root.get("author"), safeAuthor));
			}
			if (!identity.isEmpty()) {
				predicates.add(cb.or(identity.toArray(new Predicate[0])));
			}
			return cb.and(predicates.toArray(new Predicate[0]));
		};
	}

	private Sort mediaSort() {
		return Sort.by(Sort.Direction.DESC, "publishtime")
				.and(Sort.by(Sort.Direction.DESC, "createtime"))
				.and(Sort.by(Sort.Direction.DESC, "id"));
	}

	private Comparator<AdminMediaFeedItem> feedComparator() {
		return (left, right) -> {
			int publishCompare = compareNullable(left.getPublishTime(), right.getPublishTime());
			if (publishCompare != 0) {
				return publishCompare;
			}
			int createCompare = compareNullable(left.getCreateTime(), right.getCreateTime());
			if (createCompare != 0) {
				return createCompare;
			}
			return compareNullable(left.getId(), right.getId());
		};
	}

	private <T extends Comparable<T>> int compareNullable(T left, T right) {
		if (left == null && right == null) {
			return 0;
		}
		if (left == null) {
			return 1;
		}
		if (right == null) {
			return -1;
		}
		return -left.compareTo(right);
	}

	public AjaxEntity rebuildDouyinAuthors() {
		List<AuthorProfileEntity> oldAuthors = authorProfileDao.findByPlatform("抖音");
		for (AuthorProfileEntity old : oldAuthors) {
			if (old.getId() != null) {
				authorNameHistoryDao.deleteByAuthorprofileid(old.getId());
			}
		}
		authorProfileDao.deleteAll(oldAuthors);

		int scannedVideos = 0;
		int updatedVideos = 0;
		int hybridApiSuccess = 0;
		int profileApiSuccess = 0;
		int apiFailed = 0;
		int fallbackUsed = 0;
		int skippedNoAuthorUid = 0;
		for (VideoDataEntity video : videoDataDao.findAll()) {
			if (video == null || !"抖音".equals(video.getVideoplatform())) {
				continue;
			}
			scannedVideos++;
			String sourceUrl = null;
			if (video.getVideoid() != null && !video.getVideoid().trim().isEmpty()) {
				sourceUrl = DouyinSourceUrlUtil.video(video.getVideoid());
				video.setSourceurl(sourceUrl);
			}
			JSONObject hybrid = DouUtil.fetchHybridVideoData(firstNotBlank(video.getOriginaladdress(), sourceUrl));
			JSONObject hybridAuthor = null;
			if (hybrid != null) {
				hybridApiSuccess++;
				video.setJsonData(hybrid.toJSONString());
				video.setVideoinfo(hybrid.toJSONString());
				hybridAuthor = findHybridAuthor(hybrid);
				applyVideoAuthorFromAuthor(video, hybridAuthor);
			}
			JSONObject profileAuthor = resolveProfileAuthor(firstNotBlank(video.getSecuid(), video.getAuthoruid()), firstNotBlank(video.getUniqueid(), video.getAuthorusername()));
			if (profileAuthor != null) {
				profileApiSuccess++;
				applyVideoAuthorFromAuthor(video, profileAuthor);
			} else if (hybridAuthor == null) {
				apiFailed++;
			}
			String authorUid = preferDouyinAuthorUid(video.getSecuid(), video.getAuthoruid());
			String username = firstNotBlank(video.getUniqueid(), video.getAuthorusername());
			if (authorUid != null && !authorUid.trim().isEmpty()) {
				video.setAuthoruid(authorUid);
				video.setSecuid(authorUid);
				video.setAuthorusername(username);
				video.setUniqueid(username);
				upsertAuthor("抖音", authorUid, username, video.getVideoauthor(), video.getAuthoravatar(),
						"https://www.douyin.com/user/" + authorUid, authorSignature(profileAuthor, hybridAuthor));
				if (profileAuthor == null && hybridAuthor == null) {
					fallbackUsed++;
				}
			} else {
				skippedNoAuthorUid++;
			}
			videoDataDao.save(video);
			updatedVideos++;
		}

		int scannedGraphics = 0;
		int updatedGraphics = 0;
		for (GraphicContentEntity item : graphicContentDao.findAll()) {
			if (item == null || !isDouyinPlatform(item.getPlatform())) {
				continue;
			}
			scannedGraphics++;
			String sourceUrl = null;
			if (item.getVideoid() != null && !item.getVideoid().trim().isEmpty()) {
				sourceUrl = firstNotBlank(item.getSourceurl(), DouyinSourceUrlUtil.note(item.getVideoid()));
			}
			JSONObject hybrid = DouUtil.fetchHybridVideoData(firstNotBlank(item.getOriginaladdress(), sourceUrl));
			JSONObject hybridAuthor = null;
			if (hybrid != null) {
				hybridApiSuccess++;
				item.setJsonData(hybrid.toJSONString());
				hybridAuthor = findHybridAuthor(hybrid);
				applyGraphicAuthorFromAuthor(item, hybridAuthor);
			}
			JSONObject profileAuthor = resolveProfileAuthor(firstNotBlank(item.getSecuid(), item.getAuthoruid()), firstNotBlank(item.getUniqueid(), item.getAuthorusername()));
			if (profileAuthor != null) {
				profileApiSuccess++;
				applyGraphicAuthorFromAuthor(item, profileAuthor);
			} else if (hybridAuthor == null) {
				apiFailed++;
			}
			String authorUid = preferDouyinAuthorUid(item.getSecuid(), item.getAuthoruid());
			String username = firstNotBlank(item.getUniqueid(), item.getAuthorusername());
			if (authorUid != null && !authorUid.trim().isEmpty()) {
				item.setAuthoruid(authorUid);
				item.setSecuid(authorUid);
				item.setAuthorusername(username);
				item.setUniqueid(username);
				String graphicSourceUrl = DouyinSourceUrlUtil.graphic(authorUid, item.getVideoid());
				if (graphicSourceUrl != null) {
					item.setSourceurl(graphicSourceUrl);
				}
				upsertAuthor("抖音", authorUid, username, item.getAuthor(), item.getAuthoravatar(),
						"https://www.douyin.com/user/" + authorUid, authorSignature(profileAuthor, hybridAuthor));
				if (profileAuthor == null && hybridAuthor == null) {
					fallbackUsed++;
				}
			} else {
				skippedNoAuthorUid++;
			}
			graphicContentDao.save(item);
			updatedGraphics++;
		}

		java.util.Map<String, Object> result = new java.util.HashMap<>();
		result.put("deletedAuthors", oldAuthors.size());
		result.put("scannedVideos", scannedVideos);
		result.put("updatedVideos", updatedVideos);
		result.put("scannedGraphics", scannedGraphics);
		result.put("updatedGraphics", updatedGraphics);
		result.put("hybridApiSuccess", hybridApiSuccess);
		result.put("profileApiSuccess", profileApiSuccess);
		result.put("apiFailed", apiFailed);
		result.put("fallbackUsed", fallbackUsed);
		result.put("skippedNoAuthorUid", skippedNoAuthorUid);
		result.put("rebuiltAuthors", authorProfileDao.findByPlatform("抖音").size());
		return new AjaxEntity(Global.ajax_success, "重建完成", result);
	}

	private void applyVideoAuthorFromAuthor(VideoDataEntity video, JSONObject author) {
		if (author == null) {
			return;
		}
		String secUid = author.getString("sec_uid");
		String uniqueId = author.getString("unique_id");
		String nickname = author.getString("nickname");
		String avatar = firstNotBlank(DouUtil.extractAvatar(author), author.getString("avatar_thumb"));
		video.setAuthoruid(preferDouyinAuthorUid(secUid, video.getAuthoruid()));
		video.setSecuid(preferDouyinAuthorUid(secUid, video.getSecuid()));
		video.setAuthorusername(firstNotBlank(uniqueId, video.getAuthorusername()));
		video.setUniqueid(firstNotBlank(uniqueId, video.getUniqueid()));
		video.setVideoauthor(firstNotBlank(nickname, video.getVideoauthor()));
		video.setAuthoravatar(firstNotBlank(avatar, video.getAuthoravatar()));
	}

	private void applyGraphicAuthorFromAuthor(GraphicContentEntity item, JSONObject author) {
		if (author == null) {
			return;
		}
		String secUid = author.getString("sec_uid");
		String uniqueId = author.getString("unique_id");
		String nickname = author.getString("nickname");
		String avatar = firstNotBlank(DouUtil.extractAvatar(author), author.getString("avatar_thumb"));
		item.setAuthoruid(preferDouyinAuthorUid(secUid, item.getAuthoruid()));
		item.setSecuid(preferDouyinAuthorUid(secUid, item.getSecuid()));
		item.setAuthorusername(firstNotBlank(uniqueId, item.getAuthorusername()));
		item.setUniqueid(firstNotBlank(uniqueId, item.getUniqueid()));
		item.setAuthor(firstNotBlank(nickname, item.getAuthor()));
		item.setAuthoravatar(firstNotBlank(avatar, item.getAuthoravatar()));
	}

	private JSONObject findHybridAuthor(JSONObject hybrid) {
		JSONObject detail = DouUtil.findAwemeDetail(hybrid);
		return detail == null ? null : detail.getJSONObject("author");
	}

	private JSONObject resolveProfileAuthor(String secUid, String uniqueId) {
		JSONObject profile = extractProfileUser(DouUtil.fetchUserProfile(secUid));
		if (profile == null) {
			profile = extractProfileUser(DouUtil.fetchUserProfileByUniqueId(uniqueId));
		}
		return profile;
	}

	private String authorSignature(JSONObject profileAuthor, JSONObject hybridAuthor) {
		String profileSignature = profileAuthor == null ? null : profileAuthor.getString("signature");
		String hybridSignature = hybridAuthor == null ? null : hybridAuthor.getString("signature");
		return firstNotBlank(profileSignature, hybridSignature);
	}

	private JSONObject extractProfileUser(JSONObject profile) {
		if (profile == null) return null;
		JSONObject user = profile.getJSONObject("user");
		if (isProfileUser(user)) return user;
		JSONObject data = profile.getJSONObject("data");
		if (data != null) {
			JSONObject dataUser = data.getJSONObject("user");
			if (isProfileUser(dataUser)) return dataUser;
			if (isProfileUser(data)) return data;
		}
		return isProfileUser(profile) ? profile : null;
	}

	private boolean isProfileUser(JSONObject object) {
		if (object == null) return false;
		return hasText(object.getString("sec_uid")) || hasText(object.getString("unique_id"))
				|| hasText(object.getString("nickname"));
	}

	private boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}

	private boolean isDouyinPlatform(String platform) {
		return isDouyinPlatformValue(platform);
	}

	public static String preferDouyinAuthorUid(String secUid, String fallback) {
		String safeSecUid = trimToNull(secUid);
		if (isDouyinSecUid(safeSecUid)) {
			return safeSecUid;
		}
		String safeFallback = trimToNull(fallback);
		return isDouyinSecUid(safeFallback) ? safeFallback : null;
	}

	public static boolean isDouyinPlatformValue(String platform) {
		return "抖音".equals(platform) || "douyin".equalsIgnoreCase(platform);
	}

	private static boolean isDouyinSecUid(String value) {
		return value != null && value.startsWith("MS4");
	}

	private static String trimToNull(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		return value.trim();
	}

	private String firstNotBlank(String first, String second) {
		if (first != null && !first.trim().isEmpty()) {
			return first.trim();
		}
		return second == null ? null : second.trim();
	}
}
