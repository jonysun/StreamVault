package com.flower.spirit.service.transaction;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseInitializationTransaction {

	private final JdbcTemplate jdbcTemplate;

	public DatabaseInitializationTransaction(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void execute(String sql) {
		jdbcTemplate.execute(sql);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void executeAll(List<String> statements) {
		for (String statement : statements) {
			jdbcTemplate.execute(statement);
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void batchUpdate(String sql, List<Object[]> parameters) {
		jdbcTemplate.batchUpdate(sql, parameters);
	}
}
