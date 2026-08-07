ALTER TABLE biz_collect_data
    ADD COLUMN IF NOT EXISTS backfill_cursor VARCHAR(64),
    ADD COLUMN IF NOT EXISTS backfill_complete INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS backfill_source_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS backfill_verifying INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS backfill_clean_passes INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS backfill_verified_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS remote_account_state VARCHAR(32),
    ADD COLUMN IF NOT EXISTS remote_account_reason VARCHAR(255),
    ADD COLUMN IF NOT EXISTS remote_account_detected_at TIMESTAMP;
