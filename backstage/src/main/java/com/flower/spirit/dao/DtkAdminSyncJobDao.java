package com.flower.spirit.dao;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.flower.spirit.entity.DtkAdminSyncJobEntity;

@Repository
public interface DtkAdminSyncJobDao extends JpaRepository<DtkAdminSyncJobEntity, String> {
	Optional<DtkAdminSyncJobEntity> findFirstByOrderByCreatedAtDesc();
}
