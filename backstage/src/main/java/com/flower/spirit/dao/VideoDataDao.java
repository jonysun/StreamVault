package com.flower.spirit.dao;

import java.util.Date;
import java.util.List;

import jakarta.transaction.Transactional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.flower.spirit.entity.VideoDataEntity;
@Repository
@Transactional
public interface VideoDataDao
		extends JpaRepository<VideoDataEntity, Integer>, JpaSpecificationExecutor<VideoDataEntity> {

	List<VideoDataEntity> findByVideoid(String videoid);

	List<VideoDataEntity> findByVideoidAndVideoplatform(String id, String platform);

	List<VideoDataEntity> findByPlatformkeyAndVideoid(String platformkey, String videoid);

	List<VideoDataEntity> findByVideoidAndVideoplatformIn(String videoid, List<String> platforms);

	List<VideoDataEntity> findByOriginaladdressAndVideoplatformIn(String originaladdress, List<String> platforms);

	List<VideoDataEntity> findByVideoplatform(String videoplatform);

	List<VideoDataEntity> findByOriginaladdress(String originaladdress);

	/**
	 * 按平台分组统计视频数量
	 * 
	 * @return List<Object[]> 每个元素包含[platform, count]
	 */
	@Query("SELECT v.videoplatform, COUNT(v) FROM VideoDataEntity v GROUP BY v.videoplatform")
	List<Object[]> countByVideoplatformGroupBy();

	/**
	 * 统计今日新增视频数量
	 * 
	 * @param startDate 今日开始时间
	 * @param endDate 今日结束时间
	 * @return 今日新增视频数量
	 */
	@Query("SELECT COUNT(v) FROM VideoDataEntity v WHERE v.createtime >= :startDate AND v.createtime < :endDate")
	Long countTodayAdded(@Param("startDate") Date startDate, @Param("endDate") Date endDate);
	
	
	@Query(value = "SELECT * FROM biz_video WHERE videoplatform = :videoplatform ORDER BY RANDOM() LIMIT 1", nativeQuery = true)
	VideoDataEntity findRandomByVideoplatform(@Param("videoplatform") String videoplatform);
	
	@Query(value = "SELECT * FROM biz_video WHERE (videoprivacy IS NULL OR videoprivacy = '' OR videoprivacy != '1') ORDER BY id DESC LIMIT 3", nativeQuery = true)
	List<VideoDataEntity> findRecentlyAdded();

	List<VideoDataEntity> findByVideoplatformAndPublishtimeIsNull(String videoplatform);

	@Query("SELECT DISTINCT v.videoauthor FROM VideoDataEntity v WHERE v.videoauthor IS NOT NULL AND v.videoauthor <> '' ORDER BY v.videoauthor")
	List<String> findDistinctVideoauthors();
}
