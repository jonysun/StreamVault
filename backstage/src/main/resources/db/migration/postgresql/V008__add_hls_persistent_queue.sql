CREATE TABLE IF NOT EXISTS biz_hls_queue (
    video_id INTEGER PRIMARY KEY,
    state VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    available_at TIMESTAMP,
    locked_by VARCHAR(160),
    locked_at TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_hls_queue_ready
    ON biz_hls_queue (state, available_at, created_at, video_id);

CREATE INDEX IF NOT EXISTS idx_hls_queue_lease
    ON biz_hls_queue (state, locked_at);
