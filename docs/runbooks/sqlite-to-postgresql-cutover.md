# SQLite to PostgreSQL one-shot cutover

## Hard boundaries

- Never load from the repository copy at `db/spirit.db`; it is read-only audit material.
- All rehearsal and production loads use a stopped-app snapshot created on the production server.
- Never update, vacuum, reindex, or slim the SQLite source in place.
- Slimming is limited to the PostgreSQL target: when `biz_video.jsonData = biz_video.videoinfo`, the target `videoinfo` is stored as `NULL`.
- Keep the stopped SQLite snapshot and the old application Compose settings until the PostgreSQL observation window is complete.

## Compose preparation

Run from the production directory containing `docker/docker-compose.yml` and the existing `./app` volume.

```bash
cp docker/.env.postgresql.example docker/.env.postgresql
chmod 600 docker/.env.postgresql
```

Set a long random `STREAMVAULT_DB_PASSWORD`, the image tag/digest being released, and keep:

```text
SPRING_PROFILES_ACTIVE=docker,postgresql
COMPOSE_PROFILES=postgresql
```

The PostgreSQL service has no host port. Its data is stored in the named volume `stream-vault-postgres-data`.

## Rehearsal 1 and 2

1. Stop only the application and wait for it to exit:

```bash
docker compose -f docker/docker-compose.yml stop -t 120 stream-vault
```

2. Create a timestamped production snapshot outside the active database path:

```bash
mkdir -p ./migration-snapshots
cp --reflink=auto ./app/db/spirit.db ./migration-snapshots/spirit-YYYYMMDD-HHMMSS.db
test ! -e ./app/db/spirit.db-wal || cp --reflink=auto ./app/db/spirit.db-wal ./migration-snapshots/
test ! -e ./app/db/spirit.db-shm || cp --reflink=auto ./app/db/spirit.db-shm ./migration-snapshots/
sha256sum ./migration-snapshots/spirit-YYYYMMDD-HHMMSS.db
```

3. Replace `./app/db/spirit.db` in a separate rehearsal directory with the snapshot. Do not point a rehearsal at the live app directory.

4. Create and validate the PostgreSQL schema:

```bash
COMPOSE_PROFILES=migration docker compose --env-file docker/.env.postgresql \
  -f docker/docker-compose.yml up --abort-on-container-exit schema-migrate
```

5. Run the read-only dry-run:

```bash
STREAMVAULT_MIGRATION_MODE=dry-run COMPOSE_PROFILES=migration \
docker compose --env-file docker/.env.postgresql -f docker/docker-compose.yml \
  run --rm data-migrate
```

6. Load only after dry-run returns `status=ok`:

```bash
STREAMVAULT_MIGRATION_MODE=load STREAMVAULT_MIGRATION_CONFIRM=LOAD \
COMPOSE_PROFILES=migration docker compose --env-file docker/.env.postgresql \
  -f docker/docker-compose.yml run --rm data-migrate
```

7. Verify independently:

```bash
STREAMVAULT_MIGRATION_MODE=verify COMPOSE_PROFILES=migration \
docker compose --env-file docker/.env.postgresql -f docker/docker-compose.yml \
  run --rm data-migrate
```

The verify report must have no missing tables, no row-count mismatch, no remaining exact duplicate video rows, and `status=ok`. Reset only the rehearsal PostgreSQL volume between rehearsal 1 and rehearsal 2.

## Production 120-minute window

| Time | Action |
| --- | --- |
| T-30 | Confirm two successful rehearsals using the release image and record their reports, durations, snapshot hashes, and row counts. |
| T-10 | Pause all collection/download/HLS controls and confirm queues stop changing. |
| T+0 | Stop `stream-vault` with the 120-second grace period. |
| T+5 | Create and hash the final stopped-app SQLite snapshot. This is the rollback source. |
| T+15 | Run `schema-migrate`, then `dry-run`. Stop immediately if either is not `status=ok`. |
| T+25 | Run `load`; keep the SQLite source mounted read-only. |
| T+75 | Run `verify`; archive the JSON report with the release commit and image digest. |
| T+90 | Start one `stream-vault` container with `SPRING_PROFILES_ACTIVE=docker,postgresql`. Keep all pause controls enabled. |
| T+100 | Smoke test login, feed queries, author pages, one manual collection, one download, and runtime/database status. |
| T+110 | Release pause controls one scope at a time and watch errors, queue depth, PostgreSQL locks, disk growth, and process timeouts. |
| T+120 | Declare cutover complete only if the smoke tests and queue progression remain healthy. |

## Rollback

1. Re-enable all pause controls and stop the PostgreSQL-backed application.
2. Restore the previous SQLite application profile and image digest.
3. Restore the final stopped-app snapshot to `./app/db/spirit.db`, including its matching WAL/SHM files if they existed.
4. Start one application container and run SQLite quick/integrity checks before releasing workers.
5. Preserve the PostgreSQL volume for diagnosis; do not merge writes back into SQLite.
