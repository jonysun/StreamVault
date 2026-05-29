package com.flower.spirit.dao;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.flower.spirit.entity.AuthorProfileEntity;

import jakarta.transaction.Transactional;

@Repository
@Transactional
public interface AuthorProfileDao extends JpaRepository<AuthorProfileEntity, Integer>, JpaSpecificationExecutor<AuthorProfileEntity> {

	Optional<AuthorProfileEntity> findByPlatformAndAuthoruid(String platform, String authoruid);

	List<AuthorProfileEntity> findByPlatform(String platform);
}
