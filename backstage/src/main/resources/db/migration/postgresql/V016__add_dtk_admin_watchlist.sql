ALTER TABLE biz_config ADD COLUMN IF NOT EXISTS dtk_admin_base_url VARCHAR(512) NOT NULL DEFAULT '';
ALTER TABLE biz_config ADD COLUMN IF NOT EXISTS dtk_admin_api_key VARCHAR(512) NOT NULL DEFAULT '';
ALTER TABLE biz_config ADD COLUMN IF NOT EXISTS dtk_admin_watchlist_sync_enabled VARCHAR(1) NOT NULL DEFAULT '0';

CREATE TABLE IF NOT EXISTS biz_dtk_admin_sync_job (
    id VARCHAR(64) PRIMARY KEY,
    mode VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    scanned_count INTEGER NOT NULL DEFAULT 0,
    added_count INTEGER NOT NULL DEFAULT 0,
    deleted_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    error_domain VARCHAR(32),
    error_code VARCHAR(128),
    error_message TEXT,
    request_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);
