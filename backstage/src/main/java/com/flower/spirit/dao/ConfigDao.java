package com.flower.spirit.dao;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.flower.spirit.entity.ConfigEntity;



@Repository
public interface ConfigDao extends JpaRepository<ConfigEntity, Integer>, JpaSpecificationExecutor<ConfigEntity>{

	
	public List<ConfigEntity> findAll();

	

}
