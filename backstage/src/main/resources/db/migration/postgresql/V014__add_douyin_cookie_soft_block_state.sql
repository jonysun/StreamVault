CREATE TABLE IF NOT EXISTS biz_douyin_cookie_risk (
    cookie_fingerprint VARCHAR(12) PRIMARY KEY,
    consecutive_soft_blocks INTEGER NOT NULL DEFAULT 0,
    cooldown_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_douyin_cookie_risk_cooldown
    ON biz_douyin_cookie_risk(cooldown_until);
