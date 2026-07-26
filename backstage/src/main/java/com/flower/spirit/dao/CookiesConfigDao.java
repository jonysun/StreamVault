package com.flower.spirit.dao;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import com.flower.spirit.entity.CookiesConfigEntity;

@Repository
public interface CookiesConfigDao extends JpaRepository<CookiesConfigEntity, Integer>, JpaSpecificationExecutor<CookiesConfigEntity> {

	public List<CookiesConfigEntity> findAll();
	
}
