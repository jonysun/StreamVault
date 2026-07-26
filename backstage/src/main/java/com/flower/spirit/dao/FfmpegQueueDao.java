package com.flower.spirit.dao;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.flower.spirit.entity.FfmpegQueueEntity;


@Repository
public interface FfmpegQueueDao  extends JpaRepository<FfmpegQueueEntity, Integer>, JpaSpecificationExecutor<FfmpegQueueEntity>  {

	List<FfmpegQueueEntity> findByVideostatusAndAudiostatus(String string, String string2);

	List<FfmpegQueueEntity> findByVideostatusAndAudiostatusAndStatus(String string, String string2, String string3);

}
