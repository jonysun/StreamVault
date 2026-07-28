# Collection Pipeline Release Runbook

This runbook deploys the split collection fetch/download pipeline safely. It is
written for the Docker web service and SQLite production database. PostgreSQL,
Redis, and historical data rewrites are not part of this release.

## Safety Rules

- Do not run migration commands against the repository copy at `db/spirit.db`.
- Work on a separately named production backup/copy. Keep the original database,
  `spirit.db-wal`, and `spirit.db-shm` untouched until validation is complete.
- Pause collection fetch, collection downloads, and HLS from the admin controls
  before making a consistent database copy. Wait for already running work to
  finish, or record the running items for restart recovery.
- The application startup schema initializer is idempotent. It adds nullable
  pipeline columns and indexes only; it does not rewrite historical media,
  author, or raw JSON rows.
- Keep the previous image tag and database backup available for rollback.

## Preflight And Backup

Run these checks on the production host, after the three work categories are
paused. Replace the paths with the actual mounted production paths. Do not use
these commands with `db/spirit.db` in this repository.

```bash
export PROD_DB=/srv/streamvault/db/spirit.db
export COPY_DIR=/srv/streamvault/release-copies/$(date +%Y%m%d-%H%M%S)
mkdir -p "$COPY_DIR"

cp --reflink=auto "$PROD_DB" "$COPY_DIR/spirit.db"
for suffix in -wal -shm; do
  test ! -e "$PROD_DB$suffix" || cp --reflink=auto "$PROD_DB$suffix" "$COPY_DIR/spirit.db$suffix"
done

sha256sum "$PROD_DB" "$PROD_DB-wal" "$PROD_DB-shm" 2>/dev/null || true
sqlite3 "$COPY_DIR/spirit.db" "PRAGMA quick_check;"
```

Expected `quick_check` output is exactly `ok`. If it is not `ok`, stop the
release and restore from a known-good backup before investigating. Keep the
hashes with the release record.

Inspect the queue before starting the new image:

```bash
sqlite3 "$COPY_DIR/spirit.db" \
  "SELECT process_state, queue_generation, COUNT(*) FROM biz_collect_run_item GROUP BY process_state, queue_generation ORDER BY 1,2;"
sqlite3 "$COPY_DIR/spirit.db" \
  "SELECT state, COUNT(*) FROM biz_collect_run GROUP BY state ORDER BY 1;"
```

Historical rows with no pipeline generation are expected to remain visible as
`queue_generation IS NULL`. They are intentionally inert and are not replayed
by the new download worker.

## Disposable Startup Migration

Start the candidate image with only the disposable copy mounted read/write.
Use the normal production image and profile; the important property is that
`/app/db/spirit.db` points to the disposable copy, never the repository copy.

```bash
docker run --rm --name streamvault-pipeline-check \
  -e SPRING_PROFILES_ACTIVE=docker \
  -v "$COPY_DIR:/app/db" \
  -v /srv/streamvault/media:/app/media \
  -p 28081:28081 \
  streamvault:<candidate-tag>
```

Check startup logs for these outcomes:

```text
Collection pipeline schema initialization completed
SQLite pipeline preflight passed
F2 runtime version=0.0.1.7
Collection download recovery recovered=0 (or the exact stale count)
```

Then verify the copy. The exact index names must be present:

```bash
sqlite3 "$COPY_DIR/spirit.db" "PRAGMA quick_check;"
sqlite3 "$COPY_DIR/spirit.db" \
  "SELECT process_state, queue_generation, COUNT(*) FROM biz_collect_run_item GROUP BY process_state, queue_generation ORDER BY 1,2;"
sqlite3 "$COPY_DIR/spirit.db" "PRAGMA index_list('biz_collect_run_item');"
```

The index list must include:

- `idx_collect_run_item_download_claim`
- `idx_collect_run_item_active_work`
- `idx_collect_run_item_run_state`

Compare representative media/detail/author counts and hashes of selected
non-pipeline columns before and after startup. Existing media paths, works,
authors, and raw JSON must not be rewritten by this migration.

## Small-Task Smoke Test

With the configured six-hour schedule unchanged:

1. Resume fetch only and manually enqueue one small author task.
2. Confirm the fetch run reaches `COMPLETED` after its observed plan is stored.
3. Confirm the independent download queue claims the planned works while a
   second author can begin fetching.
4. Cause one disposable media URL failure and confirm only that item enters
   `RETRY_WAIT`, with `NETWORK_IO` or the more specific root error code and a
   non-empty error detail.
5. Pause downloads and verify fetch can still finish. Pause fetch and verify an
   already queued download can still finish. Pause HLS and verify collection
   state is unaffected.
6. Resume all categories and confirm HLS sees only committed media.

For a second run of the same author, logs must include page cursor, `has_more`,
observed count, and the stop reason. A normal incremental run should stop at
`KNOWN_BOUNDARY` after the known boundary is reached rather than refetching the
historical success count plus the monitor window.

## Abnormal Upstream Checks

Use fixtures or a controlled upstream response, never a production account, to
verify the diagnostic mapping:

| Upstream condition | Expected result |
| --- | --- |
| Empty list and `has_more=0` | Successful fetch, `NO_PUBLIC_WORKS` |
| Deactivated profile | `ACCOUNT_DEACTIVATED`, warning without rapid retry |
| `aweme_list=null` | `WORKS_UNAVAILABLE` with schema diagnostic |
| Three empty pages with `has_more=1` | `EMPTY_PAGINATION` |
| Cookie or verification page | `F2_COOKIE_OR_VERIFY_REQUIRED` and cookie-health alert |
| One media `unexpected end of stream` | One item in `RETRY_WAIT`, root cause retained |

No abnormal case should report `nickname_raw was initialized` as the primary
cause. The first-page response, cookie/risk-control signal, exit code, command
duration, and sanitized upstream preview must remain available in the run log.

## Production Cutover And Rollback

After the disposable copy passes, stop the old container, make one final
consistent backup, and start the candidate image against the production mount.
Do not copy the disposable database over production unless the release owner
has explicitly approved that data operation; normally only the application
image changes and startup applies the idempotent schema additions in place.

After startup, repeat `PRAGMA quick_check`, inspect the queue dashboard, and run
the small-task smoke test. Keep the old image tag and backup hashes recorded.

To roll back application code, stop the candidate container and start the old
image with the production database backup. Do not manually remove the new
nullable columns or indexes; the old application can ignore them, and deleting
them risks unnecessary database locking or data loss. Re-run the preflight
checks before resuming any scheduler.

## Release Record

Record the following with each deployment:

- candidate image tag and Git commit;
- F2 version reported at startup;
- database backup path and SHA-256 hashes;
- `PRAGMA quick_check` output before and after;
- queue state counts before and after;
- stale download items recovered at startup;
- smoke-test task/run IDs and final states;
- any upstream warning or root-cause error details.
