# Production Database Read-Only Audit

Date: 2026-07-25

Source: `db/spirit.db`

Mode: SQLite CLI `-readonly`; no schema or data changes were executed.

## File

- Database file size: 2,038,558,720 bytes (about 1.90 GiB).
- WAL size at audit time: 0 bytes.

## Video raw payload columns

| Metric | Value |
|---|---:|
| Video rows | 15,469 |
| Rows with `jsonData` | 15,469 |
| Rows with `videoinfo` | 13,174 |
| Rows where both values are exactly equal | 13,174 |
| Rows where both values differ | 0 |
| `jsonData` logical characters | 886,953,901 |
| `videoinfo` logical characters | 762,175,487 |

The production copy contains no known row where the two populated raw payload columns differ. Clearing only rows guarded by `jsonData = videoinfo` can therefore remove about 762 million redundant logical characters without removing the canonical `jsonData` payload.

## Other large text

| Metric | Value |
|---|---:|
| Graphic rows | 5,767 |
| Graphic `jsonData` logical characters | 207,938,131 |
| Maximum graphic payload | 504,985 characters |
| Collection tasks | 149 |
| Fetch snapshot logical characters | 11,146,644 |
| Maximum fetch snapshot | 499,819 characters |
| Fetch snapshots at/above old 200,000 limit | 15 |
| Plan snapshot logical characters | 20,452,487 |
| Maximum plan snapshot | 1,136,761 characters |

## Physical allocation (`dbstat`)

| Object | Bytes |
|---|---:|
| `biz_video` | 1,699,274,752 |
| `biz_graphic_content` | 225,230,848 |
| `biz_collect_data_detail` | 69,844,992 |
| `biz_collect_data` | 35,860,480 |

The video table accounts for most of the database. The duplicate `videoinfo` payload is the largest immediately removable logical duplication. Collection snapshots are a real defect and query cost, but not the primary source of the 1.9 GiB file.

## Operational conclusion

1. Deploy code that stops all new `videoinfo` writes before historical cleanup.
2. Run `GET /admin/api/database/audit` and create a maintenance preview against the live database.
3. Enable maintenance apply only for the maintenance window and pause all background work.
4. Apply `CLEAR_EXACT_DUPLICATE_VIDEOINFO` in small resumable batches.
5. Re-run the audit and verify zero populated duplicate rows and zero differing rows removed.
6. Online cleanup creates reusable SQLite pages; it does not guarantee a smaller file.
7. To physically shrink the file, stop the service, back up the database, run `VACUUM INTO` to a separate file with sufficient free disk, validate it, then replace the original.
