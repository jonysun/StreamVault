package com.flower.spirit.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
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
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.utils.AuthorIdentityUtil;
import com.flower.spirit.utils.DouUtil;

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
		String platformKey = PlatformCatalog.findByAlias(safePlatform)
				.map(PlatformDefinition::getKey)
				.orElseGet(() -> safePlatform.toLowerCase(Locale.ROOT));
		Date now = new Date();
		Optional<AuthorProfileEntity> opt = findPreferredProfile(platformKey, safePlatform, safeUid);
		AuthorProfileEntity entity = opt.orElseGet(AuthorProfileEntity::new);
		if (entity.getId() == null) {
			entity.setCreatetime(now);
		}
		entity.setPlatform(safePlatform);
		entity.setPlatformkey(platformKey);
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
		Optional<AuthorProfileEntity> existing = firstProfile(
				authorProfileDao.findAllByPlatformkeyAndAuthoruidOrderByUpdatetimeDescIdDesc(canonicalKey, safeUid));
		if (existing.isEmpty() && definition.isPresent()) {
			for (String alias : definition.get().getAliases()) {
				existing = firstProfile(
						authorProfileDao.findAllByPlatformAndAuthoruidOrderByUpdatetimeDescIdDesc(alias, safeUid));
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

	@Transactional
	public AuthorProfileRefreshResult applyExternalDouyinProfile(Integer authorProfileId, JSONObject profileUser) {
		if (authorProfileId == null) {
			throw new WorkMetadataValidationException("作者档案 ID 不能为空");
		}
		AuthorProfileEntity profile = authorProfileDao.findById(authorProfileId)
				.orElseThrow(() -> new WorkMetadataValidationException("作者档案不存在: " + authorProfileId));
		if (!AuthorIdentityUtil.isDouyinPlatform(profile.getPlatform())
				&& !"douyin".equalsIgnoreCase(trimToNull(profile.getPlatformkey()))) {
			throw new WorkMetadataValidationException("仅支持刷新抖音作者档案");
		}
		String targetUid = AuthorIdentityUtil.canonicalAuthorUid("douyin", profile.getAuthoruid(),
				profile.getAuthoruid());
		if (!AuthorIdentityUtil.isDouyinSecUid(targetUid)) {
			throw new WorkMetadataValidationException("作者缺少有效的抖音 sec_uid，请先执行作者修复");
		}
		String responseUid = profileUser == null ? null
				: AuthorIdentityUtil.canonicalAuthorUid("douyin", profileUser.getString("sec_uid"),
						profileUser.getString("sec_uid"));
		if (!targetUid.equals(responseUid)) {
			throw new WorkMetadataValidationException(responseUid == null
					? "外部 profile 响应缺少有效 sec_uid"
					: "外部 profile 返回了其他作者，已拒绝更新");
		}

		String oldDisplayName = trimToNull(profile.getDisplayname());
		String oldUsername = trimToNull(profile.getUsername());
		String oldAvatar = trimToNull(profile.getAvatar());
		String oldSignature = trimToNull(profile.getSignature());
		String oldHomepage = trimToNull(profile.getHomepage());
		String displayName = firstNotBlank(profileUser.getString("nickname"), oldDisplayName);
		String username = firstNotBlank(profileUser.getString("unique_id"), oldUsername);
		String avatar = firstNotBlank(DouUtil.extractAvatar(profileUser), oldAvatar);
		String signature = firstNotBlank(profileUser.getString("signature"), oldSignature);
		String homepage = AuthorIdentityUtil.douyinHomepage(targetUid);
		int authorFieldsUpdated = changedFieldCount(oldDisplayName, displayName, oldUsername, username,
				oldAvatar, avatar, oldSignature, signature, oldHomepage, homepage);

		profile.setPlatform("抖音");
		profile.setPlatformkey("douyin");
		profile.setAuthoruid(targetUid);
		profile.setDisplayname(displayName);
		profile.setUsername(username);
		profile.setAvatar(avatar);
		profile.setSignature(signature);
		profile.setHomepage(homepage);
		profile.setUpdatetime(new Date());
		AuthorProfileEntity saved = authorProfileDao.save(profile);
		if (saved.getId() != null && hasText(displayName)) {
			upsertNameHistory(saved.getId(), displayName.trim(), saved.getUpdatetime());
		}
		List<String> platforms = List.of("抖音", "douyin");
		int videosUpdated = videoDataDao.updateDouyinAuthorMetadata(targetUid, trimToNull(displayName),
				trimToNull(username), trimToNull(avatar), homepage, platforms);
		int graphicsUpdated = graphicContentDao.updateDouyinAuthorMetadata(targetUid, trimToNull(displayName),
				trimToNull(username), trimToNull(avatar), homepage, platforms);
		return new AuthorProfileRefreshResult(saved.getId(), targetUid, displayName, username, authorFieldsUpdated,
				videosUpdated, graphicsUpdated);
	}

	private int changedFieldCount(String... values) {
		int changed = 0;
		for (int i = 0; i + 1 < values.length; i += 2) {
			if (!Objects.equals(values[i], values[i + 1])) {
				changed++;
			}
		}
		return changed;
	}

	public record AuthorProfileRefreshResult(Integer authorProfileId, String authorUid, String displayName,
			String username, int authorFieldsUpdated, int videosUpdated, int graphicsUpdated) {
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
		String platform = item.getPlatform().trim();
		String platformKey = PlatformCatalog.findByAlias(platform)
				.map(PlatformDefinition::getKey)
				.orElseGet(() -> platform.toLowerCase(Locale.ROOT));
		findPreferredProfile(platformKey, platform, canonicalUid)
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
			String platformKey = PlatformCatalog.findByAlias(safePlatform)
					.map(PlatformDefinition::getKey)
					.orElseGet(() -> safePlatform.toLowerCase(Locale.ROOT));
			Optional<AuthorProfileEntity> byUid = findPreferredProfile(platformKey, safePlatform, safeUid);
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

	public WorkAuthorReconcileResult reconcileDouyinVideo(VideoDataEntity video, JSONObject observedAuthor,
			Map<String, JSONObject> profileCache) {
		if (video == null || !isDouyinPlatform(video.getVideoplatform())) {
			return WorkAuthorReconcileResult.skipped();
		}
		String before = videoIdentityFingerprint(video);
		String legacyUid = trimToNull(video.getAuthoruid());
		JSONObject localAuthor = observedAuthor == null ? findStoredAuthor(video.getJsonData()) : observedAuthor;
		applyVideoAuthorFromAuthor(video, localAuthor);

		String canonicalUid = AuthorIdentityUtil.canonicalAuthorUid(video.getVideoplatform(), video.getAuthoruid(), video.getSecuid());
		boolean localResolved = localAuthor != null && canonicalUid != null;
		JSONObject profileAuthor = needsProfileEnrichment(video, canonicalUid)
				? resolveDouyinProfileAuthorCached(profileCache, canonicalUid, video.getAuthorusername()) : null;
		applyVideoAuthorFromAuthor(video, profileAuthor);
		canonicalUid = AuthorIdentityUtil.canonicalAuthorUid(video.getVideoplatform(), video.getAuthoruid(), video.getSecuid());
		if (canonicalUid == null) {
			return WorkAuthorReconcileResult.unresolvedResult();
		}

		video.setAuthoruid(canonicalUid);
		video.setSecuid(canonicalUid);
		String username = AuthorIdentityUtil.canonicalUsername(video.getAuthorusername(), video.getUniqueid());
		video.setAuthorusername(username);
		video.setUniqueid(username);
		upsertReconciledDouyinAuthor(canonicalUid, username, video.getVideoauthor(), video.getAuthoravatar(),
				profileAuthor, localAuthor);
		boolean merged = mergeLegacyProfile("抖音", legacyUid, canonicalUid);
		merged = mergeCanonicalDuplicateProfiles(canonicalUid) || merged;
		boolean changed = !Objects.equals(before, videoIdentityFingerprint(video));
		if (changed) {
			videoDataDao.save(video);
		}
		return new WorkAuthorReconcileResult(changed, merged, localResolved, profileAuthor != null, false);
	}

	public WorkAuthorReconcileResult reconcileDouyinVideo(VideoDataEntity video, Map<String, JSONObject> profileCache) {
		return reconcileDouyinVideo(video, null, profileCache);
	}

	public WorkAuthorReconcileResult reconcileDouyinGraphic(GraphicContentEntity graphic, JSONObject observedAuthor,
			Map<String, JSONObject> profileCache) {
		if (graphic == null || !isDouyinPlatform(graphic.getPlatform())) {
			return WorkAuthorReconcileResult.skipped();
		}
		String before = graphicIdentityFingerprint(graphic);
		String legacyUid = trimToNull(graphic.getAuthoruid());
		JSONObject localAuthor = observedAuthor == null ? findStoredAuthor(graphic.getJsonData()) : observedAuthor;
		applyGraphicAuthorFromAuthor(graphic, localAuthor);

		String canonicalUid = AuthorIdentityUtil.canonicalAuthorUid(graphic.getPlatform(), graphic.getAuthoruid(), graphic.getSecuid());
		boolean localResolved = localAuthor != null && canonicalUid != null;
		JSONObject profileAuthor = needsProfileEnrichment(graphic, canonicalUid)
				? resolveDouyinProfileAuthorCached(profileCache, canonicalUid, graphic.getAuthorusername()) : null;
		applyGraphicAuthorFromAuthor(graphic, profileAuthor);
		canonicalUid = AuthorIdentityUtil.canonicalAuthorUid(graphic.getPlatform(), graphic.getAuthoruid(), graphic.getSecuid());
		if (canonicalUid == null) {
			return WorkAuthorReconcileResult.unresolvedResult();
		}

		graphic.setAuthoruid(canonicalUid);
		graphic.setSecuid(canonicalUid);
		String username = AuthorIdentityUtil.canonicalUsername(graphic.getAuthorusername(), graphic.getUniqueid());
		graphic.setAuthorusername(username);
		graphic.setUniqueid(username);
		upsertReconciledDouyinAuthor(canonicalUid, username, graphic.getAuthor(), graphic.getAuthoravatar(),
				profileAuthor, localAuthor);
		boolean merged = mergeLegacyProfile("抖音", legacyUid, canonicalUid);
		merged = mergeCanonicalDuplicateProfiles(canonicalUid) || merged;
		boolean changed = !Objects.equals(before, graphicIdentityFingerprint(graphic));
		if (changed) {
			graphicContentDao.save(graphic);
		}
		return new WorkAuthorReconcileResult(changed, merged, localResolved, profileAuthor != null, false);
	}

	public WorkAuthorReconcileResult reconcileDouyinGraphic(GraphicContentEntity graphic,
			Map<String, JSONObject> profileCache) {
		return reconcileDouyinGraphic(graphic, null, profileCache);
	}

	private void upsertReconciledDouyinAuthor(String canonicalUid, String username, String workNickname, String avatar,
			JSONObject profileAuthor, JSONObject fallbackAuthor) {
		AuthorProfileEntity existing = findCanonicalDouyinProfile(canonicalUid);
		String profileNickname = profileAuthor == null ? null : profileAuthor.getString("nickname");
		String currentNickname = firstNotBlank(profileNickname,
				firstNotBlank(existing == null ? null : existing.getDisplayname(), workNickname));
		String currentUsername = firstNotBlank(profileAuthor == null ? null : profileAuthor.getString("unique_id"),
				firstNotBlank(existing == null ? null : existing.getUsername(), username));
		String currentAvatar = firstNotBlank(profileAuthor == null ? null : DouUtil.extractAvatar(profileAuthor),
				firstNotBlank(existing == null ? null : existing.getAvatar(), avatar));
		upsertAuthor("抖音", canonicalUid, currentUsername, currentNickname, currentAvatar,
				AuthorIdentityUtil.douyinHomepage(canonicalUid), authorSignature(profileAuthor, fallbackAuthor));
		AuthorProfileEntity canonical = findCanonicalDouyinProfile(canonicalUid);
		if (canonical != null && canonical.getId() != null && hasText(workNickname)) {
			upsertNameHistory(canonical.getId(), workNickname.trim(), new Date());
		}
	}

	private AuthorProfileEntity findCanonicalDouyinProfile(String canonicalUid) {
		Optional<AuthorProfileEntity> byKey = firstProfile(
				authorProfileDao.findAllByPlatformkeyAndAuthoruidOrderByUpdatetimeDescIdDesc("douyin", canonicalUid));
		if (byKey.isPresent()) {
			return byKey.get();
		}
		for (String platform : List.of("抖音", "douyin")) {
			Optional<AuthorProfileEntity> byPlatform = firstProfile(
					authorProfileDao.findAllByPlatformAndAuthoruidOrderByUpdatetimeDescIdDesc(platform, canonicalUid));
			if (byPlatform.isPresent()) {
				return byPlatform.get();
			}
		}
		return null;
	}

	private boolean mergeCanonicalDuplicateProfiles(String canonicalUid) {
		List<AuthorProfileEntity> profiles = authorProfileDao.findByAuthoruid(canonicalUid).stream()
				.filter(profile -> profile != null && (AuthorIdentityUtil.isDouyinPlatform(profile.getPlatform())
						|| "douyin".equalsIgnoreCase(trimToNull(profile.getPlatformkey()))))
				.sorted(Comparator.comparing(AuthorProfileEntity::getUpdatetime,
						Comparator.nullsLast(Comparator.reverseOrder())))
				.toList();
		if (profiles.size() < 2) {
			return false;
		}
		AuthorProfileEntity primary = profiles.get(0);
		for (AuthorProfileEntity duplicate : profiles) {
			if (duplicate.getId() == null || duplicate.getId().equals(primary.getId())) {
				continue;
			}
			primary.setUsername(firstNotBlank(primary.getUsername(), duplicate.getUsername()));
			primary.setDisplayname(firstNotBlank(primary.getDisplayname(), duplicate.getDisplayname()));
			primary.setAvatar(firstNotBlank(primary.getAvatar(), duplicate.getAvatar()));
			primary.setSignature(firstNotBlank(primary.getSignature(), duplicate.getSignature()));
			mergeNameHistory(primary.getId(), duplicate.getDisplayname(), duplicate.getCreatetime(), duplicate.getUpdatetime());
			for (AuthorNameHistoryEntity history : authorNameHistoryDao.findByAuthorProfileIdOrderByLastSeen(duplicate.getId())) {
				if (history != null) {
					mergeNameHistory(primary.getId(), history.getDisplayname(), history.getFirstseentime(), history.getLastseentime());
				}
			}
			authorNameHistoryDao.deleteByAuthorprofileid(duplicate.getId());
			authorProfileDao.delete(duplicate);
		}
		primary.setPlatform("抖音");
		primary.setPlatformkey("douyin");
		primary.setHomepage(AuthorIdentityUtil.douyinHomepage(canonicalUid));
		primary.setUpdatetime(new Date());
		authorProfileDao.save(primary);
		return true;
	}

	private boolean needsProfileEnrichment(VideoDataEntity video, String canonicalUid) {
		return canonicalUid != null && (!hasText(video.getAuthorusername()) || !hasText(video.getVideoauthor())
				|| !hasText(video.getAuthoravatar()));
	}

	private boolean needsProfileEnrichment(GraphicContentEntity graphic, String canonicalUid) {
		return canonicalUid != null && (!hasText(graphic.getAuthorusername()) || !hasText(graphic.getAuthor())
				|| !hasText(graphic.getAuthoravatar()));
	}

	private String videoIdentityFingerprint(VideoDataEntity video) {
		return String.join("\u0001", safeFingerprint(video.getAuthoruid()), safeFingerprint(video.getSecuid()),
				safeFingerprint(video.getAuthorusername()), safeFingerprint(video.getUniqueid()),
				safeFingerprint(video.getVideoauthor()), safeFingerprint(video.getAuthoravatar()));
	}

	private String graphicIdentityFingerprint(GraphicContentEntity graphic) {
		return String.join("\u0001", safeFingerprint(graphic.getAuthoruid()), safeFingerprint(graphic.getSecuid()),
				safeFingerprint(graphic.getAuthorusername()), safeFingerprint(graphic.getUniqueid()),
				safeFingerprint(graphic.getAuthor()), safeFingerprint(graphic.getAuthoravatar()));
	}

	private String safeFingerprint(String value) {
		return value == null ? "" : value;
	}

	public record WorkAuthorReconcileResult(boolean updated, boolean merged, boolean localResolved,
			boolean apiResolved, boolean unresolved) {
		static WorkAuthorReconcileResult skipped() {
			return new WorkAuthorReconcileResult(false, false, false, false, false);
		}

		static WorkAuthorReconcileResult unresolvedResult() {
			return new WorkAuthorReconcileResult(false, false, false, false, true);
		}
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
			if (author == null) {
				JSONObject data = payload.getJSONObject("data");
				author = data == null ? null : data.getJSONObject("author");
			}
			return author != null ? author : extractProfileUser(payload);
		} catch (Exception e) {
			return null;
		}
	}

	public JSONObject resolveDouyinProfileAuthorCached(Map<String, JSONObject> cache, String canonicalUid,
			String uniqueId) {
		String safeUid = AuthorIdentityUtil.isDouyinSecUid(canonicalUid) ? canonicalUid.trim() : null;
		String safeUsername = trimToNull(uniqueId);
		if (safeUid == null && safeUsername == null) {
			return null;
		}
		String key = safeUid == null ? "username:" + safeUsername : "uid:" + safeUid;
		if (cache == null) {
			return safeUid == null ? extractProfileUser(DouUtil.fetchUserProfileByUniqueId(safeUsername))
					: resolveProfileAuthor(safeUid);
		}
		if (cache.containsKey(key)) {
			return cache.get(key);
		}
		JSONObject resolved = safeUid == null ? extractProfileUser(DouUtil.fetchUserProfileByUniqueId(safeUsername))
				: resolveProfileAuthor(safeUid);
		cache.put(key, resolved);
		if (resolved != null && AuthorIdentityUtil.isDouyinSecUid(resolved.getString("sec_uid"))) {
			cache.put("uid:" + resolved.getString("sec_uid").trim(), resolved);
		}
		return resolved;
	}

	private boolean mergeLegacyProfile(String platform, String legacyUid, String canonicalUid) {
		if (!hasText(legacyUid) || !AuthorIdentityUtil.isDouyinSecUid(canonicalUid)
				|| canonicalUid.equals(legacyUid) || AuthorIdentityUtil.isDouyinSecUid(legacyUid)) {
			return false;
		}
		Optional<AuthorProfileEntity> legacyOpt = findPreferredProfile("douyin", platform, legacyUid.trim());
		Optional<AuthorProfileEntity> canonicalOpt = findPreferredProfile("douyin", platform, canonicalUid);
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

	JSONObject extractProfileUser(JSONObject profile) {
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

	private Optional<AuthorProfileEntity> findPreferredProfile(String platformKey, String platform, String authorUid) {
		Optional<AuthorProfileEntity> byKey = firstProfile(
				authorProfileDao.findAllByPlatformkeyAndAuthoruidOrderByUpdatetimeDescIdDesc(platformKey, authorUid));
		if (byKey.isPresent()) {
			return byKey;
		}
		return firstProfile(authorProfileDao.findAllByPlatformAndAuthoruidOrderByUpdatetimeDescIdDesc(platform, authorUid));
	}

	private Optional<AuthorProfileEntity> firstProfile(List<AuthorProfileEntity> profiles) {
		return profiles == null || profiles.isEmpty() ? Optional.empty() : Optional.ofNullable(profiles.get(0));
	}

	private String firstNotBlank(String first, String second) {
		if (first != null && !first.trim().isEmpty()) {
			return first.trim();
		}
		return second == null ? null : second.trim();
	}
}
