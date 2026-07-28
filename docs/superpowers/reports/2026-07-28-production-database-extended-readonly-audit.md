# Production Database Extended Read-Only Audit

Date: 2026-07-28

Source: `db/spirit.db`

Mode: SQLite CLI `-readonly` with `PRAGMA query_only=ON`; no schema or data changes were executed.

## Integrity And File State

- `PRAGMA quick_check`: `ok`.
- Database file size: 2,038,558,720 bytes (about 1.90 GiB).
- WAL size at audit time: 0 bytes.
- Page size: 4,096 bytes.
- Page count: 497,695.
- Freelist pages: 462 (1,892,352 reusable bytes).

## Raw Payload And Snapshot Storage

| Metric | Value |
| --- | ---: |
| Video rows | 15,469 |
| Rows with `jsonData` | 15,469 |
| Rows with `videoinfo` | 13,174 |
| Rows where both values are exactly equal | 13,174 |
| Rows where both values differ | 0 |
| Rows with only `jsonData` | 2,295 |
| Rows with only `videoinfo` | 0 |
| Rows with neither raw payload | 0 |
| Exact duplicate `videoinfo` logical characters | 762,175,487 |
| Graphic rows | 5,767 |
| Graphic `jsonData` logical characters | 207,938,131 |
| Collection tasks | 149 |
| Fetch snapshot logical characters | 11,146,644 |
| Plan snapshot logical characters | 20,452,487 |

These values exactly match the 2026-07-25 audit. The source copy is stable, and
`CLEAR_EXACT_DUPLICATE_VIDEOINFO` has an unambiguous 13,174-row cleanup boundary.

## Canonical Identity And Integrity

| Metric | Value |
| --- | ---: |
| Duplicate video `(platformkey, videoid)` groups | 0 |
| Duplicate graphic `(platformkey, videoid)` groups | 0 |
| Media-reference conflict groups | 0 |
| Video rows missing `platformkey` | 0 |
| Graphic rows missing `platformkey` | 0 |
| Author rows missing `platformkey` | 0 |
| Author-name history rows without a profile | 0 |
| Collection details without a collection task | 0 |

The copy predates the persistent collection runtime tables (`biz_collect_run`,
`biz_collect_run_item`, `biz_collect_run_event`, and `biz_job_queue`), so runtime
orphan and retention counts are zero by optional-table rules.

No work-level merge, deletion, or new uniqueness constraint is justified by this
copy. PostgreSQL migration can use the canonical platform/work identity without a
pre-migration duplicate merge for these media rows.

## Physical Allocation

| Object | Bytes |
| --- | ---: |
| `biz_video` | 1,699,274,752 |
| `biz_graphic_content` | 225,230,848 |
| `biz_collect_data_detail` | 69,844,992 |
| `biz_collect_data` | 35,860,480 |

## Repacked File Warning

`.tmp/db-analysis/spirit_repacked.db` is not a verified compacted backup. It has
an adjacent rollback journal, fails a normal read-only open with an attempted
recovery write, and fails immutable inspection. It must not be used for cutover,
rollback, or size-reduction claims.

## Decision

1. Keep work deduplication report-only; there are no confirmed duplicate groups.
2. Use the existing preview/apply guard to clear only exact duplicate `videoinfo`.
3. Complete retention maintenance for runtime item, event, run, and job history.
4. Keep physical compaction as a separate stopped-service `VACUUM INTO` procedure.
5. Re-audit the live database immediately before any apply or PostgreSQL dry-run.
