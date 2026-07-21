package com.flower.spirit.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.PlatformDefinition;
import com.flower.spirit.utils.AuthorIdentityUtil;
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

	@Transactional
	public synchronized void upsertAuthor(String platform, String authoruid, String username, String displayName,
			String avatar, String homepage) {
		upsertAuthor(platform, authoruid, username, displayName, avatar, homepage, null);
	}

	@Transactional
	public synchronized void upsertAuthor(String platform, String authoruid, String username, String displayName, String avatar,
			String homepage, String signature) {
		if (platform == null || platform.trim().isEmpty() || authoruid == null || authoruid.trim().isEmpty()) {
			return;
		}
		String safePlatform = platform.trim();
		String safeUid = AuthorIdentityUtil.canonicalAuthorUid(safePlatform, authoruid, authoruid);
		if (safeUid == null) {
			return;
		}
		Date now = new Date();
		Optional<AuthorProfileEntity> opt = authorProfileDao.findByPlatformAndAuthoruid(safePlatform, safeUid);
		AuthorProfileEntity entity = opt.orElseGet(AuthorProfileEntity::new);
		if (entity.getId() == null) {
			entity.setCreatetime(now);
		}
		entity.setPlatform(safePlatform);
		entity.setAuthoruid(safeUid);
		String safeUsername = AuthorIdentityUtil.canonicalUsername(username, null);
		if (safeUsername != null) {
			entity.setUsername(safeUsername);
		}
		if (displayName != null && !displayName.trim().isEmpty()) {
			entity.setDisplayname(displayName.trim());
		}
		if (avatar != null && !avatar.trim().isEmpty()) {
			entity.setAvatar(avatar.trim());
		}
		String safeHomepage = AuthorIdentityUtil.sanitizeHomepage(safePlatform, safeUid, homepage);
		if (safeHomepage != null) {
			entity.setHomepage(safeHomepage);
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

	@Transactional
	public synchronized void upsertCanonicalAuthor(String platformKey, String legacyPlatform, String authoruid, String username,
			String displayName, String avatar, String homepage) {
		upsertCanonicalAuthor(platformKey, legacyPlatform, authoruid, username, displayName, avatar, homepage, null);
	}

	@Transactional
	public synchronized void upsertCanonicalAuthor(String platformKey, String legacyPlatform, String authoruid, String username,
			String displayName, String avatar, String homepage, String signature) {
		if (platformKey == null || platformKey.trim().isEmpty() || authoruid == null || authoruid.trim().isEmpty()) {
			return;
		}
		Optional<PlatformDefinition> definition = PlatformCatalog.findByAlias(platformKey);
		if (definition.isEmpty()) {
			definition = PlatformCatalog.findByAlias(legacyPlatform);
		}
		String canonicalKey = definition.map(PlatformDefinition::getKey)
				.orElseGet(() -> platformKey.trim().toLowerCase(Locale.ROOT));
		String safePlatform = definition.map(PlatformDefinition::getDisplayName)
				.orElseGet(() -> legacyPlatform == null || legacyPlatform.trim().isEmpty()
						? platformKey.trim() : legacyPlatform.trim());
		String safeUid = AuthorIdentityUtil.canonicalAuthorUid(safePlatform, authoruid, authoruid);
		if (safeUid == null) {
			return;
		}
		Optional<AuthorProfileEntity> existing = authorProfileDao.findByPlatformkeyAndAuthoruid(canonicalKey, safeUid);
		if (existing.isEmpty() && definition.isPresent()) {
			for (String alias : definition.get().getAliases()) {
				existing = authorProfileDao.findByPlatformAndAuthoruid(alias, safeUid);
				if (existing.isPresent()) {
					break;
				}
			}
		}
		AuthorProfileEntity entity = existing.orElseGet(AuthorProfileEntity::new);
		Date now = new Date();
		if (entity.getId() == null) {
			entity.setCreatetime(now);
		}
		entity.setPlatform(safePlatform);
		entity.setPlatformkey(canonicalKey);
		entity.setAuthoruid(safeUid);
		String safeUsername = AuthorIdentityUtil.canonicalUsername(username, null);
		if (safeUsername != null) {
			entity.setUsername(safeUsername);
		}
		if (displayName != null && !displayName.trim().isEmpty()) {
			entity.setDisplayname(displayName.trim());
		}
		if (avatar != null && !avatar.trim().isEmpty()) {
			entity.setAvatar(avatar.trim());
		}
		String safeHomepage = AuthorIdentityUtil.sanitizeHomepage(safePlatform, safeUid, homepage);
		if (safeHomepage != null) {
			entity.setHomepage(safeHomepage);
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
		Page<AdminAuthorProfileSummary> sanitizedPage = page.map(this::toSanitizedProfileSummary);
		return new AjaxEntity(Global.ajax_success, "数据获取成功", sanitizedPage);
	}

	private AdminAuthorProfileSummary toSanitizedProfileSummary(AuthorProfileEntity profile) {
		AdminAuthorProfileSummary summary = new AdminAuthorProfileSummary();
		if (profile == null) {
			return summary;
		}
		String canonicalUid = AuthorIdentityUtil.canonicalAuthorUid(profile.getPlatform(), profile.getAuthoruid(), profile.getAuthoruid());
		summary.setId(profile.getId());
		summary.setPlatform(profile.getPlatform());
		summary.setAuthoruid(canonicalUid);
		summary.setUsername(AuthorIdentityUtil.canonicalUsername(profile.getUsername(), null));
		summary.setDisplayname(profile.getDisplayname());
		summary.setAvatar(profile.getAvatar());
		summary.setHomepage(AuthorIdentityUtil.sanitizeHomepage(profile.getPlatform(), canonicalUid, profile.getHomepage()));
		summary.setSignature(profile.getSignature());
		summary.setUpdatetime(profile.getUpdatetime());
		return summary;
	}

	public List<AuthorNameHistoryEntity> findNameHistory(Integer authorProfileId) {
		return authorNameHistoryDao.findByAuthorProfileIdOrderByLastSeen(authorProfileId);
	}

	public AjaxEntity findProfileSummary(String platform, String authoruid, String authorusername, String author) {
		String safePlatform = trimToNull(platform);
		String safeUid = AuthorIdentityUtil.canonicalAuthorUid(safePlatform, authoruid, authoruid);
		String safeUsername = AuthorIdentityUtil.canonicalUsername(authorusername, null);
		AuthorProfileEntity profile = findBestProfile(safePlatform, safeUid, safeUsername, author);
		List<String> nameAliases = authorNameAliases(profile, author);
		Specification<VideoDataEntity> videoSpec = buildVideoAuthorSpec(safePlatform, safeUid, safeUsername, author, nameAliases);
		Specification<GraphicContentEntity> graphicSpec = buildGraphicAuthorSpec(safePlatform, safeUid, safeUsername, author, nameAliases);
		long videoCount = videoDataDao.count(videoSpec);
		long graphicCount = graphicContentDao.count(graphicSpec);
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
		summary.setPlatform(firstNotBlank(summary.getPlatform(), safePlatform));
		String summaryUid = AuthorIdentityUtil.canonicalAuthorUid(summary.getPlatform(), summary.getAuthoruid(), safeUid);
		summary.setAuthoruid(summaryUid);
		summary.setUsername(AuthorIdentityUtil.canonicalUsername(summary.getUsername(), safeUsername));
		summary.setDisplayname(firstNotBlank(summary.getDisplayname(), author));
		summary.setHomepage(AuthorIdentityUtil.sanitizeHomepage(summary.getPlatform(), summaryUid, summary.getHomepage()));
		summary.setVideoCount(videoCount);
		summary.setGraphicCount(graphicCount);
		summary.setTotalCount(videoCount + graphicCount);
		return new AjaxEntity(Global.ajax_success, "数据获取成功", summary);
	}

	public AjaxEntity findProfileWorks(String platform, String authoruid, String authorusername, String author,
			String type, Integer pageNo, Integer pageSize) {
		String safePlatform = trimToNull(platform);
		String safeUid = AuthorIdentityUtil.canonicalAuthorUid(safePlatform, authoruid, authoruid);
		String safeUsername = AuthorIdentityUtil.canonicalUsername(authorusername, null);
		int actualPageNo = pageNo == null ? 0 : Math.max(0, pageNo.intValue());
		int actualPageSize = pageSize == null ? 24 : Math.max(1, Math.min(100, pageSize.intValue()));
		int fetchSize = Math.max(actualPageSize, (actualPageNo + 1) * actualPageSize);
		List<AdminMediaFeedItem> items = new ArrayList<>();
		long totalElements = 0;
		AuthorProfileEntity profile = findBestProfile(safePlatform, safeUid, safeUsername, author);
		List<String> nameAliases = authorNameAliases(profile, author);
		if (!"graphic".equalsIgnoreCase(type)) {
			Page<VideoDataEntity> videos = videoDataDao.findAll(buildVideoAuthorSpec(safePlatform, safeUid, safeUsername, author, nameAliases),
					PageRequest.of(0, fetchSize, mediaSort()));
			totalElements += videos.getTotalElements();
			for (VideoDataEntity video : videos.getContent()) {
				items.add(toVideoFeedItem(video));
			}
		}
		if (!"video".equalsIgnoreCase(type)) {
			Page<GraphicContentEntity> graphics = graphicContentDao.findAll(buildGraphicAuthorSpec(safePlatform, safeUid, safeUsername, author, nameAliases),
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
		item.setSecuid(video.getSecuid());
		item.setAuthorusername(video.getAuthorusername());
		item.setUniqueid(video.getUniqueid());
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
		item.setSecuid(graphic.getSecuid());
		item.setAuthorusername(graphic.getAuthorusername());
		item.setUniqueid(graphic.getUniqueid());
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
		if (item == null) {
			return;
		}
		String canonicalUid = AuthorIdentityUtil.canonicalAuthorUid(item.getPlatform(), item.getAuthoruid(), item.getSecuid());
		String canonicalUsername = AuthorIdentityUtil.canonicalUsername(item.getAuthorusername(), item.getUniqueid());
		item.setAuthoruid(canonicalUid);
		item.setSecuid(canonicalUid);
		item.setAuthorusername(canonicalUsername);
		item.setUniqueid(canonicalUsername);
		if (trimToNull(item.getPlatform()) == null || canonicalUid == null) {
			return;
		}
		authorProfileDao.findByPlatformAndAuthoruid(item.getPlatform().trim(), canonicalUid)
				.map(AuthorProfileEntity::getDisplayname)
				.filter(name -> name != null && !name.trim().isEmpty())
				.ifPresent(name -> {
					item.setDisplayAuthor(name.trim());
					item.setProfileAuthorUid(canonicalUid);
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
		String safeUid = AuthorIdentityUtil.canonicalAuthorUid(safePlatform, authoruid, authoruid);
		String safeUsername = AuthorIdentityUtil.canonicalUsername(authorusername, null);
		String safeAuthor = trimToNull(author);
		if (AuthorIdentityUtil.isDouyinPlatform(safePlatform) && safeUid == null) {
			return null;
		}
		if (safeUid == null && safeUsername == null && safeAuthor == null) {
			return null;
		}
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

	private List<String> authorNameAliases(AuthorProfileEntity profile, String requestedAuthor) {
		Set<String> aliases = new LinkedHashSet<>();
		addAlias(aliases, requestedAuthor);
		if (profile != null) {
			addAlias(aliases, profile.getDisplayname());
			if (profile.getId() != null) {
				for (AuthorNameHistoryEntity history : authorNameHistoryDao.findByAuthorProfileIdOrderByLastSeen(profile.getId())) {
					addAlias(aliases, history == null ? null : history.getDisplayname());
				}
			}
		}
		return new ArrayList<>(aliases);
	}

	private List<String> normalizeAliases(List<String> values) {
		Set<String> aliases = new LinkedHashSet<>();
		if (values != null) {
			for (String value : values) {
				addAlias(aliases, value);
			}
		}
		return new ArrayList<>(aliases);
	}

	private void addAlias(Set<String> aliases, String value) {
		String safeValue = trimToNull(value);
		if (safeValue != null) {
			aliases.add(safeValue);
		}
	}

	private Specification<VideoDataEntity> buildVideoAuthorSpec(String platform, String authoruid, String authorusername, String author,
			List<String> nameAliases) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (trimToNull(platform) != null) {
				predicates.add(cb.equal(root.get("videoplatform"), platform.trim()));
			}
			List<Predicate> identity = new ArrayList<>();
			String safeUid = AuthorIdentityUtil.canonicalAuthorUid(platform, authoruid, authoruid);
			String safeUsername = AuthorIdentityUtil.canonicalUsername(authorusername, null);
			String safeAuthor = trimToNull(author);
			boolean douyin = AuthorIdentityUtil.isDouyinPlatform(platform);
			if (safeUid != null) {
				identity.add(cb.equal(root.get("authoruid"), safeUid));
				identity.add(cb.equal(root.get("secuid"), safeUid));
				Predicate blankUid = cb.and(
						cb.or(cb.isNull(root.get("authoruid")), cb.equal(root.get("authoruid"), "")),
						cb.or(cb.isNull(root.get("secuid")), cb.equal(root.get("secuid"), "")));
				if (!douyin && safeUsername != null) {
					identity.add(cb.and(blankUid, cb.or(
							cb.equal(root.get("authorusername"), safeUsername),
							cb.equal(root.get("uniqueid"), safeUsername))));
				}
				List<String> aliases = normalizeAliases(nameAliases);
				if (!douyin && !aliases.isEmpty()) {
					identity.add(cb.and(blankUid, root.get("videoauthor").in(aliases)));
				}
			} else if (!douyin && safeUsername != null) {
				identity.add(cb.equal(root.get("authorusername"), safeUsername));
				identity.add(cb.equal(root.get("uniqueid"), safeUsername));
			} else if (!douyin && safeAuthor != null) {
				identity.add(cb.equal(root.get("videoauthor"), safeAuthor));
			}
			predicates.add(identity.isEmpty() ? cb.disjunction() : cb.or(identity.toArray(new Predicate[0])));
			return cb.and(predicates.toArray(new Predicate[0]));
		};
	}

	private Specification<GraphicContentEntity> buildGraphicAuthorSpec(String platform, String authoruid, String authorusername, String author,
			List<String> nameAliases) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (trimToNull(platform) != null) {
				predicates.add(cb.equal(root.get("platform"), platform.trim()));
			}
			List<Predicate> identity = new ArrayList<>();
			String safeUid = AuthorIdentityUtil.canonicalAuthorUid(platform, authoruid, authoruid);
			String safeUsername = AuthorIdentityUtil.canonicalUsername(authorusername, null);
			String safeAuthor = trimToNull(author);
			boolean douyin = AuthorIdentityUtil.isDouyinPlatform(platform);
			if (safeUid != null) {
				identity.add(cb.equal(root.get("authoruid"), safeUid));
				identity.add(cb.equal(root.get("secuid"), safeUid));
				Predicate blankUid = cb.and(
						cb.or(cb.isNull(root.get("authoruid")), cb.equal(root.get("authoruid"), "")),
						cb.or(cb.isNull(root.get("secuid")), cb.equal(root.get("secuid"), "")));
				if (!douyin && safeUsername != null) {
					identity.add(cb.and(blankUid, cb.or(
							cb.equal(root.get("authorusername"), safeUsername),
							cb.equal(root.get("uniqueid"), safeUsername))));
				}
				List<String> aliases = normalizeAliases(nameAliases);
				if (!douyin && !aliases.isEmpty()) {
					identity.add(cb.and(blankUid, root.get("author").in(aliases)));
				}
			} else if (!douyin && safeUsername != null) {
				identity.add(cb.equal(root.get("authorusername"), safeUsername));
				identity.add(cb.equal(root.get("uniqueid"), safeUsername));
			} else if (!douyin && safeAuthor != null) {
				identity.add(cb.equal(root.get("author"), safeAuthor));
			}
			predicates.add(identity.isEmpty() ? cb.disjunction() : cb.or(identity.toArray(new Predicate[0])));
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
		int scannedVideos = 0;
		int repairedVideos = 0;
		int scannedGraphics = 0;
		int repairedGraphics = 0;
		int localResolved = 0;
		int hybridApiSuccess = 0;
		int profileApiSuccess = 0;
		int apiFailed = 0;
		int unresolved = 0;
		int mergedProfiles = 0;
		Map<String, JSONObject> profileAuthorCache = new HashMap<>();

		for (VideoDataEntity video : videoDataDao.findAll()) {
			if (video == null || !isDouyinPlatform(video.getVideoplatform())) {
				continue;
			}
			scannedVideos++;
			String legacyUid = trimToNull(video.getAuthoruid());
			JSONObject resolvedAuthor = findStoredAuthor(video.getJsonData());
			if (hasCanonicalDouyinUid(resolvedAuthor)) {
				localResolved++;
			}
			applyVideoAuthorFromAuthor(video, resolvedAuthor);

			String canonicalUid = AuthorIdentityUtil.canonicalAuthorUid(video.getVideoplatform(), video.getAuthoruid(), video.getSecuid());
			String username = AuthorIdentityUtil.canonicalUsername(video.getAuthorusername(), video.getUniqueid());
			JSONObject profileAuthor = resolveProfileAuthorCached(profileAuthorCache, canonicalUid);
			if (profileAuthor != null) {
				profileApiSuccess++;
				applyVideoAuthorFromAuthor(video, profileAuthor);
				canonicalUid = AuthorIdentityUtil.canonicalAuthorUid(video.getVideoplatform(), video.getAuthoruid(), video.getSecuid());
				username = AuthorIdentityUtil.canonicalUsername(video.getAuthorusername(), video.getUniqueid());
			}

			JSONObject hybridAuthor = resolvedAuthor;
			if (canonicalUid == null) {
				String sourceUrl = firstNotBlank(video.getOriginaladdress(), DouyinSourceUrlUtil.video(video.getVideoid()));
				JSONObject hybrid = DouUtil.fetchHybridVideoData(sourceUrl);
				if (hybrid != null) {
					hybridApiSuccess++;
					video.setJsonData(hybrid.toJSONString());
					hybridAuthor = findHybridAuthor(hybrid);
					applyVideoAuthorFromAuthor(video, hybridAuthor);
				} else {
					apiFailed++;
				}
				canonicalUid = AuthorIdentityUtil.canonicalAuthorUid(video.getVideoplatform(), video.getAuthoruid(), video.getSecuid());
				username = AuthorIdentityUtil.canonicalUsername(video.getAuthorusername(), video.getUniqueid());
			}

			if (canonicalUid == null) {
				unresolved++;
				continue;
			}
			video.setAuthoruid(canonicalUid);
			video.setSecuid(canonicalUid);
			video.setAuthorusername(username);
			video.setUniqueid(username);
			video.setSourceurl(DouyinSourceUrlUtil.video(video.getVideoid()));
			upsertAuthor("抖音", canonicalUid, username, video.getVideoauthor(), video.getAuthoravatar(),
					AuthorIdentityUtil.douyinHomepage(canonicalUid), authorSignature(profileAuthor, hybridAuthor));
			videoDataDao.save(video);
			repairedVideos++;
			if (mergeLegacyProfile("抖音", legacyUid, canonicalUid)) {
				mergedProfiles++;
			}
		}

		for (GraphicContentEntity item : graphicContentDao.findAll()) {
			if (item == null || !isDouyinPlatform(item.getPlatform())) {
				continue;
			}
			scannedGraphics++;
			String legacyUid = trimToNull(item.getAuthoruid());
			JSONObject resolvedAuthor = findStoredAuthor(item.getJsonData());
			if (hasCanonicalDouyinUid(resolvedAuthor)) {
				localResolved++;
			}
			applyGraphicAuthorFromAuthor(item, resolvedAuthor);

			String canonicalUid = AuthorIdentityUtil.canonicalAuthorUid(item.getPlatform(), item.getAuthoruid(), item.getSecuid());
			String username = AuthorIdentityUtil.canonicalUsername(item.getAuthorusername(), item.getUniqueid());
			JSONObject profileAuthor = resolveProfileAuthorCached(profileAuthorCache, canonicalUid);
			if (profileAuthor != null) {
				profileApiSuccess++;
				applyGraphicAuthorFromAuthor(item, profileAuthor);
				canonicalUid = AuthorIdentityUtil.canonicalAuthorUid(item.getPlatform(), item.getAuthoruid(), item.getSecuid());
				username = AuthorIdentityUtil.canonicalUsername(item.getAuthorusername(), item.getUniqueid());
			}

			JSONObject hybridAuthor = resolvedAuthor;
			if (canonicalUid == null) {
				String sourceUrl = firstNotBlank(item.getOriginaladdress(), DouyinSourceUrlUtil.note(item.getVideoid()));
				JSONObject hybrid = DouUtil.fetchHybridVideoData(sourceUrl);
				if (hybrid != null) {
					hybridApiSuccess++;
					item.setJsonData(hybrid.toJSONString());
					hybridAuthor = findHybridAuthor(hybrid);
					applyGraphicAuthorFromAuthor(item, hybridAuthor);
				} else {
					apiFailed++;
				}
				canonicalUid = AuthorIdentityUtil.canonicalAuthorUid(item.getPlatform(), item.getAuthoruid(), item.getSecuid());
				username = AuthorIdentityUtil.canonicalUsername(item.getAuthorusername(), item.getUniqueid());
			}

			if (canonicalUid == null) {
				unresolved++;
				continue;
			}
			item.setAuthoruid(canonicalUid);
			item.setSecuid(canonicalUid);
			item.setAuthorusername(username);
			item.setUniqueid(username);
			String graphicSourceUrl = DouyinSourceUrlUtil.graphic(canonicalUid, item.getVideoid());
			if (graphicSourceUrl != null) {
				item.setSourceurl(graphicSourceUrl);
			}
			upsertAuthor("抖音", canonicalUid, username, item.getAuthor(), item.getAuthoravatar(),
					AuthorIdentityUtil.douyinHomepage(canonicalUid), authorSignature(profileAuthor, hybridAuthor));
			graphicContentDao.save(item);
			repairedGraphics++;
			if (mergeLegacyProfile("抖音", legacyUid, canonicalUid)) {
				mergedProfiles++;
			}
		}

		Map<String, Object> result = new HashMap<>();
		result.put("scannedVideos", scannedVideos);
		result.put("repairedVideos", repairedVideos);
		result.put("scannedGraphics", scannedGraphics);
		result.put("repairedGraphics", repairedGraphics);
		result.put("localResolved", localResolved);
		result.put("hybridApiSuccess", hybridApiSuccess);
		result.put("profileApiSuccess", profileApiSuccess);
		result.put("apiFailed", apiFailed);
		result.put("unresolved", unresolved);
		result.put("mergedProfiles", mergedProfiles);
		result.put("rebuiltAuthors", authorProfileDao.findByPlatform("抖音").size());
		return new AjaxEntity(Global.ajax_success, "修复完成", result);
	}

	private JSONObject findStoredAuthor(String rawJson) {
		if (!hasText(rawJson)) {
			return null;
		}
		try {
			JSONObject payload = JSON.parseObject(rawJson);
			JSONObject author = findHybridAuthor(payload);
			if (author == null) {
				author = payload.getJSONObject("author");
			}
			return author != null ? author : extractProfileUser(payload);
		} catch (Exception e) {
			return null;
		}
	}

	private boolean hasCanonicalDouyinUid(JSONObject author) {
		return author != null && AuthorIdentityUtil.isDouyinSecUid(author.getString("sec_uid"));
	}

	private JSONObject resolveProfileAuthorCached(Map<String, JSONObject> cache, String canonicalUid) {
		if (!AuthorIdentityUtil.isDouyinSecUid(canonicalUid)) {
			return null;
		}
		String key = "uid:" + canonicalUid;
		if (cache.containsKey(key)) {
			return cache.get(key);
		}
		JSONObject resolved = resolveProfileAuthor(canonicalUid);
		cache.put(key, resolved);
		return resolved;
	}

	private boolean mergeLegacyProfile(String platform, String legacyUid, String canonicalUid) {
		if (!hasText(legacyUid) || !AuthorIdentityUtil.isDouyinSecUid(canonicalUid)
				|| canonicalUid.equals(legacyUid) || AuthorIdentityUtil.isDouyinSecUid(legacyUid)) {
			return false;
		}
		Optional<AuthorProfileEntity> legacyOpt = authorProfileDao.findByPlatformAndAuthoruid(platform, legacyUid.trim());
		Optional<AuthorProfileEntity> canonicalOpt = authorProfileDao.findByPlatformAndAuthoruid(platform, canonicalUid);
		if (legacyOpt.isEmpty() || canonicalOpt.isEmpty()) {
			return false;
		}
		AuthorProfileEntity legacy = legacyOpt.get();
		AuthorProfileEntity canonical = canonicalOpt.get();
		if (legacy.getId() == null || canonical.getId() == null || legacy.getId().equals(canonical.getId())) {
			return false;
		}
		canonical.setUsername(firstNotBlank(canonical.getUsername(), legacy.getUsername()));
		canonical.setDisplayname(firstNotBlank(canonical.getDisplayname(), legacy.getDisplayname()));
		canonical.setAvatar(firstNotBlank(canonical.getAvatar(), legacy.getAvatar()));
		canonical.setSignature(firstNotBlank(canonical.getSignature(), legacy.getSignature()));
		canonical.setHomepage(AuthorIdentityUtil.douyinHomepage(canonicalUid));
		canonical.setUpdatetime(new Date());
		authorProfileDao.save(canonical);

		mergeNameHistory(canonical.getId(), legacy.getDisplayname(), legacy.getCreatetime(), legacy.getUpdatetime());
		for (AuthorNameHistoryEntity history : authorNameHistoryDao.findByAuthorProfileIdOrderByLastSeen(legacy.getId())) {
			if (history != null) {
				mergeNameHistory(canonical.getId(), history.getDisplayname(), history.getFirstseentime(), history.getLastseentime());
			}
		}
		authorNameHistoryDao.deleteByAuthorprofileid(legacy.getId());
		authorProfileDao.delete(legacy);
		return true;
	}

	private void mergeNameHistory(Integer profileId, String displayName, Date firstSeen, Date lastSeen) {
		if (profileId == null || !hasText(displayName)) {
			return;
		}
		Date safeFirst = firstSeen == null ? new Date() : firstSeen;
		Date safeLast = lastSeen == null ? safeFirst : lastSeen;
		AuthorNameHistoryEntity target = authorNameHistoryDao
				.findByAuthorprofileidAndDisplayname(profileId, displayName.trim())
				.orElseGet(AuthorNameHistoryEntity::new);
		if (target.getId() == null) {
			target.setAuthorprofileid(profileId);
			target.setDisplayname(displayName.trim());
			target.setFirstseentime(safeFirst);
			target.setLastseentime(safeLast);
		} else {
			if (target.getFirstseentime() == null || safeFirst.before(target.getFirstseentime())) {
				target.setFirstseentime(safeFirst);
			}
			if (target.getLastseentime() == null || safeLast.after(target.getLastseentime())) {
				target.setLastseentime(safeLast);
			}
		}
		authorNameHistoryDao.save(target);
	}

	private void applyVideoAuthorFromAuthor(VideoDataEntity video, JSONObject author) {
		if (author == null) {
			return;
		}
		String secUid = author.getString("sec_uid");
		String uniqueId = author.getString("unique_id");
		String nickname = author.getString("nickname");
		String avatar = firstNotBlank(DouUtil.extractAvatar(author), author.getString("avatar_thumb"));
		String canonicalUid = AuthorIdentityUtil.canonicalAuthorUid(video.getVideoplatform(), video.getAuthoruid(), secUid);
		if (canonicalUid != null) {
			video.setAuthoruid(canonicalUid);
			video.setSecuid(canonicalUid);
		}
		String username = AuthorIdentityUtil.canonicalUsername(uniqueId, video.getAuthorusername());
		if (username != null) {
			video.setAuthorusername(username);
			video.setUniqueid(username);
		}
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
		String canonicalUid = AuthorIdentityUtil.canonicalAuthorUid(item.getPlatform(), item.getAuthoruid(), secUid);
		if (canonicalUid != null) {
			item.setAuthoruid(canonicalUid);
			item.setSecuid(canonicalUid);
		}
		String username = AuthorIdentityUtil.canonicalUsername(uniqueId, item.getAuthorusername());
		if (username != null) {
			item.setAuthorusername(username);
			item.setUniqueid(username);
		}
		item.setAuthor(firstNotBlank(nickname, item.getAuthor()));
		item.setAuthoravatar(firstNotBlank(avatar, item.getAuthoravatar()));
	}

	private JSONObject findHybridAuthor(JSONObject hybrid) {
		JSONObject detail = DouUtil.findAwemeDetail(hybrid);
		return detail == null ? null : detail.getJSONObject("author");
	}

	private JSONObject resolveProfileAuthor(String secUid) {
		return extractProfileUser(DouUtil.fetchUserProfile(secUid));
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
		return AuthorIdentityUtil.isDouyinPlatform(platform);
	}

	public static String preferDouyinAuthorUid(String secUid, String fallback) {
		return AuthorIdentityUtil.canonicalAuthorUid("douyin", fallback, secUid);
	}

	public static boolean isDouyinPlatformValue(String platform) {
		return AuthorIdentityUtil.isDouyinPlatform(platform);
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
