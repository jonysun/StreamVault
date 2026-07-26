package com.flower.spirit.dao;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.flower.spirit.entity.GraphicContentEntity;

@Repository
public interface GraphicContentDao extends JpaRepository<GraphicContentEntity, Integer>,
		JpaSpecificationExecutor<GraphicContentEntity> {

	Optional<GraphicContentEntity> findById(Integer id);

	Optional<GraphicContentEntity> findByVideoidAndPlatform(String post, String name);

	List<GraphicContentEntity> findByPlatformkeyAndVideoid(String platformkey, String videoid);

	List<GraphicContentEntity> findByVideoidAndPlatformIn(String videoid, List<String> platforms);

	List<GraphicContentEntity> findByOriginaladdressAndPlatformIn(String originaladdress, List<String> platforms);

	Optional<GraphicContentEntity> findByOriginaladdressAndPlatform(String url, String name);

	List<GraphicContentEntity> findByPlatform(String platform);

	List<GraphicContentEntity> findByPlatformInAndIdGreaterThanOrderByIdAsc(List<String> platforms, Integer id,
			Pageable pageable);

	long countByPlatformIn(List<String> platforms);

	@Query("select count(g) from GraphicContentEntity g where g.platform in :platforms and ("
			+ "g.authoruid is null or g.authoruid not like 'MS4%' or g.secuid is null or g.secuid not like 'MS4%' "
			+ "or g.authorusername is null or trim(g.authorusername) = '' or g.authoravatar is null or trim(g.authoravatar) = '')")
	long countDouyinAuthorRepairCandidates(@Param("platforms") List<String> platforms);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Transactional
	@Query("update GraphicContentEntity g set g.authoruid = :authorUid, g.secuid = :authorUid, "
			+ "g.author = coalesce(:displayName, g.author), "
			+ "g.authorusername = coalesce(:username, g.authorusername), "
			+ "g.uniqueid = coalesce(:username, g.uniqueid), "
			+ "g.authoravatar = coalesce(:avatar, g.authoravatar), "
			+ "g.authorhomepage = coalesce(:homepage, g.authorhomepage) "
			+ "where (g.platformkey = 'douyin' or g.platform in :platforms) "
			+ "and (g.authoruid = :authorUid or g.secuid = :authorUid)")
	int updateDouyinAuthorMetadata(@Param("authorUid") String authorUid,
			@Param("displayName") String displayName, @Param("username") String username,
			@Param("avatar") String avatar, @Param("homepage") String homepage,
			@Param("platforms") List<String> platforms);

	/**
	 * 按平台分组统计图文内容数量
	 * 
	 * @return List<Object[]> 每个元素包含[platform, count]
	 */
	@Query("SELECT g.platform, COUNT(g) FROM GraphicContentEntity g GROUP BY g.platform")
	List<Object[]> countByPlatformGroupBy();

	/**
	 * 统计今日新增图文内容数量
	 * 
	 * @param startDate 今日开始时间
	 * @param endDate 今日结束时间
	 * @return 今日新增图文内容数量
	 */
	@Query("SELECT COUNT(g) FROM GraphicContentEntity g WHERE g.createtime >= :startDate AND g.createtime < :endDate")
	Long countTodayAdded(@Param("startDate") Date startDate, @Param("endDate") Date endDate);
	
	@Query(value = "SELECT * FROM biz_graphic_content WHERE platform = :platform ORDER BY RANDOM() LIMIT 1", nativeQuery = true)
	GraphicContentEntity findRandomByPlatform(@Param("platform") String platform);
	
	@Query(value = "SELECT * FROM biz_graphic_content ORDER BY id DESC LIMIT 3", nativeQuery = true)
	List<GraphicContentEntity> findRecentlyAdded();

	List<GraphicContentEntity> findByPlatformAndPublishtimeIsNull(String platform);

	@Query("SELECT DISTINCT g.author FROM GraphicContentEntity g WHERE g.author IS NOT NULL AND g.author <> '' ORDER BY g.author")
	List<String> findDistinctAuthors();
}
