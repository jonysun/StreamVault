package com.flower.spirit.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.flower.spirit.entity.AuthorNameHistoryEntity;

public interface AuthorNameHistoryDao extends JpaRepository<AuthorNameHistoryEntity, Integer> {

	Optional<AuthorNameHistoryEntity> findByAuthorprofileidAndDisplayname(Integer authorprofileid, String displayname);

	@Query("select h from AuthorNameHistoryEntity h where h.authorprofileid = :authorProfileId order by h.lastseentime desc")
	List<AuthorNameHistoryEntity> findByAuthorProfileIdOrderByLastSeen(@Param("authorProfileId") Integer authorProfileId);

	long countByAuthorprofileid(Integer authorprofileid);

	@Transactional
	void deleteByAuthorprofileid(Integer authorprofileid);
}
