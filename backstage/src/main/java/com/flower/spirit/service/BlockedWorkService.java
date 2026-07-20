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
import com.flower.spirit.dao.BlockedWorkDao;
import com.flower.spirit.entity.BlockedWorkEntity;
import com.flower.spirit.utils.AuthorIdentityUtil;

import jakarta.persistence.criteria.Predicate;

@Service
public class BlockedWorkService {

	@Autowired
	private BlockedWorkDao blockedWorkDao;

	public void blockWork(String platform, String workId, String workType, String title, String authorName, String authorUid, String sourceUrl, String reason) {
		if (platform == null || platform.trim().isEmpty() || workId == null || workId.trim().isEmpty() || workType == null || workType.trim().isEmpty()) {
			return;
		}
		Date now = new Date();
		Optional<BlockedWorkEntity> opt = blockedWorkDao.findByPlatformAndWorkidAndWorktypeAndStatus(platform.trim(), workId.trim(), workType.trim(), "blocked");
		BlockedWorkEntity entity = opt.orElseGet(BlockedWorkEntity::new);
		if (entity.getId() == null) {
			entity.setCreatetime(now);
		}
		entity.setPlatform(platform.trim());
		entity.setWorkid(workId.trim());
		entity.setWorktype(workType.trim());
		entity.setTitle(title);
		entity.setAuthorname(authorName);
		entity.setAuthoruid(AuthorIdentityUtil.canonicalAuthorUid(platform, authorUid, authorUid));
		entity.setSourceurl(sourceUrl);
		entity.setReason(reason);
		entity.setStatus("blocked");
		entity.setUpdatetime(now);
		blockedWorkDao.save(entity);
	}

	public boolean isBlocked(String platform, String workId, String workType) {
		if (platform == null || workId == null || workType == null) {
			return false;
		}
		return blockedWorkDao.findByPlatformAndWorkidAndWorktypeAndStatus(platform.trim(), workId.trim(), workType.trim(), "blocked").isPresent();
	}

	public AjaxEntity restore(Integer id) {
		Optional<BlockedWorkEntity> opt = blockedWorkDao.findById(id);
		if (!opt.isPresent()) {
			return new AjaxEntity(Global.ajax_uri_error, "记录不存在", null);
		}
		BlockedWorkEntity entity = opt.get();
		entity.setStatus("restored");
		entity.setUpdatetime(new Date());
		blockedWorkDao.save(entity);
		return new AjaxEntity(Global.ajax_success, "已恢复", entity);
	}

	public AjaxEntity findPage(BlockedWorkEntity queryEntity) {
		PageRequest pageRequest = PageRequest.of(queryEntity.getPageNo(), queryEntity.getPageSize());
		Specification<BlockedWorkEntity> specification = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (queryEntity.getPlatform() != null && !queryEntity.getPlatform().trim().isEmpty()) {
				predicates.add(cb.like(root.get("platform"), "%" + queryEntity.getPlatform().trim() + "%"));
			}
			if (queryEntity.getStatus() != null && !queryEntity.getStatus().trim().isEmpty()) {
				predicates.add(cb.equal(root.get("status"), queryEntity.getStatus().trim()));
			}
			if (queryEntity.getKeyword() != null && !queryEntity.getKeyword().trim().isEmpty()) {
				String kw = "%" + queryEntity.getKeyword().trim() + "%";
				predicates.add(cb.or(
					cb.like(root.get("title"), kw),
					cb.like(root.get("workid"), kw),
					cb.like(root.get("authorname"), kw)
				));
			}
			query.orderBy(cb.desc(root.get("updatetime")), cb.desc(root.get("id")));
			return cb.and(predicates.toArray(new Predicate[0]));
		};
		Page<BlockedWorkEntity> page = blockedWorkDao.findAll(specification, pageRequest);
		return new AjaxEntity(Global.ajax_success, "数据获取成功", page);
	}
}
