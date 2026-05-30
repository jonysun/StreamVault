package com.flower.spirit.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.AuthorNameHistoryDao;
import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.AuthorNameHistoryEntity;
import com.flower.spirit.entity.AuthorProfileEntity;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.utils.DouUtil;
import com.flower.spirit.utils.DouyinSourceUrlUtil;

import jakarta.persistence.criteria.Predicate;

@Service
public class AuthorProfileService {

	@Autowired
	private AuthorProfileDao authorProfileDao;

	@Autowired
	private AuthorNameHistoryDao authorNameHistoryDao;

	@Autowired
	private VideoDataDao videoDataDao;

	@Autowired
	private GraphicContentDao graphicContentDao;

	public void upsertAuthor(String platform, String authoruid, String username, String displayName, String avatar, String homepage) {
		if (platform == null || platform.trim().isEmpty() || authoruid == null || authoruid.trim().isEmpty()) {
			return;
		}
		String safePlatform = platform.trim();
		String safeUid = authoruid.trim();
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

	public long countNameHistory(Integer authorProfileId) {
		return authorNameHistoryDao.countByAuthorprofileid(authorProfileId);
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
			String authorUid = firstNotBlank(video.getSecuid(), video.getAuthoruid());
			String username = firstNotBlank(video.getUniqueid(), video.getAuthorusername());
			if (authorUid != null && !authorUid.trim().isEmpty()) {
				video.setAuthoruid(authorUid);
				video.setSecuid(authorUid);
				video.setAuthorusername(username);
				video.setUniqueid(username);
				upsertAuthor("抖音", authorUid, username, video.getVideoauthor(), video.getAuthoravatar(),
						"https://www.douyin.com/user/" + authorUid);
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
				sourceUrl = DouyinSourceUrlUtil.note(item.getVideoid());
				item.setSourceurl(sourceUrl);
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
			String authorUid = firstNotBlank(item.getSecuid(), item.getAuthoruid());
			String username = firstNotBlank(item.getUniqueid(), item.getAuthorusername());
			if (authorUid != null && !authorUid.trim().isEmpty()) {
				item.setAuthoruid(authorUid);
				item.setSecuid(authorUid);
				item.setAuthorusername(username);
				item.setUniqueid(username);
				upsertAuthor("抖音", authorUid, username, item.getAuthor(), item.getAuthoravatar(),
						"https://www.douyin.com/user/" + authorUid);
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
		video.setAuthoruid(firstNotBlank(secUid, video.getAuthoruid()));
		video.setSecuid(firstNotBlank(secUid, video.getSecuid()));
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
		item.setAuthoruid(firstNotBlank(secUid, item.getAuthoruid()));
		item.setSecuid(firstNotBlank(secUid, item.getSecuid()));
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
		return "抖音".equals(platform) || "douyin".equalsIgnoreCase(platform);
	}

	private String firstNotBlank(String first, String second) {
		if (first != null && !first.trim().isEmpty()) {
			return first.trim();
		}
		return second == null ? null : second.trim();
	}
}
