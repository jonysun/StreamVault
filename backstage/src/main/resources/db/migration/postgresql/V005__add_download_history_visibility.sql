CREATE TABLE biz_download_history_hidden (
    record_key VARCHAR(80) PRIMARY KEY,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    hidden_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_download_history_hidden_source
    ON biz_download_history_hidden(source_type, source_id);
