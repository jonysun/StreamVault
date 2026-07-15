package com.flower.spirit.service;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.utils.CommandUtil;
import com.flower.spirit.utils.StringUtil;

@Service
public class GraphicContentService {
	
	
	@Autowired
	private GraphicContentDao graphicContentDao;

	@Autowired
	private BlockedWorkService blockedWorkService;

	@Autowired
	private DouyinWorkMaintenanceService douyinWorkMaintenanceService;
	
	
	
	public AjaxEntity findPage(GraphicContentEntity res) {
	    PageRequest pageRequest = PageRequest.of(res.getPageNo(), res.getPageSize());

	    Specification<GraphicContentEntity> specification = (root, query, cb) -> {
	        List<Predicate> predicates = new ArrayList<>();

	        if (res != null) {
	            // OR 查询 title 和 content
	            if (StringUtil.isString(res.getTitle()) && StringUtil.isString(res.getContent())) {
	                predicates.add(cb.or(
	                        cb.like(root.get("title"), "%" + res.getTitle() + "%"),
	                        cb.like(root.get("content"), "%" + res.getContent() + "%")
	                ));
	            } else if (StringUtil.isString(res.getTitle())) {
	                predicates.add(cb.like(root.get("title"), "%" + res.getTitle() + "%"));
	            } else if (StringUtil.isString(res.getContent())) {
	                predicates.add(cb.like(root.get("content"), "%" + res.getContent() + "%"));
	            }

	            if (StringUtil.isString(res.getPlatform())) {
	                predicates.add(cb.like(root.get("platform"), "%" + res.getPlatform() + "%"));
	            }
	            if (StringUtil.isString(res.getAuthor())) {
	            	String[] authors = res.getAuthor().split(",");
	            	List<Predicate> authorPredicates = new ArrayList<>();
	            	for (String author : authors) {
	            		String trimmed = author == null ? "" : author.trim();
	            		if (!trimmed.isEmpty()) {
	            			authorPredicates.add(cb.like(root.get("author"), "%" + trimmed + "%"));
	            		}
	            	}
	            	if (!authorPredicates.isEmpty()) {
	            		predicates.add(cb.or(authorPredicates.toArray(new Predicate[0])));
	            	}
	            }
	            if (StringUtil.isString(res.getPublishStart())) {
	            	predicates.add(cb.greaterThanOrEqualTo(root.get("publishtime"), res.getPublishStart().trim() + " 00:00:00"));
	            }
	            if (StringUtil.isString(res.getPublishEnd())) {
	            	predicates.add(cb.lessThanOrEqualTo(root.get("publishtime"), res.getPublishEnd().trim() + " 23:59:59"));
	            }
	        }

	        String sortField = res == null || res.getSortField() == null || res.getSortField().trim().isEmpty() ? Global.graphicListSortField : res.getSortField();
	        String sortOrder = res == null || res.getSortOrder() == null || res.getSortOrder().trim().isEmpty() ? Global.graphicListSortOrder : res.getSortOrder();
	        String actualSortField = null;
	        if ("id".equals(sortField) || "createtime".equals(sortField) || "publishtime".equals(sortField) || "author".equals(sortField)) {
	        	actualSortField = sortField;
	        }
	        if (actualSortField != null) {
	        	if ("asc".equalsIgnoreCase(sortOrder)) {
	        		query.orderBy(cb.asc(root.get(actualSortField)), cb.desc(root.get("id")));
	        	} else {
	        		query.orderBy(cb.desc(root.get(actualSortField)), cb.desc(root.get("id")));
	        	}
	        } else {
	        	query.orderBy(cb.desc(root.get("id")));
	        }
	        return cb.and(predicates.toArray(new Predicate[0]));
	    };

	    Page<GraphicContentEntity> findAll = graphicContentDao.findAll(specification, pageRequest);
	    return new AjaxEntity(Global.ajax_success, "数据获取成功", findAll);
	}




	public AjaxEntity deleteGraphicContent(String id, String blockwork) {
		Optional<GraphicContentEntity> findById = graphicContentDao.findById(Integer.valueOf(id));
		if (findById.isPresent()) {
			GraphicContentEntity graphicContentEntity = findById.get();
			if (!"0".equals(blockwork)) {
				blockedWorkService.blockWork(graphicContentEntity.getPlatform(), graphicContentEntity.getVideoid(), "graphic",
						graphicContentEntity.getTitle(), graphicContentEntity.getAuthor(), graphicContentEntity.getAuthoruid(),
						graphicContentEntity.getSourceurl() != null ? graphicContentEntity.getSourceurl() : graphicContentEntity.getOriginaladdress(), "manual-delete");
			}
			CommandUtil.deleteDirectory(Paths.get(graphicContentEntity.getMarkroute()).normalize().toString());
			graphicContentDao.deleteById(Integer.valueOf(id));
		}
		return new AjaxEntity(Global.ajax_success, "操作成功", null);
	}

	public AjaxEntity redownloadGraphicContent(Integer id) {
		return douyinWorkMaintenanceService.redownloadGraphic(id);
	}



	public Map<String, Long> countByPlatformGroupBy() {
		List<Object[]> graphicPlatformStats = graphicContentDao.countByPlatformGroupBy();
		Map<String, Long> graphicPlatformMap = new HashMap<>();
		for (Object[] stat : graphicPlatformStats) {
			String platform = (String) stat[0];
			Long count = (Long) stat[1];
			graphicPlatformMap.put(platform != null ? platform : "未知", count);
		}
		return graphicPlatformMap;
	}

	/**
	 * 统计今日新增图文内容数量
	 * 
	 * @return 今日新增图文内容数量
	 */
	public Long countTodayAdded() {
		// 获取今日开始时间（00:00:00）
		Calendar calendar = Calendar.getInstance();
		calendar.set(Calendar.HOUR_OF_DAY, 0);
		calendar.set(Calendar.MINUTE, 0);
		calendar.set(Calendar.SECOND, 0);
		calendar.set(Calendar.MILLISECOND, 0);
		Date startDate = calendar.getTime();
		// 获取明日开始时间（作为今日结束时间）
		calendar.add(Calendar.DAY_OF_MONTH, 1);
		Date endDate = calendar.getTime();
		return graphicContentDao.countTodayAdded(startDate, endDate);
	}

	public GraphicContentEntity findRandomByPlatform(String platform) {
		return graphicContentDao.findRandomByPlatform(platform);
	}
	
	public List<GraphicContentEntity> findRecentlyAdded() {
		return graphicContentDao.findRecentlyAdded();
	}

	public List<String> findDistinctAuthors() {
		return graphicContentDao.findDistinctAuthors();
	}
}
