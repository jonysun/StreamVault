package com.flower.spirit.dao;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.flower.spirit.entity.FfmpegQueueDataEntity;



@Repository
public interface FfmpegQueueDataDao extends JpaRepository<FfmpegQueueDataEntity, Integer>, JpaSpecificationExecutor<FfmpegQueueDataEntity> {

	List<FfmpegQueueDataEntity> findByStatus(String string);

	List<FfmpegQueueDataEntity> findByQueueid(Integer id);

}
