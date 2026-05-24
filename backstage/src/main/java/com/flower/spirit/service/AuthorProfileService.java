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

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.AuthorNameHistoryDao;
import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.entity.AuthorNameHistoryEntity;
import com.flower.spirit.entity.AuthorProfileEntity;

import jakarta.persistence.criteria.Predicate;

@Service
public class AuthorProfileService {

	@Autowired
	private AuthorProfileDao authorProfileDao;

	@Autowired
	private AuthorNameHistoryDao authorNameHistoryDao;

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
}
