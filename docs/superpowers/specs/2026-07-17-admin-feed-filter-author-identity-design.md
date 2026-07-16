# Admin Feed Filter and Author Identity Design

## Context

The admin media playback page (`/admin/index`) currently serves a merged media feed through
`/admin/api/findMediaFeedList`. The backend merge happens in `MediaFeedService.findPage`, which
always fetches videos and, unless the favorite filter is active, graphics as well. There is no
explicit main-feed switch for "video only", "graphic only", or "mixed".

Author identity is currently partly normalized but still easy to confuse in downstream queries.
`AuthorProfileEntity.authoruid` is intended to be the stable author identity. For Douyin, that
must be the `sec_uid` value beginning with `MS4`. `username` / `uniqueid` is the custom handle and
is not globally stable. `displayname`, `videoauthor`, and graphic `author` are nicknames and can
change over time.

The current author profile service already rejects numeric Douyin ids as canonical author ids, and
Douyin graphic ingestion now upserts author profiles from an author snapshot. The remaining risk is
that profile lookup and work aggregation still use broad OR conditions across uid, username, and
nickname, which can merge unrelated people or fail to reflect a rename consistently.

## Goals

1. Add a clear `/admin/index` feed switch: mixed, video only, graphic only.
2. Make `authoruid` the only normal author identity key when it is available.
3. Treat Douyin `authoruid` as the stable `MS4...` `sec_uid`; never treat the numeric uid as the
   canonical author profile key.
4. Preserve old work rows as ingestion-time snapshots while showing the latest author nickname in
   author-facing UI.
5. Keep nickname history in `AuthorNameHistoryEntity` whenever a changed nickname is observed.
6. Add focused tests so future changes do not reintroduce uid/username/nickname confusion.

## Non-Goals

1. Do not bulk rewrite all old work rows to the latest nickname.
2. Do not change mobile app playback behavior in this pass.
3. Do not introduce a large schema migration or rename existing database columns.
4. Do not solve old rows that have no stable uid except through existing/future repair tools.

## Field Semantics

`authoruid` is the stable author identity. For Douyin, the value must be `sec_uid` and must begin
with `MS4`. If a Douyin work only has a numeric uid, it can be stored in raw JSON, but it must not
create or identify an `AuthorProfileEntity`.

`secuid` is the Douyin-specific copy of the stable uid on work rows. For Douyin rows, `authoruid`
and `secuid` should converge to the same `MS4...` value when metadata is available.

`username`, `authorusername`, and `uniqueid` are display/search handles. They may change, may be
empty, and may collide. They must not be used as the primary grouping key when `authoruid` exists.

`displayname` is the current nickname in the author profile table. `videoauthor` and graphic
`author` are work-level nickname snapshots captured when the work was ingested or repaired.

## Current Author List Data Source

The existing `/admin/authorList` page does not group directly from the video and graphic work
tables at request time. Its current flow is:

1. `authorList.html` posts to `/admin/api/findAuthorProfileList`.
2. `AdminController.findAuthorProfileList` delegates to `AuthorProfileService.findPage`.
3. `AuthorProfileService.findPage` queries `biz_author_profile` through `AuthorProfileDao`.
4. The optional keyword currently searches `displayname`, `username`, and `authoruid`.
5. The page is ordered by `updatetime desc, id desc`.
6. The history button calls `/admin/api/findAuthorNameHistory`, which reads
   `biz_author_name_history` for the selected author profile id.

That means the author list only reflects data that has already been written to
`biz_author_profile`. New or changed author names appear there only when an ingest, analysis,
repair, or rebuild path calls `AuthorProfileService.upsertAuthor` with a stable uid and nickname.
If a work row has a newer nickname but no matching profile upsert happened, the work can look
newer than the author list. This implementation should therefore fix both the display/query
semantics and the profile-upsert coverage.

## Display Rules

Author-level UI shows the current profile nickname:

- Author list: `displayname`, plus name-history popup.
- Admin feed profile drawer: `displayname`.
- Author profile header and stats: `displayname`.
- Author profile work grid: current profile nickname when available.

Work rows preserve their snapshot nickname, but media cards should prefer a display-only current
author name when a stable profile can be resolved. If no stable profile exists, they fall back to
the work snapshot nickname. Diagnostics, raw JSON, and repair logs may still expose the snapshot
name for debugging.

## Feed Filter Design

Frontend:

- Add a compact control on `/admin/index` for Mixed / Video / Graphic.
- Keep the default as Mixed. The rendered UI labels should be Chinese in the page.
- Persist the selection in `localStorage`.
- Include the selected mode in the feed request as `mediaType=mixed|video|graphic`.
- Reload the feed when the mode changes, clearing the current rendered feed state.

Backend:

- Add a transient request field to `VideoDataEntity`, for example `mediaType`.
- `MediaFeedService.findPage` normalizes missing/unknown media type to `mixed`.
- `mixed` keeps the existing merge behavior.
- `video` fetches only video candidates.
- `graphic` fetches only graphic candidates.
- Existing sort, random mode, author filter, and publish-time fallback behavior continue to apply.

## Author Query Design

Profile summary and work listing should use a stricter identity strategy:

1. If a stable `authoruid` is present, query by `authoruid` / `secuid` only, with platform included
   when provided.
2. Only if `authoruid` is missing, fall back to `authorusername` / `uniqueid`.
3. Only if both uid and username are missing, fall back to nickname.

This applies to:

- `findBestProfile`
- `buildVideoAuthorSpec`
- `buildGraphicAuthorSpec`
- Any feed/profile helper that constructs author-context queries

This avoids grouping two authors together because they share a nickname or a custom username, and
it keeps renamed authors grouped correctly when their stable uid is present.

## Current Nickname Enrichment

To show the latest author name without mutating historical work rows, the backend can enrich
`AdminMediaFeedItem` with display-only author profile information. The least invasive option is to
add fields such as `displayAuthor` and `profileAuthorUid`, then set them from `AuthorProfileEntity`
when a stable profile is found. Existing frontend code can use:

1. `displayAuthor`
2. `author`
3. fallback empty label

This keeps old APIs compatible while making the admin feed and profile drawer show the latest name.

## Rename History

Every author ingest/repair path that observes a stable uid and nickname should call
`AuthorProfileService.upsertAuthor`. That method continues to:

- Find the profile by `(platform, authoruid)`.
- Update `username`, `displayname`, avatar, homepage, and update time when new values exist.
- Upsert one history row per distinct nickname and refresh its `lastseentime`.

The implementation should verify that video ingestion, graphic ingestion, analysis ingestion, and
repair paths still call `upsertAuthor` with the canonical uid.

## Testing

Add or update focused tests for:

1. `MediaFeedService`: `mediaType=video` excludes graphics, `mediaType=graphic` excludes videos,
   and missing media type preserves mixed behavior.
2. `AuthorProfileService`: Douyin numeric uid is not promoted to profile identity.
3. `AuthorProfileService`: when `authoruid` is present, work specs do not also match by nickname or
   username.
4. `AuthorProfileService`: a second upsert with the same uid and a new nickname updates current
   `displayname` and writes/refreshes nickname history.
5. Existing author profile works still return videos and graphics for the same stable uid.

## Rollout Notes

This change is backward-compatible with existing rows. Rows with valid `MS4...` uid become more
accurate immediately. Rows that only have numeric uid or only nickname remain fallback cases until
metadata repair/backfill supplies a stable `sec_uid`.

Implementation should be done on a clean branch or with exact-file staging because the current
workspace contains unrelated modifications.
