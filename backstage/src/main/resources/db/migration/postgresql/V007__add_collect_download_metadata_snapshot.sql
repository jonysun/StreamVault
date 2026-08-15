ALTER TABLE biz_collect_run_item
    ADD COLUMN IF NOT EXISTS metadata_snapshot TEXT;
