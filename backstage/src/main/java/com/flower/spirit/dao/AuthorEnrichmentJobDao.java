package com.flower.spirit.dao;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.flower.spirit.entity.AuthorEnrichmentJobEntity;

@Repository
public interface AuthorEnrichmentJobDao extends JpaRepository<AuthorEnrichmentJobEntity, Integer> {

	List<AuthorEnrichmentJobEntity> findTop20ByOrderByCreatedAtDescIdDesc();
}
