package com.flower.spirit.service.transaction;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DouyinCookieRiskTransaction {

	private final JdbcTemplate jdbcTemplate;

	public DouyinCookieRiskTransaction(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void initializeSchema() {
		jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS biz_douyin_cookie_risk ("
				+ "cookie_fingerprint VARCHAR(12) PRIMARY KEY, consecutive_soft_blocks INTEGER NOT NULL DEFAULT 0, "
				+ "cooldown_until TIMESTAMP NULL, updated_at TIMESTAMP NOT NULL)");
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public Map<String, RiskState> load() {
		Map<String, RiskState> result = new LinkedHashMap<>();
		jdbcTemplate.query("SELECT cookie_fingerprint, consecutive_soft_blocks, cooldown_until "
				+ "FROM biz_douyin_cookie_risk", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
					while (rs.next()) result.put(rs.getString(1), new RiskState(rs.getInt(2),
							rs.getTimestamp(3) == null ? 0L : rs.getTimestamp(3).getTime()));
					return null;
				});
		return result;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int recordSoftBlock(String fingerprint, Instant now, Instant cooldownUntil) {
		jdbcTemplate.update("INSERT INTO biz_douyin_cookie_risk "
				+ "(cookie_fingerprint, consecutive_soft_blocks, cooldown_until, updated_at) VALUES (?, 1, NULL, ?) "
				+ "ON CONFLICT(cookie_fingerprint) DO UPDATE SET consecutive_soft_blocks = CASE "
				+ "WHEN cooldown_until IS NOT NULL AND cooldown_until <= excluded.updated_at THEN 1 "
				+ "ELSE consecutive_soft_blocks + 1 END, "
				+ "cooldown_until = CASE WHEN cooldown_until IS NOT NULL "
				+ "AND cooldown_until <= excluded.updated_at THEN NULL "
				+ "WHEN consecutive_soft_blocks + 1 >= 2 THEN ? "
				+ "ELSE cooldown_until END, updated_at = excluded.updated_at",
				fingerprint, Timestamp.from(now), Timestamp.from(cooldownUntil));
		return jdbcTemplate.queryForObject("SELECT consecutive_soft_blocks FROM biz_douyin_cookie_risk "
				+ "WHERE cookie_fingerprint = ?", Integer.class, fingerprint);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordSuccess(String fingerprint, Instant now) {
		jdbcTemplate.update("UPDATE biz_douyin_cookie_risk SET consecutive_soft_blocks = 0, updated_at = ? "
				+ "WHERE cookie_fingerprint = ?", Timestamp.from(now), fingerprint);
	}

	public record RiskState(int consecutiveSoftBlocks, long cooldownUntilEpochMillis) {
	}
}
