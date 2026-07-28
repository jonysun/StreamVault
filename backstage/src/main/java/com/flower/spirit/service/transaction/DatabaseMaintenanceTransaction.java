package com.flower.spirit.service.transaction;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flower.spirit.service.RuntimeHistoryRetentionPolicy;

@Service
public class DatabaseMaintenanceTransaction {

	private static final DateTimeFormatter SQLITE_TIMESTAMP = DateTimeFormatter
			.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

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
		return purgeByCondition(operationId, "biz_collect_run_item",
				"((UPPER(COALESCE(process_state, '')) = 'FAILED' AND created_at < ?) "
						+ "OR (UPPER(COALESCE(process_state, '')) <> 'FAILED' "
						+ "AND created_at < ?))",
				List.of(cutoff(now, RuntimeHistoryRetentionPolicy.FAILED_RUN_ITEM_DAYS),
						cutoff(now, RuntimeHistoryRetentionPolicy.NON_FAILED_RUN_ITEM_DAYS)),
				afterId, batchSize, now);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public BatchResult purgeExpiredRunEvents(long operationId, long afterId, int batchSize, Instant now) {
		return purgeByCondition(operationId, "biz_collect_run_event",
				"created_at < ?", List.of(cutoff(now, RuntimeHistoryRetentionPolicy.TERMINAL_HISTORY_DAYS)),
				afterId, batchSize, now);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public BatchResult purgeExpiredTerminalRuns(long operationId, long afterId, int batchSize, Instant now) {
		if (!tableExists("biz_collect_run")) return emptyBatch();
		StringBuilder condition = new StringBuilder("UPPER(COALESCE(state, '')) IN "
				+ "('COMPLETED','FETCH_FAILED','DB_FAILED','INTERRUPTED','SKIPPED_PAUSED','CANCELLED') "
				+ "AND created_at < ?");
		if (tableExists("biz_collect_run_item")) {
			condition.append(" AND NOT EXISTS (SELECT 1 FROM biz_collect_run_item item "
					+ "WHERE item.run_id = biz_collect_run.id)");
		}
		if (tableExists("biz_collect_run_event")) {
			condition.append(" AND NOT EXISTS (SELECT 1 FROM biz_collect_run_event event "
					+ "WHERE event.run_id = biz_collect_run.id)");
		}
		return purgeByCondition(operationId, "biz_collect_run", condition.toString(),
				List.of(cutoff(now, RuntimeHistoryRetentionPolicy.TERMINAL_HISTORY_DAYS)),
				afterId, batchSize, now);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public BatchResult purgeExpiredTerminalJobs(long operationId, long afterId, int batchSize, Instant now) {
		return purgeByCondition(operationId, "biz_job_queue",
				"UPPER(COALESCE(state, '')) IN ('COMPLETED','FAILED','CANCELLED') "
						+ "AND created_at < ?",
				List.of(cutoff(now, RuntimeHistoryRetentionPolicy.TERMINAL_HISTORY_DAYS)),
				afterId, batchSize, now);
	}

	private String cutoff(Instant now, int days) {
		return SQLITE_TIMESTAMP.format(now.minus(days, ChronoUnit.DAYS));
	}

	private BatchResult purgeByCondition(long operationId, String table, String condition,
			List<?> conditionParameters, long afterId, int batchSize, Instant now) {
		if (!tableExists(table)) return emptyBatch();
		List<Object> parameters = new ArrayList<>();
		parameters.add(afterId);
		parameters.addAll(conditionParameters);
		List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM " + table + " WHERE id > ? AND " + condition
				+ " ORDER BY id LIMIT " + batchSize, Long.class, parameters.toArray());
		int affected = updateIds("DELETE FROM " + table + " WHERE id IN (", ids);
		return recordBatch(operationId, ids, affected, batchSize, now);
	}

	private BatchResult emptyBatch() {
		return new BatchResult(0, 0L, true);
	}

	private boolean tableExists(String name) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", Integer.class, name);
		return count != null && count > 0;
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
