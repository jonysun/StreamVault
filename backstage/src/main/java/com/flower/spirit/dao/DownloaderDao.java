package com.flower.spirit.dao;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.flower.spirit.entity.DownloaderEntity;

@Repository
public interface DownloaderDao extends JpaRepository<DownloaderEntity, Integer>, JpaSpecificationExecutor<DownloaderEntity> {

	DownloaderEntity findByStatus(String string);

	
	List<DownloaderEntity> findAll();
}
