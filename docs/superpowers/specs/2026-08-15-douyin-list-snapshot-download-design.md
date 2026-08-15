# Douyin List-Snapshot Download Design

## Problem

Production evidence shows that the F2 single-work detail endpoint can return
HTTP 200 with an empty body on every attempt. The five-minute soft backoff
prevents a request storm, but cannot make that endpoint usable. Collection
list requests still return work entries, yet the current normalizer discards
their playable media fields and persists only identity and display metadata.
Every collection download must therefore call the blocked detail endpoint
again.

## Decision

Collection downloads will prefer a compact metadata snapshot captured from the
author-list response. A forward-only PostgreSQL migration adds nullable
`metadata_snapshot TEXT` to `biz_collect_run_item`. Null preserves all existing
queue rows and rollback compatibility at the application level.

The snapshot is not a copy of the full upstream response. Python builds an
`aweme_detail`-compatible object containing only fields required by the current
Douyin parser and downloader:

- work ID, description, and publish time;
- author identifiers, nickname, avatar URL list, and signature;
- video play URL list, cover URL list, and original-cover URL list;
- image URL lists and nested mixed-item video play addresses.

Cookie values, request signatures, request URLs, diagnostics, and unrelated
upstream fields are never stored in the snapshot.

## Data Flow

1. The incremental paginator validates each raw list work as it does today.
2. It emits the existing normalized item plus a compact `download_snapshot`.
3. Java serializes that object into the matching persistent run item.
4. The download claim carries the nullable snapshot to `WorkIngestService`.
5. `DouyinPlatformAdapter` parses a valid snapshot without calling F2.
6. If the snapshot is absent or cannot produce valid metadata, the adapter uses
   the existing single-work detail request and existing cooldown behavior.
7. Media download continues to use the currently selected Douyin Cookie and
   existing request headers. A failed or expired media URL remains a normal
   retryable download failure; the next collection fetch can enqueue a fresh
   snapshot when the item is eligible for repair.

The download worker remains serial. The change removes one detail request for
each newly fetched collection work when the list response is sufficient.

## Compatibility and Failure Handling

- Existing rows have a null snapshot and continue through the old detail path.
- Non-Douyin adapters ignore the optional parse snapshot.
- Snapshot work ID must match the claimed work ID; mismatches are rejected and
  fall back to the remote detail path.
- Snapshot parsing does not report Cookie success because no metadata request
  occurred. A completed media download still records success through the
  existing adapter behavior.
- Strong 401/403/429 cooldown and five-minute soft backoff are unchanged for
  actual F2 requests.
- The triggering F2 failure remains visible in queue history; later gated work
  remains deferred without consuming an attempt.

## Alternatives Considered

1. Re-fetch the author page for every download. Rejected because it increases
   request volume and still couples download progress to pagination order.
2. Change user-agent, signature parameters, or use an undocumented alternate
   detail endpoint. Rejected as the primary solution because it is brittle and
   cannot guarantee continued compatibility with F2 0.0.1.7.
3. Persist the complete raw work response. Rejected because it duplicates
   unnecessary data and can retain unrelated signed or identifying fields.

## Verification

- Python tests cover compact video, graphic, and mixed snapshots and ensure
  unrelated fields and Cookie-like values are excluded.
- PostgreSQL contract tests cover the forward-only nullable column migration.
- Queue transaction tests cover snapshot persistence and legacy null rows.
- Adapter and ingest tests prove a valid snapshot avoids the F2 gateway and an
  invalid or absent snapshot falls back to it.
- Download service tests prove the snapshot travels from claim to ingest while
  preserving retry, deduplication, and output-directory behavior.
- Existing incremental pagination, F2 diagnostics, and full Java tests remain
  regression gates.
