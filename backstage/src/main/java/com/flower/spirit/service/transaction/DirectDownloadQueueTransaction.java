package com.flower.spirit.service.transaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.service.DirectDownloadClaim;
import com.flower.spirit.service.DirectDownloadEnqueueResult;
import com.flower.spirit.service.DirectDownloadSource;
import com.flower.spirit.service.JobType;

@Service
public class DirectDownloadQueueTransaction {

	private static final String ACTIVE_STATES = "'QUEUED','RUNNING','RETRY_WAIT'";
	private final JdbcTemplate jdbcTemplate;

	public DirectDownloadQueueTransaction(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public DirectDownloadEnqueueResult enqueue(String sourceUrl, String platformKey, String platformName,
			String title, String author, DirectDownloadSource sourceType, String batchId, Integer historyId,
			Instant now, int maxAttempts) {
		String dedupeKey = "direct:" + sha256(sourceUrl);
		List<DirectDownloadEnqueueResult> existing = jdbcTemplate.query(
				"SELECT id, payload FROM biz_job_queue WHERE job_type = ? AND dedupe_key = ? AND state IN ("
						+ ACTIVE_STATES + ") ORDER BY id DESC LIMIT 1",
				(rs, rowNum) -> {
					JSONObject payload = JSONObject.parseObject(rs.getString("payload"));
					return new DirectDownloadEnqueueResult(rs.getLong("id"), payload.getInteger("historyId"), false,
							DirectDownloadSource.from(payload.getString("sourceType")));
				}, JobType.DIRECT_DOWNLOAD.name(), dedupeKey);
		if (!existing.isEmpty()) return existing.get(0);

		JSONObject payload = new JSONObject(true);
		payload.put("sourceUrl", sourceUrl);
		payload.put("platformKey", platformKey);
		payload.put("platformName", platformName);
		payload.put("title", title);
		payload.put("author", author);
		payload.put("sourceType", sourceType.name());
		payload.put("batchId", batchId);
		payload.put("historyId", historyId);
		Timestamp timestamp = Timestamp.from(now);
		KeyHolder keys = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(
					"INSERT INTO biz_job_queue (job_type, dedupe_key, payload, state, priority, available_at, "
							+ "attempt_count, max_attempts, created_at, updated_at) VALUES (?, ?, ?, 'QUEUED', 100, ?, 0, ?, ?, ?)",
					java.sql.Statement.RETURN_GENERATED_KEYS);
			statement.setString(1, JobType.DIRECT_DOWNLOAD.name());
			statement.setString(2, dedupeKey);
			statement.setString(3, payload.toJSONString());
			statement.setTimestamp(4, timestamp);
			statement.setInt(5, Math.max(1, maxAttempts));
			statement.setTimestamp(6, timestamp);
			statement.setTimestamp(7, timestamp);
			return statement;
		}, keys);
		Number id = keys.getKey();
		if (id == null) throw new IllegalStateException("Direct download queue insert returned no id");
		return new DirectDownloadEnqueueResult(id.longValue(), historyId, true, sourceType);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public DirectDownloadClaim claimNext(String workerId, Instant now) {
		List<JobRow> due = jdbcTemplate.query(
				"SELECT id, payload, attempt_count, max_attempts FROM biz_job_queue WHERE job_type = ? "
						+ "AND state IN ('QUEUED','RETRY_WAIT') AND available_at <= ? "
						+ "ORDER BY priority ASC, available_at ASC, id ASC LIMIT 1",
				(rs, rowNum) -> new JobRow(rs.getLong("id"), rs.getString("payload"),
						rs.getInt("attempt_count"), rs.getInt("max_attempts")),
				JobType.DIRECT_DOWNLOAD.name(), Timestamp.from(now));
		if (due.isEmpty()) return null;
		JobRow candidate = due.get(0);
		String lockToken = workerId + ":" + candidate.id();
		Timestamp timestamp = Timestamp.from(now);
		int updated = jdbcTemplate.update("UPDATE biz_job_queue SET state='RUNNING', attempt_count=attempt_count+1, "
				+ "locked_by=?, locked_at=?, last_error_code=NULL, last_error_message=NULL, updated_at=? "
				+ "WHERE id=? AND job_type=? AND state IN ('QUEUED','RETRY_WAIT')",
				lockToken, timestamp, timestamp, candidate.id(), JobType.DIRECT_DOWNLOAD.name());
		if (updated != 1) return null;
		JSONObject payload = JSONObject.parseObject(candidate.payload());
		return new DirectDownloadClaim(candidate.id(), payload.getString("sourceUrl"),
				payload.getString("platformKey"), payload.getString("platformName"), payload.getString("title"),
				payload.getString("author"), DirectDownloadSource.from(payload.getString("sourceType")),
				payload.getString("batchId"), payload.getInteger("historyId"), candidate.attemptCount() + 1,
				candidate.maxAttempts(), lockToken);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(DirectDownloadClaim claim, Instant now) {
		int updated = jdbcTemplate.update("UPDATE biz_job_queue SET state='COMPLETED', available_at=?, locked_by=NULL, "
				+ "locked_at=NULL, last_error_code=NULL, last_error_message=NULL, updated_at=? "
				+ "WHERE id=? AND job_type=? AND state='RUNNING' AND locked_by=?",
				Timestamp.from(now), Timestamp.from(now), claim.jobId(), JobType.DIRECT_DOWNLOAD.name(), claim.lockToken());
		assertUpdated(updated, claim.jobId());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean fail(DirectDownloadClaim claim, String code, String message, Instant now, Instant retryAt) {
		boolean retry = claim.attemptCount() < claim.maxAttempts();
		String state = retry ? "RETRY_WAIT" : "FAILED";
		Timestamp availableAt = Timestamp.from(retry ? retryAt : now);
		int updated = jdbcTemplate.update("UPDATE biz_job_queue SET state=?, available_at=?, locked_by=NULL, "
				+ "locked_at=NULL, last_error_code=?, last_error_message=?, updated_at=? "
				+ "WHERE id=? AND job_type=? AND state='RUNNING' AND locked_by=?", state, availableAt,
				truncate(code, 255), truncate(message, 2048), Timestamp.from(now), claim.jobId(),
				JobType.DIRECT_DOWNLOAD.name(), claim.lockToken());
		assertUpdated(updated, claim.jobId());
		return retry;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void deferPaused(DirectDownloadClaim claim, String message, Instant now, Instant retryAt) {
		int updated = jdbcTemplate.update("UPDATE biz_job_queue SET state='RETRY_WAIT', attempt_count=CASE "
				+ "WHEN attempt_count > 0 THEN attempt_count - 1 ELSE 0 END, available_at=?, locked_by=NULL, "
				+ "locked_at=NULL, last_error_code='DOWNLOAD_PAUSED', last_error_message=?, updated_at=? "
				+ "WHERE id=? AND job_type=? AND state='RUNNING' AND locked_by=?", Timestamp.from(retryAt),
				truncate(message, 2048), Timestamp.from(now), claim.jobId(), JobType.DIRECT_DOWNLOAD.name(),
				claim.lockToken());
		assertUpdated(updated, claim.jobId());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int recoverStale(Instant staleBefore, Instant now) {
		return jdbcTemplate.update("UPDATE biz_job_queue SET state=CASE WHEN attempt_count < max_attempts "
				+ "THEN 'RETRY_WAIT' ELSE 'FAILED' END, available_at=?, locked_by=NULL, locked_at=NULL, "
				+ "last_error_code='WORKER_RESTART_RECOVERY', last_error_message='Recovered stale direct download', "
				+ "updated_at=? WHERE job_type=? AND state='RUNNING' AND locked_at < ?", Timestamp.from(now),
				Timestamp.from(now), JobType.DIRECT_DOWNLOAD.name(), Timestamp.from(staleBefore));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean requeue(long jobId, Instant now) {
		return jdbcTemplate.update("UPDATE biz_job_queue SET state='QUEUED', attempt_count=0, available_at=?, "
				+ "locked_by=NULL, locked_at=NULL, last_error_code=NULL, last_error_message=NULL, updated_at=? "
				+ "WHERE id=? AND job_type=? AND state='FAILED'", Timestamp.from(now), Timestamp.from(now), jobId,
				JobType.DIRECT_DOWNLOAD.name()) == 1;
	}

	private void assertUpdated(int updated, long jobId) {
		if (updated != 1) throw new IllegalStateException("Direct download job state changed concurrently: " + jobId);
	}

	private String truncate(String value, int limit) {
		if (value == null || value.length() <= limit) return value;
		return value.substring(0, limit);
	}

	private String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}

	private record JobRow(long id, String payload, int attemptCount, int maxAttempts) {
	}
}
