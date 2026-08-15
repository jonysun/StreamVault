# Douyin List-Snapshot Download Implementation Plan

## Success Criteria

- Newly fetched collection works can parse and download without calling the F2
  single-work detail endpoint when their list snapshot is complete.
- Existing queue rows and incomplete snapshots still use the current detail
  request, classification, and cooldown path.
- The persisted snapshot contains only explicitly allowlisted work, author, and
  media fields and never contains Cookie or signature values.
- PostgreSQL migration is forward-only, nullable, and automatically applied by
  the normal application startup.

## Steps

1. Add a Python helper that converts a raw list work into a compact,
   `aweme_detail`-compatible snapshot and attach it to paginator results. Add
   video, graphic, mixed-item, allowlist, and malformed-field tests.
2. Add PostgreSQL migration V007 for nullable
   `biz_collect_run_item.metadata_snapshot` and extend the deployment contract.
3. Extend `CollectRunFetchedItem`, queue insertion, download claiming, and
   `CollectDownloadClaim` to carry the snapshot while retaining convenience
   constructors for existing callers and tests.
4. Extend `WorkParseRequest` and `WorkIngestService` with an optional raw
   metadata argument. Existing call sites keep their current behavior.
5. Make `DouyinPlatformAdapter` validate and parse a supplied list snapshot
   first, then fall back to the current F2 detail flow on missing, mismatched,
   or invalid snapshot data.
6. Pass the claimed snapshot from `CollectDownloadService` into ingest and add
   tests proving snapshot-first and legacy fallback behavior.
7. Retain the corrected per-attempt F2 evidence classification and compact
   attempt history in logs.
8. Run targeted Python and Java tests, the full Maven suite, compile, diff and
   secret scans, then commit, push, create a PR, and merge after checks permit.
