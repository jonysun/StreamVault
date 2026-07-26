package com.flower.spirit.dao;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.flower.spirit.entity.BlockedWorkEntity;

@Repository
public interface BlockedWorkDao extends JpaRepository<BlockedWorkEntity, Integer>, JpaSpecificationExecutor<BlockedWorkEntity> {

	Optional<BlockedWorkEntity> findByPlatformAndWorkidAndWorktypeAndStatus(String platform, String workid, String worktype, String status);
}
