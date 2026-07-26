package com.flower.spirit.dao;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.flower.spirit.entity.VideoMixSegmentEntity;




@Repository
public interface VideoMixSegmentDao extends JpaRepository<VideoMixSegmentEntity, Integer>, JpaSpecificationExecutor<VideoMixSegmentEntity>{

	
	public List<VideoMixSegmentEntity> findAll();

	public List<VideoMixSegmentEntity> findByVideomixidOrderBySegmentNoAsc(Integer id);

	@Transactional
	public void deleteByVideomixid(Integer id);


	

}
