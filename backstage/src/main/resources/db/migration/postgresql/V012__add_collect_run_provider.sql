ALTER TABLE biz_collect_run
    ADD COLUMN provider_mode VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN provider_path VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN provider_reason VARCHAR(255);

CREATE INDEX idx_collect_run_created
    ON biz_collect_run(created_at DESC, id DESC);
