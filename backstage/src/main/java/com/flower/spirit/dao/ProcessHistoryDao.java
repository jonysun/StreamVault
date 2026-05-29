package com.flower.spirit.dao;

import java.util.List;

import jakarta.transaction.Transactional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import com.flower.spirit.entity.ProcessHistoryEntity;




@Repository
@Transactional
public interface ProcessHistoryDao extends JpaRepository<ProcessHistoryEntity, Integer>, JpaSpecificationExecutor<ProcessHistoryEntity>{

	
	public List<ProcessHistoryEntity> findAll();

	public List<ProcessHistoryEntity> findByVideoplatformOrderByIdDesc(String videoplatform);

	

}
