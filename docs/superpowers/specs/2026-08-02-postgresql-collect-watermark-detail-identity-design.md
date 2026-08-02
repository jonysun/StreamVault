# PostgreSQL collection watermark and detail identity hardening

## Context

Production is already on PostgreSQL and the generated-key row extractor is present in the image. The previous failure moved forward to two PostgreSQL-specific defects:

- Collection fetch watermark updates used bare nullable parameters in SQL, so PostgreSQL could not infer the type of `? IS NOT NULL`.
- `biz_collect_data_detail.id` was created as `INTEGER PRIMARY KEY` in the PostgreSQL baseline, but download completion inserts details without an explicit `id`.

## Design

Use the smallest database-portable changes:

- Replace the watermark SQL nullable-parameter check with an explicit integer flag. Keep the timestamp comparison in SQL so SQLite and PostgreSQL continue to use one transaction path.
- Treat empty stored watermarks as missing by comparing against `NULLIF(TRIM(last_seen_publish_time), '')`.
- Move `CollectDataDetailEntity` from `GenerationType.TABLE` to `GenerationType.IDENTITY` so JPA and native SQL both use the database identity source.
- Keep the already released PostgreSQL `V001` baseline checksum stable. Add `V002` for both existing migrated databases and fresh installs. `V002` adds identity only when missing and resets the identity sequence to the current maximum `id`.
- Add `biz_collect_data_detail` to the SQLite identity preflight so older SQLite schemas must have `id INTEGER PRIMARY KEY` before native identity use.

## Review Scope

The production-code scan covers native inserts, generated ID strategies, PostgreSQL baseline DDL, SQLite preflight tables, and nullable SQL parameter checks. The only native insert that required a new identity fix was `biz_collect_data_detail`; existing run, run item, run event, job queue, maintenance, and author enrichment inserts already target identity-backed tables.

## Verification

- Transaction tests cover watermark advancement from blank stored values and no-op behavior when the incoming watermark is empty.
- Deployment contract tests cover the PostgreSQL baseline, `V002`, and the absence of bare `? IS NOT NULL` checks in main Java sources.
- SQLite preflight tests cover `biz_collect_data_detail` as a native identity table.
- Full backend Maven tests remain the release gate before PR merge.

## Production Rollout

Do not rerun SQLite-to-PostgreSQL load and do not delete the PostgreSQL volume. Build and deploy a new image from the merged main branch. On startup, Flyway applies `V002` to the existing PostgreSQL database, after which download completion can insert `biz_collect_data_detail` rows without manually supplied IDs.
