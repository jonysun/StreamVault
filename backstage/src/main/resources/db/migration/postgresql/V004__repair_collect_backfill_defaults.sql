UPDATE biz_collect_data
SET backfill_complete = COALESCE(backfill_complete, 0),
    backfill_verifying = COALESCE(backfill_verifying, 0),
    backfill_clean_passes = COALESCE(backfill_clean_passes, 0)
WHERE backfill_complete IS NULL
   OR backfill_verifying IS NULL
   OR backfill_clean_passes IS NULL;

ALTER TABLE biz_collect_data
    ALTER COLUMN backfill_complete SET DEFAULT 0,
    ALTER COLUMN backfill_complete SET NOT NULL,
    ALTER COLUMN backfill_verifying SET DEFAULT 0,
    ALTER COLUMN backfill_verifying SET NOT NULL,
    ALTER COLUMN backfill_clean_passes SET DEFAULT 0,
    ALTER COLUMN backfill_clean_passes SET NOT NULL;
