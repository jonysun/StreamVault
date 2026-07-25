package com.flower.spirit.service.transaction;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseMaintenanceTransaction {

	private final JdbcTemplate jdbcTemplate;

	public DatabaseMaintenanceTransaction(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public long create(String tokenHash, String fingerprint, String operations, long estimatedRows, int batchSize,
			String firstOperation, Instant now) {
		KeyHolder keys = new GeneratedKeyHolder();
		Timestamp timestamp = Timestamp.from(now);
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO biz_database_maintenance_operation (preview_token_hash, db_fingerprint, operations, "
							+ "status, current_operation, last_processed_id, processed_rows, estimated_rows, batch_size, "
							+ "created_at, updated_at) VALUES (?, ?, ?, 'RUNNING', ?, 0, 0, ?, ?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			statement.setString(1, tokenHash);
			statement.setString(2, fingerprint);
			statement.setString(3, operations);
			statement.setString(4, firstOperation);
			statement.setLong(5, estimatedRows);
			statement.setInt(6, batchSize);
			statement.setTimestamp(7, timestamp);
			statement.setTimestamp(8, timestamp);
			return statement;
		}, keys);
		Number key = keys.getKey();
		if (key == null) throw new IllegalStateException("No database maintenance operation ID returned");
		return key.longValue();
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public Map<String, Object> find(long operationId) {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql() + " WHERE id = ?", operationId);
		return rows.isEmpty() ? Map.of() : rows.get(0);
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public Map<String, Object> findByTokenHash(String tokenHash) {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql() + " WHERE preview_token_hash = ?",
				tokenHash);
		return rows.isEmpty() ? Map.of() : rows.get(0);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public BatchResult clearDuplicateVideoInfo(long operationId, long afterId, int batchSize, Instant now) {
		List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM biz_video WHERE id > ? AND jsonData IS NOT NULL "
				+ "AND videoinfo IS NOT NULL AND jsonData = videoinfo ORDER BY id LIMIT " + batchSize,
				Long.class, afterId);
		int affected = updateIds("UPDATE biz_video SET videoinfo = NULL WHERE jsonData = videoinfo AND id IN (",
				ids);
		return recordBatch(operationId, ids, affected, batchSize, now);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public BatchResult purgeExpiredRunItems(long operationId, long afterId, int batchSize, Instant now) {
		List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM biz_collect_run_item WHERE id > ? AND "
				+ "((UPPER(COALESCE(process_state, '')) = 'FAILED' AND created_at < datetime('now','-365 days')) "
				+ "OR (UPPER(COALESCE(process_state, '')) <> 'FAILED' AND created_at < datetime('now','-90 days'))) "
				+ "ORDER BY id LIMIT " + batchSize, Long.class, afterId);
		int affected = updateIds("DELETE FROM biz_collect_run_item WHERE id IN (", ids);
		return recordBatch(operationId, ids, affected, batchSize, now);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void moveToOperation(long operationId, String operation, Instant now) {
		jdbcTemplate.update("UPDATE biz_database_maintenance_operation SET current_operation = ?, "
				+ "last_processed_id = 0, status = 'RUNNING', updated_at = ? WHERE id = ?",
				operation, Timestamp.from(now), operationId);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(long operationId, Instant now) {
		jdbcTemplate.update("UPDATE biz_database_maintenance_operation SET status = 'COMPLETED', "
				+ "current_operation = NULL, updated_at = ? WHERE id = ?", Timestamp.from(now), operationId);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void fail(long operationId, String message, Instant now) {
		jdbcTemplate.update("UPDATE biz_database_maintenance_operation SET status = 'FAILED', error_message = ?, "
				+ "updated_at = ? WHERE id = ?", truncate(message, 2048), Timestamp.from(now), operationId);
	}

	private BatchResult recordBatch(long operationId, List<Long> ids, int affected, int batchSize, Instant now) {
		long lastId = ids.isEmpty() ? 0L : ids.get(ids.size() - 1);
		if (!ids.isEmpty()) {
			jdbcTemplate.update("UPDATE biz_database_maintenance_operation SET last_processed_id = ?, "
					+ "processed_rows = processed_rows + ?, status = 'PARTIAL', updated_at = ? WHERE id = ?",
					lastId, affected, Timestamp.from(now), operationId);
		}
		return new BatchResult(affected, lastId, ids.size() < batchSize);
	}

	private int updateIds(String prefix, List<Long> ids) {
		if (ids.isEmpty()) return 0;
		String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
		List<Object> parameters = new ArrayList<>(ids);
		return jdbcTemplate.update(prefix + placeholders + ")", parameters.toArray());
	}

	private String selectSql() {
		return "SELECT id AS operationId, db_fingerprint AS dbFingerprint, operations, status, "
				+ "current_operation AS currentOperation, last_processed_id AS lastProcessedId, "
				+ "processed_rows AS processedRows, estimated_rows AS estimatedRows, batch_size AS batchSize, "
				+ "error_message AS errorMessage, created_at AS createdAt, updated_at AS updatedAt "
				+ "FROM biz_database_maintenance_operation";
	}

	private String truncate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) return value;
		return value.substring(0, maxLength);
	}

	public record BatchResult(int affectedRows, long lastProcessedId, boolean exhausted) {
	}
}
