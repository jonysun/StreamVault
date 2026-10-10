CREATE TABLE IF NOT EXISTS biz_douyin_global_risk (
    singleton_id INTEGER PRIMARY KEY,
    risk_started_at TIMESTAMPTZ,
    detail_started_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
