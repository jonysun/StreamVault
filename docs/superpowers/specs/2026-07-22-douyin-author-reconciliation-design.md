# Douyin Author Reconciliation Design

Date: 2026-07-22

## Goal

Restore reliable author Profile actions in `/admin/index` and make the author list converge on one record per canonical Douyin `sec_uid`.

The change must:

- recover canonical identity already present in stored work JSON;
- fill the current public `unique_id` when the upstream data contains it;
- merge duplicate and legacy numeric author profiles without losing name history;
- keep future collection runs consistent, including runs that skip already-downloaded works;
- preserve the lightweight media feed and avoid loading large JSON fields during normal playback requests;
- update the production database only through idempotent application operations, never by editing the checked-in database copy.

## Confirmed Root Causes

The synchronized project database demonstrates that the data exists but is not normalized:

- 15,002 Douyin video rows exist, but only 9 have a structured `MS4...` UID in the structured columns.
- 14,940 video `jsonData` values contain a canonical `sec_uid`.
- 5,737 graphic rows exist; 5,102 have a structured canonical UID.
- Author profiles are split between 135 canonical `MS4...` records and 64 legacy numeric or invalid records.

The regression has four causes:

1. Identity validation correctly rejects numeric Douyin UID values, but most legacy works were never migrated from raw JSON into structured columns.
2. `/admin/index` suppresses the complete author action when a canonical UID is absent from the lightweight response, so the migration gap became a visible missing-avatar regression.
3. The existing repair method uses unpaged `findAll()` calls over rows containing large JSON fields, is manually triggered, and does not provide safe background convergence.
4. Collection processing skips author reconciliation when a work already exists, so recurring author fetches do not repair old work rows or collect later nickname changes.

The feed enrichment path also applies only the profile display name and homepage. It does not fill a missing work avatar or username from the canonical profile.

## Canonical Contract

The existing field contract remains unchanged:

| Field | Douyin meaning |
| --- | --- |
| `authoruid`, `secuid` | Canonical `sec_uid`, beginning with `MS4` |
| `authorusername`, `uniqueid` | Current public `unique_id`; blank only if the upstream profile genuinely omits it |
| Work `author` / `videoauthor` | Nickname snapshot observed for that work |
| Profile `displayname` | Most recently observed nickname |
| Name history | Every distinct nickname observed for the canonical UID |
| Numeric `uid` | Raw JSON only; never an aggregation key or Profile URL component |

Non-Douyin platforms retain their current identity behavior.

## Reconciliation Architecture

### 1. Explicit Bounded Background Repair

Add an idempotent Douyin reconciliation job that can only be started from an explicit administrator maintenance action. Application startup must never scan or mutate media and author rows automatically.

The job processes videos and graphics in stable ID-ordered pages. Each page is handled and committed independently so memory use is bounded and a restart can safely repeat completed work. It must never call `findAll()` for media rows containing `jsonData`.

The author-list page provides a read-only impact preview, an explicit confirmation step, a background start action and a status/progress view. The preview runs SQLite `PRAGMA quick_check(1)` first; a failed integrity check disables the repair action and performs no writes. A single-flight guard allows only one reconciliation run to mutate author data at a time. An interrupted run may be started again safely because every repair operation is idempotent and every page is committed independently. No migration state columns are added to the normal configuration table.

For each work, resolution order is:

1. canonical structured `authoruid/secuid`;
2. the work's own stored JSON (`aweme_detail.author`, `author`, or an equivalent profile object);
3. an upstream profile request keyed only by canonical `sec_uid`, and only when locally stored data is incomplete.

The migration writes a work only when it improves canonical identity or author metadata. Existing nonblank values are not cleared after parse or network failure. API results are cached by canonical UID for the duration of a run.

Runtime status reports running/completed/failed status and counters for scanned, locally resolved, API-enriched, updated, merged and unresolved records. A failed or interrupted run remains retryable from the administrator entry.

### 2. Canonical Profile Merge

Profile reconciliation groups author data by platform key plus canonical UID. Before removing any duplicate or legacy record it must:

- persist the canonical profile;
- prefer metadata observed in the current fetch; otherwise use the nonblank value from the profile with the latest update time, then fall back to older records;
- copy the legacy profile's current display name into canonical name history;
- merge all legacy history rows while preserving earliest `firstSeen` and latest `lastSeen` timestamps;
- add every distinct nickname snapshot found on works with that canonical UID;
- re-associate all future lookups with the canonical profile.

Only profiles that can be tied to a canonical UID through their own work JSON or structured work identity may be merged. Unresolved legacy rows remain untouched and are reported.

Application upsert logic must use the canonical platform key and UID lookup consistently. Duplicate lookup results must be consolidated deterministically rather than relying on an `Optional` query that assumes uniqueness before cleanup.

### 3. Continuous Collection Reconciliation

Each successful Douyin author/list response performs one profile upsert for the task author. Each fetched work also reconciles its author fields before the download deduplication branch returns.

Therefore an already-downloaded work can receive:

- canonical `sec_uid` in both UID columns;
- current `unique_id` in both username columns;
- its observed nickname snapshot and avatar when missing;
- a canonical author profile upsert and name-history observation.

This reconciliation performs no media download and does not change the existing work snapshot nickname merely because the profile's current nickname changed. The current name belongs to the profile; historical work names remain snapshots.

The compact fetch snapshot includes `unique_id` for diagnostics, but it is not a source of truth and is not used for author aggregation.

## Feed And Profile Behavior

The lightweight video and graphic projections continue to select structured identity columns only. They must not add `jsonData` to feed queries.

Feed author enrichment uses canonical profile data to fill response-only fields in one bounded batch per page:

- current display name;
- canonical profile UID;
- public username;
- avatar;
- homepage.

This does not overwrite stored work snapshots. It prevents a blank work avatar from producing inconsistent UI when the profile already has a valid avatar.

For public Douyin works, the author action remains present. A canonical UID makes it clickable and opens the canonical Profile. During migration, a row not yet reconciled may show a disabled initial/avatar placeholder with an explicit pending-reconciliation title; it must never use numeric UID or nickname guessing to open a Profile. Private works retain the existing privacy behavior.

After reconciliation, all locally resolvable Douyin rows expose their canonical UID and the normal clickable Profile action.

## Performance And Failure Handling

- Startup never starts reconciliation and is not blocked by it.
- Media rows are read in bounded pages and page persistence uses bounded transactions.
- Normal feed requests never parse large work JSON.
- Profile enrichment avoids per-item queries by loading required canonical profiles in a bounded batch.
- Upstream author API calls happen only for fields unavailable locally and are cached by UID.
- Parse errors and API failures retain existing values, increment diagnostics and allow later retries.
- Concurrent collection and migration updates are monotonic: nonblank canonical values win, and no path clears valid identity.
- The checked-in database remains read-only during development verification.
- Production database mutations are available only through clearly labelled administrator maintenance operations with preview and confirmation.

## Validation

Automated tests must cover:

- extraction of canonical UID, username, nickname and avatar from each supported stored JSON shape;
- paged reconciliation without unbounded `findAll()` media reads;
- no upstream call when local JSON already contains complete author identity;
- API fallback only for incomplete local data;
- merging numeric and duplicate profiles with complete name-history preservation;
- collecting multiple work nickname snapshots under one canonical UID;
- reconciliation of already-existing works in the collection dedup path;
- lightweight feed enrichment of username and avatar from the profile;
- consistent author action rendering for canonical and pending-reconciliation rows;
- no numeric Douyin UID in Profile lookup, aggregation or generated links;
- idempotence when the reconciliation job runs twice.

Run the full Maven suite, admin feed JavaScript tests, inline template syntax check and `git diff --check`.

## Acceptance Criteria

- Every locally resolvable Douyin work has the same canonical `MS4...` value in `authoruid` and `secuid` after migration.
- Every available upstream `unique_id` is stored in `authorusername` and `uniqueid` and exposed in author/profile views.
- One canonical author list row represents one platform-key plus UID pair.
- All observed nicknames for that UID appear in its name history without changing historical work snapshots.
- `/admin/index` no longer loses the author action because legacy structured columns were not migrated.
- Normal feed latency and payload size do not regress through large JSON reads or per-item author queries.
- Migration is restart-safe, bounded in memory and does not delete unresolved data.
