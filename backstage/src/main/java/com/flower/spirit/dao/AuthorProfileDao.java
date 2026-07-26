package com.flower.spirit.dao;

import java.util.Optional;
import java.util.List;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.flower.spirit.entity.AuthorProfileEntity;

@Repository
public interface AuthorProfileDao extends JpaRepository<AuthorProfileEntity, Integer>, JpaSpecificationExecutor<AuthorProfileEntity> {

	Optional<AuthorProfileEntity> findByPlatformAndAuthoruid(String platform, String authoruid);

	Optional<AuthorProfileEntity> findByPlatformkeyAndAuthoruid(String platformkey, String authoruid);

	List<AuthorProfileEntity> findAllByPlatformkeyAndAuthoruidOrderByUpdatetimeDescIdDesc(String platformkey,
			String authoruid);

	List<AuthorProfileEntity> findAllByPlatformAndAuthoruidOrderByUpdatetimeDescIdDesc(String platform,
			String authoruid);

	List<AuthorProfileEntity> findByPlatform(String platform);

	List<AuthorProfileEntity> findByAuthoruid(String authoruid);

	List<AuthorProfileEntity> findByAuthoruidIn(Collection<String> authoruids);

	long countByPlatformIn(List<String> platforms);

	@Query("select count(a) from AuthorProfileEntity a where a.platform in :platforms or a.platformkey = 'douyin'")
	long countDouyinProfiles(@Param("platforms") List<String> platforms);

	@Query("select count(a) from AuthorProfileEntity a where (a.platform in :platforms or a.platformkey = 'douyin') "
			+ "and (a.authoruid is null or a.authoruid not like 'MS4%')")
	long countLegacyDouyinProfiles(@Param("platforms") List<String> platforms);
}
