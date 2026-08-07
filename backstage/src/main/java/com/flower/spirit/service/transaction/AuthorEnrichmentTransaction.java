package com.flower.spirit.service.transaction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flower.spirit.service.AuthorEnrichmentClaim;
import com.flower.spirit.service.AuthorEnrichmentEnqueueResult;

@Service
public class AuthorEnrichmentTransaction {

	private static final String ACTIVE_STATES = "'QUEUED','RUNNING','RETRY_WAIT'";

	private final JdbcTemplate jdbcTemplate;

	public AuthorEnrichmentTransaction(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public AuthorEnrichmentEnqueueResult enqueue(String platformKey, String authorUid, int priority,
			boolean promote, Instant now) {
		Timestamp timestamp = Timestamp.from(now);
		int inserted = jdbcTemplate.update("INSERT INTO biz_author_enrichment_job "
				+ "(platform_key, author_uid, state, priority, attempt_count, next_attempt_at, created_at, updated_at) "
				+ "SELECT ?, ?, 'QUEUED', ?, 0, ?, ?, ? WHERE NOT EXISTS ("
				+ "SELECT 1 FROM biz_author_enrichment_job WHERE platform_key = ? AND author_uid = ? "
				+ "AND state IN (" + ACTIVE_STATES + ")) ON CONFLICT DO NOTHING",
				platformKey, authorUid, priority, timestamp, timestamp, timestamp, platformKey, authorUid);
		boolean promoted = false;
		if (promote && inserted == 0) {
			promoted = jdbcTemplate.update("UPDATE biz_author_enrichment_job SET priority = "
					+ "CASE WHEN priority < ? THEN priority ELSE ? END, "
					+ "state = CASE WHEN state = 'RUNNING' THEN state ELSE 'QUEUED' END, "
					+ "next_attempt_at = CASE WHEN state = 'RUNNING' THEN next_attempt_at ELSE ? END, updated_at = ? "
					+ "WHERE platform_key = ? AND author_uid = ? AND state IN (" + ACTIVE_STATES + ")",
					priority, priority, timestamp, timestamp, platformKey, authorUid) > 0;
		}
		boolean promotionApplied = promoted;
		List<AuthorEnrichmentEnqueueResult> rows = jdbcTemplate.query(
				"SELECT id, state FROM biz_author_enrichment_job WHERE platform_key = ? AND author_uid = ? "
						+ "AND state IN (" + ACTIVE_STATES + ") ORDER BY id DESC LIMIT 1",
				(rs, rowNum) -> new AuthorEnrichmentEnqueueResult(rs.getInt("id"), rs.getString("state"),
						inserted > 0, promotionApplied), platformKey, authorUid);
		if (rows.isEmpty()) {
			throw new IllegalStateException("Author enrichment enqueue did not produce an active job");
		}
		return rows.get(0);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public AuthorEnrichmentClaim claimNext(Instant now, long staleLockMinutes) {
		Timestamp timestamp = Timestamp.from(now);
		Timestamp staleBefore = Timestamp.from(now.minus(staleLockMinutes, ChronoUnit.MINUTES));
		jdbcTemplate.update("UPDATE biz_author_enrichment_job SET state = 'RETRY_WAIT', locked_at = NULL, "
				+ "next_attempt_at = ?, last_error_code = 'STALE_LOCK', "
				+ "last_error_message = 'Recovered stale running author enrichment job', updated_at = ? "
				+ "WHERE state = 'RUNNING' AND locked_at < ?", timestamp, timestamp, staleBefore);

		List<AuthorEnrichmentClaim> due = jdbcTemplate.query(
				"SELECT id, platform_key, author_uid, attempt_count FROM biz_author_enrichment_job "
						+ "WHERE state IN ('QUEUED','RETRY_WAIT') AND next_attempt_at <= ? "
						+ "ORDER BY priority ASC, next_attempt_at ASC, id ASC LIMIT 1",
				this::mapClaim, timestamp);
		if (due.isEmpty()) {
			return null;
		}
		AuthorEnrichmentClaim candidate = due.get(0);
		int updated = jdbcTemplate.update("UPDATE biz_author_enrichment_job SET state = 'RUNNING', "
				+ "attempt_count = attempt_count + 1, locked_at = ?, updated_at = ? "
				+ "WHERE id = ? AND state IN ('QUEUED','RETRY_WAIT')",
				timestamp, timestamp, candidate.id());
		return updated == 1 ? new AuthorEnrichmentClaim(candidate.id(), candidate.platformKey(),
				candidate.authorUid(), candidate.attemptCount() + 1) : null;
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public boolean isAuthorPermanentlyUnavailable(String platformKey, String authorUid) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM biz_collect_data WHERE LOWER(COALESCE(platform, '')) IN ('抖音', 'douyin') "
						+ "AND remote_account_state IN ('DEACTIVATED','BANNED') "
						+ "AND LOWER(TRIM(SUBSTR(COALESCE(originaladdress, ''), 1, 4))) = 'post' "
						+ "AND TRIM(SUBSTR(originaladdress, 5)) = ?", Integer.class, authorUid);
		return count != null && count > 0;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void cancel(int jobId, String errorCode, String message, Instant now) {
		updateTerminal(jobId, "CANCELLED", errorCode, message, null, now);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int enqueueMissingWorkAuthors(Instant now, int limit) {
		Timestamp recentCompletion = Timestamp.from(now.minus(30, ChronoUnit.DAYS));
		List<String> authorUids = jdbcTemplate.queryForList("SELECT DISTINCT works.author_uid FROM ("
				+ "SELECT CASE WHEN secuid LIKE 'MS4%' THEN secuid ELSE authoruid END AS author_uid "
				+ "FROM biz_video WHERE (platformkey = 'douyin' OR ((platformkey IS NULL OR trim(platformkey) = '') "
				+ "AND videoplatform IN ('抖音','douyin'))) "
				+ "UNION ALL "
				+ "SELECT CASE WHEN secuid LIKE 'MS4%' THEN secuid ELSE authoruid END AS author_uid "
				+ "FROM biz_graphic_content WHERE (platformkey = 'douyin' OR ((platformkey IS NULL OR trim(platformkey) = '') "
				+ "AND platform IN ('抖音','douyin')))"
				+ ") works LEFT JOIN biz_author_profile profile ON profile.authoruid = works.author_uid "
				+ "AND (profile.platformkey = 'douyin' OR ((profile.platformkey IS NULL OR trim(profile.platformkey) = '') "
				+ "AND profile.platform IN ('抖音','douyin'))) "
				+ "WHERE works.author_uid LIKE 'MS4%' AND (profile.id IS NULL OR trim(coalesce(profile.displayname,'')) = '' "
				+ "OR trim(coalesce(profile.username,'')) = '' OR trim(coalesce(profile.avatar,'')) = '' "
				+ "OR trim(coalesce(profile.signature,'')) = '' OR trim(coalesce(profile.homepage,'')) = '') "
				+ "AND NOT EXISTS (SELECT 1 FROM biz_author_enrichment_job active WHERE active.platform_key = 'douyin' "
				+ "AND active.author_uid = works.author_uid AND active.state IN (" + ACTIVE_STATES + ")) "
				+ "AND NOT EXISTS (SELECT 1 FROM biz_author_enrichment_job recent WHERE recent.platform_key = 'douyin' "
				+ "AND recent.author_uid = works.author_uid AND recent.state = 'COMPLETED' AND recent.updated_at >= ?) "
				+ "AND NOT EXISTS (SELECT 1 FROM biz_collect_data stopped "
				+ "WHERE stopped.remote_account_state IN ('DEACTIVATED','BANNED') "
				+ "AND LOWER(TRIM(SUBSTR(COALESCE(stopped.originaladdress, ''), 1, 4))) = 'post' "
				+ "AND TRIM(SUBSTR(stopped.originaladdress, 5)) = works.author_uid) "
				+ "ORDER BY works.author_uid LIMIT ?", String.class, recentCompletion, Math.max(1, limit));
		int inserted = 0;
		for (String authorUid : authorUids) {
			if (enqueue("douyin", authorUid, 200, false, now).created()) {
				inserted++;
			}
		}
		return inserted;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(int jobId, Instant now) {
		updateTerminal(jobId, "COMPLETED", null, null, null, now);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void retryLater(int jobId, String errorCode, String message, Instant nextAttemptAt, Instant now) {
		updateTerminal(jobId, "RETRY_WAIT", errorCode, message, nextAttemptAt, now);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void fail(int jobId, String errorCode, String message, Instant now) {
		updateTerminal(jobId, "FAILED", errorCode, message, null, now);
	}

	private void updateTerminal(int jobId, String state, String errorCode, String message, Instant nextAttemptAt,
			Instant now) {
		int updated = jdbcTemplate.update("UPDATE biz_author_enrichment_job SET state = ?, next_attempt_at = ?, "
				+ "locked_at = NULL, last_error_code = ?, last_error_message = ?, updated_at = ? "
				+ "WHERE id = ? AND state = 'RUNNING'", state,
				nextAttemptAt == null ? Timestamp.from(now) : Timestamp.from(nextAttemptAt), errorCode,
				truncate(message, 2000), Timestamp.from(now), jobId);
		if (updated != 1) {
			throw new IllegalStateException("Author enrichment job is no longer running: " + jobId);
		}
	}

	private AuthorEnrichmentClaim mapClaim(ResultSet rs, int rowNum) throws SQLException {
		return new AuthorEnrichmentClaim(rs.getInt("id"), rs.getString("platform_key"),
				rs.getString("author_uid"), rs.getInt("attempt_count"));
	}

	private String truncate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}
}
