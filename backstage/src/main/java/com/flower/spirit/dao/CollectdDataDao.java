package com.flower.spirit.dao;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.flower.spirit.entity.CollectDataEntity;

@Repository
public interface CollectdDataDao
		extends JpaRepository<CollectDataEntity, Integer>, JpaSpecificationExecutor<CollectDataEntity> {

	public List<CollectDataEntity> findAll();

	public List<CollectDataEntity> findByMonitoring(String string);

	public List<CollectDataEntity> findByMonitoringAndTaskenabled(String monitoring, String taskenabled);

	@Query("SELECT c FROM CollectDataEntity c WHERE TRIM(c.originaladdress) = :originaladdress ORDER BY c.id ASC")
	List<CollectDataEntity> findByNormalizedOriginalAddress(@Param("originaladdress") String originalAddress);

	/**
	 * 统计收藏夹数据总数
	 * 
	 * @return 总数
	 */
	@Query("SELECT COUNT(c) FROM CollectDataEntity c")
	Long countTotal();

}
