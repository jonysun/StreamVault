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
		int apiSuccess = 0;
		int apiFailed = 0;
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
			JSONObject hybrid = DouUtil.fetchHybridVideoData(sourceUrl);
			if (hybrid != null) {
				apiSuccess++;
				video.setJsonData(hybrid.toJSONString());
				video.setVideoinfo(hybrid.toJSONString());
				applyVideoAuthorFromHybrid(video, hybrid);
			} else {
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
			JSONObject hybrid = DouUtil.fetchHybridVideoData(sourceUrl);
			if (hybrid != null) {
				apiSuccess++;
				item.setJsonData(hybrid.toJSONString());
				applyGraphicAuthorFromHybrid(item, hybrid);
			} else {
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
		result.put("apiSuccess", apiSuccess);
		result.put("apiFailed", apiFailed);
		result.put("rebuiltAuthors", authorProfileDao.findByPlatform("抖音").size());
		return new AjaxEntity(Global.ajax_success, "重建完成", result);
	}

	private void applyVideoAuthorFromHybrid(VideoDataEntity video, JSONObject hybrid) {
		JSONObject detail = DouUtil.findAwemeDetail(hybrid);
		JSONObject author = detail == null ? null : detail.getJSONObject("author");
		if (author == null) {
			return;
		}
		String secUid = author.getString("sec_uid");
		String uniqueId = author.getString("unique_id");
		String nickname = author.getString("nickname");
		String avatar = DouUtil.extractAvatar(author);
		video.setAuthoruid(firstNotBlank(secUid, video.getAuthoruid()));
		video.setSecuid(firstNotBlank(secUid, video.getSecuid()));
		video.setAuthorusername(firstNotBlank(uniqueId, video.getAuthorusername()));
		video.setUniqueid(firstNotBlank(uniqueId, video.getUniqueid()));
		video.setVideoauthor(firstNotBlank(nickname, video.getVideoauthor()));
		video.setAuthoravatar(firstNotBlank(avatar, video.getAuthoravatar()));
	}

	private void applyGraphicAuthorFromHybrid(GraphicContentEntity item, JSONObject hybrid) {
		JSONObject detail = DouUtil.findAwemeDetail(hybrid);
		JSONObject author = detail == null ? null : detail.getJSONObject("author");
		if (author == null) {
			return;
		}
		String secUid = author.getString("sec_uid");
		String uniqueId = author.getString("unique_id");
		String nickname = author.getString("nickname");
		String avatar = DouUtil.extractAvatar(author);
		item.setAuthoruid(firstNotBlank(secUid, item.getAuthoruid()));
		item.setSecuid(firstNotBlank(secUid, item.getSecuid()));
		item.setAuthorusername(firstNotBlank(uniqueId, item.getAuthorusername()));
		item.setUniqueid(firstNotBlank(uniqueId, item.getUniqueid()));
		item.setAuthor(firstNotBlank(nickname, item.getAuthor()));
		item.setAuthoravatar(firstNotBlank(avatar, item.getAuthoravatar()));
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
