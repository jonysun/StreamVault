package com.flower.spirit.service;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.utils.AuthorIdentityUtil;
import com.flower.spirit.utils.CommandUtil;
import com.flower.spirit.utils.StringUtil;

@Service
public class GraphicContentService {
	
	
	@Autowired
	private GraphicContentDao graphicContentDao;

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private BlockedWorkService blockedWorkService;

	@Autowired
	private DouyinWorkMaintenanceService douyinWorkMaintenanceService;

	@Autowired
	private PlatformMetadataCompatibilityService platformMetadataCompatibilityService;
	
	
	
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
	                predicates.add(buildGraphicPlatformPredicate(root, cb, res.getPlatform()));
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
	            String effectiveAuthorUid = StringUtil.isString(res.getAuthoruid()) ? res.getAuthoruid().trim()
	                    : (StringUtil.isString(res.getSecuid()) ? res.getSecuid().trim() : null);
	            if (StringUtil.isString(effectiveAuthorUid)) {
	                String safeUid = effectiveAuthorUid.trim();
	                predicates.add(cb.or(cb.equal(root.get("authoruid"), safeUid), cb.equal(root.get("secuid"), safeUid)));
	            }
	            if (StringUtil.isString(res.getPublishStart())) {
	            	predicates.add(cb.greaterThanOrEqualTo(root.get("publishtime"), res.getPublishStart().trim() + " 00:00:00"));
	            }
	            if (StringUtil.isString(res.getPublishEnd())) {
	            	predicates.add(cb.lessThanOrEqualTo(root.get("publishtime"), res.getPublishEnd().trim() + " 23:59:59"));
	            }
	        }

			String sortField = resolveGraphicSortField(res == null ? null : res.getSortField());
			String sortOrder = resolveSortOrder(res == null ? null : res.getSortOrder());
			if ("id".equals(sortField)) {
				if ("asc".equalsIgnoreCase(sortOrder)) {
					query.orderBy(cb.asc(root.get("id")));
				} else {
					query.orderBy(cb.desc(root.get("id")));
				}
			} else {
				if ("asc".equalsIgnoreCase(sortOrder)) {
					query.orderBy(cb.asc(root.get(sortField)), cb.desc(root.get("id")));
				} else {
					query.orderBy(cb.desc(root.get(sortField)), cb.desc(root.get("id")));
				}
			}
	        return cb.and(predicates.toArray(new Predicate[0]));
	    };

	    Page<GraphicContentEntity> findAll = graphicContentDao.findAll(specification, pageRequest);
	    findAll = findAll.map(this::copyForResponse);
	    return new AjaxEntity(Global.ajax_success, "数据获取成功", findAll);
	}




	public AjaxEntity findLitePage(GraphicContentEntity res) {
		int pageNo = res == null ? 0 : Math.max(0, res.getPageNo());
		int pageSize = res == null ? 25 : Math.max(1, res.getPageSize());
		PageRequest pageRequest = PageRequest.of(pageNo, pageSize);
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
		CriteriaQuery<Tuple> query = cb.createTupleQuery();
		Root<GraphicContentEntity> root = query.from(GraphicContentEntity.class);
		query.multiselect(
				root.get("id").alias("id"),
				root.get("originaladdress").alias("originaladdress"),
				root.get("videoid").alias("videoid"),
				root.get("platform").alias("platform"),
				root.get("title").alias("title"),
				root.get("content").alias("content"),
				root.get("images").alias("images"),
				root.get("author").alias("author"),
				root.get("authoruid").alias("authoruid"),
				root.get("authorusername").alias("authorusername"),
				root.get("authoravatar").alias("authoravatar"),
				root.get("secuid").alias("secuid"),
				root.get("uniqueid").alias("uniqueid"),
				root.get("createtime").alias("createtime"),
				root.get("publishtime").alias("publishtime"),
				root.get("sourceurl").alias("sourceurl"),
				root.get("platformkey").alias("platformkey"),
				root.get("contenttype").alias("contenttype"),
				root.get("authorhomepage").alias("authorhomepage"),
				root.get("privacy").alias("privacy"),
				root.get("favorite").alias("favorite"));

		query.where(buildGraphicPredicates(res, root, cb));
		query.orderBy(buildGraphicOrders(res, root, cb));
		List<Tuple> tuples = entityManager.createQuery(query)
				.setFirstResult(pageNo * pageSize)
				.setMaxResults(pageSize)
				.getResultList();
		long totalElements = countLiteItems(res);
		List<GraphicContentEntity> items = new ArrayList<>();
		for (Tuple tuple : tuples) {
			items.add(toLiteGraphicEntity(tuple));
		}
		Page<GraphicContentEntity> page = new PageImpl<>(items, pageRequest, totalElements);
		return new AjaxEntity(Global.ajax_success, "数据获取成功", page);
	}

	private long countLiteItems(GraphicContentEntity res) {
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
		CriteriaQuery<Long> query = cb.createQuery(Long.class);
		Root<GraphicContentEntity> root = query.from(GraphicContentEntity.class);
		query.select(cb.count(root));
		query.where(buildGraphicPredicates(res, root, cb));
		return entityManager.createQuery(query).getSingleResult();
	}

	private Predicate[] buildGraphicPredicates(GraphicContentEntity res, Root<GraphicContentEntity> root, CriteriaBuilder cb) {
		List<Predicate> predicates = new ArrayList<>();
		if (res != null) {
			if (StringUtil.isString(res.getTitle()) && StringUtil.isString(res.getContent())) {
				predicates.add(cb.or(
						cb.like(root.get("title"), "%" + res.getTitle() + "%"),
						cb.like(root.get("content"), "%" + res.getContent() + "%")));
			} else if (StringUtil.isString(res.getTitle())) {
				predicates.add(cb.like(root.get("title"), "%" + res.getTitle() + "%"));
			} else if (StringUtil.isString(res.getContent())) {
				predicates.add(cb.like(root.get("content"), "%" + res.getContent() + "%"));
			}
			if (StringUtil.isString(res.getPlatform())) {
				predicates.add(buildGraphicPlatformPredicate(root, cb, res.getPlatform()));
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
			String effectiveAuthorUid = StringUtil.isString(res.getAuthoruid()) ? res.getAuthoruid().trim()
					: (StringUtil.isString(res.getSecuid()) ? res.getSecuid().trim() : null);
			if (StringUtil.isString(effectiveAuthorUid)) {
				String safeUid = effectiveAuthorUid.trim();
				predicates.add(cb.or(cb.equal(root.get("authoruid"), safeUid), cb.equal(root.get("secuid"), safeUid)));
			}
			if (StringUtil.isString(res.getPublishStart())) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("publishtime"), res.getPublishStart().trim() + " 00:00:00"));
			}
			if (StringUtil.isString(res.getPublishEnd())) {
				predicates.add(cb.lessThanOrEqualTo(root.get("publishtime"), res.getPublishEnd().trim() + " 23:59:59"));
			}
		}
		return predicates.toArray(new Predicate[0]);
	}

	private Order[] buildGraphicOrders(GraphicContentEntity res, Root<GraphicContentEntity> root, CriteriaBuilder cb) {
		String sortField = resolveGraphicSortField(res == null ? null : res.getSortField());
		String sortOrder = resolveSortOrder(res == null ? null : res.getSortOrder());
		if ("id".equals(sortField)) {
			return "asc".equalsIgnoreCase(sortOrder)
					? new Order[] { cb.asc(root.get("id")) }
					: new Order[] { cb.desc(root.get("id")) };
		}
		return "asc".equalsIgnoreCase(sortOrder)
				? new Order[] { cb.asc(root.get(sortField)), cb.desc(root.get("id")) }
				: new Order[] { cb.desc(root.get(sortField)), cb.desc(root.get("id")) };
	}

	private GraphicContentEntity toLiteGraphicEntity(Tuple tuple) {
		GraphicContentEntity item = new GraphicContentEntity();
		if (tuple == null) {
			return item;
		}
		item.setId(tuple.get("id", Integer.class));
		item.setOriginaladdress(tuple.get("originaladdress", String.class));
		item.setVideoid(tuple.get("videoid", String.class));
		item.setPlatform(tuple.get("platform", String.class));
		item.setTitle(tuple.get("title", String.class));
		item.setContent(tuple.get("content", String.class));
		item.setImages(tuple.get("images", String.class));
		item.setAuthor(tuple.get("author", String.class));
		item.setAuthoruid(tuple.get("authoruid", String.class));
		item.setAuthorusername(tuple.get("authorusername", String.class));
		item.setAuthoravatar(tuple.get("authoravatar", String.class));
		item.setSecuid(tuple.get("secuid", String.class));
		item.setUniqueid(tuple.get("uniqueid", String.class));
		item.setCreatetime(tuple.get("createtime", Date.class));
		item.setPublishtime(tuple.get("publishtime", String.class));
		item.setSourceurl(tuple.get("sourceurl", String.class));
		item.setPlatformkey(tuple.get("platformkey", String.class));
		item.setContenttype(tuple.get("contenttype", String.class));
		item.setAuthorhomepage(tuple.get("authorhomepage", String.class));
		item.setPrivacy(tuple.get("privacy", String.class));
		item.setFavorite(tuple.get("favorite", String.class));
		normalizeResponseIdentity(item, item);
		return item;
	}

	private GraphicContentEntity copyForResponse(GraphicContentEntity source) {
		GraphicContentEntity target = new GraphicContentEntity();
		if (source == null) {
			return target;
		}
		BeanUtils.copyProperties(source, target, "pageNo", "pageSize");
		normalizeResponseIdentity(source, target);
		return target;
	}

	private void normalizeResponseIdentity(GraphicContentEntity source, GraphicContentEntity target) {
		String canonicalUid = AuthorIdentityUtil.canonicalAuthorUid(source.getPlatform(), source.getAuthoruid(), source.getSecuid());
		String canonicalUsername = AuthorIdentityUtil.canonicalUsername(source.getAuthorusername(), source.getUniqueid());
		target.setAuthoruid(canonicalUid);
		target.setSecuid(canonicalUid);
		target.setAuthorusername(canonicalUsername);
		target.setUniqueid(canonicalUsername);
		if (platformMetadataCompatibilityService != null) {
			platformMetadataCompatibilityService.enrichGraphic(target);
		} else {
			PlatformMetadataCompatibilityService.enrichCanonicalGraphic(target);
		}
	}

	private String resolveGraphicSortField(String requestedField) {
		String candidate = StringUtil.isString(requestedField) ? requestedField : Global.graphicListSortField;
		if ("createtime".equals(candidate) || "publishtime".equals(candidate) || "author".equals(candidate)) {
			return candidate;
		}
		return "id";
	}

	private Predicate buildGraphicPlatformPredicate(Root<GraphicContentEntity> root, CriteriaBuilder cb,
			String requestedPlatform) {
		List<Predicate> matches = new ArrayList<>();
		String canonicalKey = PlatformMetadataCompatibilityService.resolvePlatformKey(null, requestedPlatform);
		if (canonicalKey != null) {
			matches.add(cb.equal(cb.lower(root.get("platformkey")), canonicalKey.toLowerCase(java.util.Locale.ROOT)));
			for (String alias : PlatformMetadataCompatibilityService.resolveFilterAliases(requestedPlatform)) {
				matches.add(cb.equal(cb.lower(root.get("platform")), alias));
			}
		} else {
			matches.add(cb.like(cb.lower(root.get("platform")),
					"%" + requestedPlatform.trim().toLowerCase(java.util.Locale.ROOT) + "%"));
		}
		return cb.or(matches.toArray(new Predicate[0]));
	}

	private String resolveSortOrder(String requestedOrder) {
		String candidate = StringUtil.isString(requestedOrder) ? requestedOrder : Global.graphicListSortOrder;
		return "asc".equalsIgnoreCase(candidate) ? "asc" : "desc";
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
