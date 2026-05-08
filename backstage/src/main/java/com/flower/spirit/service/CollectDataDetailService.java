package com.flower.spirit.service;

import java.util.ArrayList;
import java.util.List;

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
import com.flower.spirit.dao.CollectdDataDetailDao;
import com.flower.spirit.entity.CollectDataDetailEntity;

@Service
public class CollectDataDetailService {
	
	
	@Autowired
	private CollectdDataDetailDao collectdDataDetailDao;

	public CollectDataDetailEntity save(CollectDataDetailEntity collectDataDetailEntity) {
		return collectdDataDetailDao.save(collectDataDetailEntity);
	}

	public AjaxEntity findPage(CollectDataDetailEntity res) {
	    PageRequest of = PageRequest.of(res.getPageNo(), res.getPageSize());

	    Specification<CollectDataDetailEntity> specification = (root, query, cb) -> {
	        List<Predicate> predicates = new ArrayList<>();

	        if (res != null) {
	            if (res.getDataid() != null) {
	                predicates.add(cb.equal(root.get("dataid"), res.getDataid()));
	            }
	            if (res.getStatus() != null) {
	                predicates.add(cb.like(root.get("status"), "%" + res.getStatus() + "%"));
	            }
	            if (res.getMediatype() != null && !res.getMediatype().trim().isEmpty()) {
	            	predicates.add(cb.equal(root.get("mediatype"), res.getMediatype().trim()));
	            }
	            if (res.getErrorcode() != null && !res.getErrorcode().trim().isEmpty()) {
	            	predicates.add(cb.like(root.get("errorcode"), "%" + res.getErrorcode().trim() + "%"));
	            }
	        }

	        query.orderBy(cb.desc(root.get("id")));

	        return cb.and(predicates.toArray(new Predicate[0]));
	    };

	    Page<CollectDataDetailEntity> findAll = collectdDataDetailDao.findAll(specification, of);
	    return new AjaxEntity(Global.ajax_success, "数据获取成功", findAll);
	}

	public void deleteDataid(Integer dataid) {
		collectdDataDetailDao.deleteByDataid(dataid);
	}

	public CollectDataDetailEntity findByVideoAndDataid(String videoid, Integer id) {
		List<CollectDataDetailEntity> byVideoidAndDataid = collectdDataDetailDao.findByVideoidAndDataid(videoid,id);
		if (byVideoidAndDataid.isEmpty()) {
			return null;
		} else {
			return byVideoidAndDataid.get(0);
		}
	}

}
