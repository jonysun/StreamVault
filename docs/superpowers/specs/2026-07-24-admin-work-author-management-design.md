# Admin Work And Author Management Design

## Scope

Add administrator-facing metadata editing and deletion to `/admin/index` for both video and
graphic works. Add a destructive author cleanup action to the existing profile drawer. The
author operation removes the author's current local library, author profile, and name history,
and disables direct collection tasks so scheduled collection does not immediately restore the
deleted content.

This work does not introduce author-wide permanent blocking for unseen future works. General
favorites, recommendations, and shared collections remain enabled. Existing works deleted by
these operations are added to the current work block list so those known work IDs are not
downloaded again.

## Chosen Approach

Use one `AdminMediaManagementService` as the ownership boundary for work edits, single-work
deletion, author deletion preview, and author deletion execution.

- Single-work deletion is synchronous because it affects one bounded media directory.
- Author deletion is an asynchronous, idempotent batch operation because an author may own
  hundreds of works and many gigabytes of files.
- Existing video and graphic GET deletion routes remain for compatibility, but the new feed UI
  uses authenticated POST routes and does not duplicate their business logic.
- The existing `WorkMetadataEditService` remains the canonical implementation for limited
  metadata editing. The new UI calls its current endpoint.

## Identity Rules

Every author operation requires a platform and canonical author UID. Douyin authors require an
`MS4...` sec_uid. Nickname, display name, numeric Douyin uid, and user-defined username are
never accepted as the deletion key.

Work matching uses the same canonical identity rules as profile aggregation:

1. Normalize the supplied platform to `platformkey`.
2. Normalize `authoruid` and `secuid` through `AuthorIdentityUtil`.
3. Match video and graphic rows by canonical platform plus canonical UID.
4. Use legacy platform aliases only to find rows that have not yet received `platformkey`.

If no canonical UID is available, preview and execution fail without changing tasks, files, or
database rows.

## API Design

All new mutation routes require the authenticated administrator session already used by
`updateWorkMetadata`.

- `POST /admin/api/updateWorkMetadata`: existing limited-edit route; unchanged envelope.
- `POST /admin/api/deleteWork`: accepts `workType`, positive work `id`, and `blockWork`.
- `POST /admin/api/previewDeleteAuthor`: accepts platform and canonical author UID; returns
  author identity, video count, graphic count, and direct collection tasks that will be disabled.
- `POST /admin/api/deleteAuthor`: accepts the same identity plus an explicit confirmation token;
  returns an author-deletion job ID.
- `GET /admin/api/authorDeletionStatus`: returns phase, processed counts, failed paths, message,
  and terminal state for one job ID.

The server re-reads every work and author row by ID or canonical identity. It never trusts file
paths, counts, author names, or media URLs supplied by the browser.

## Limited Metadata Editing

The feed exposes an edit action for the current video or graphic work. It opens a compact modal
pre-populated from the current feed item. Editable fields mirror `WorkMetadataEditService`:
title, description, author display name, author username, author avatar, author homepage,
author signature, publish time, source URL, video cover URL, tags, privacy, and favorite state.

The modal submits only fields changed by the administrator. Author profile synchronization is
an explicit checkbox and defaults on when an author field changes and a canonical UID exists.
After success, the current feed item and open profile header are patched in place; a full feed
reload is used only when the edited field affects the active filter or ordering.

Manual values continue to be stored in `metadataoverrides`, with editor and edit time, so later
refresh or redownload reapplies them.

## Single-Work Deletion

The fixed right-side global mute action is replaced by a red delete action. The custom player
control bar keeps its inline mute button, so audio control remains available.

The delete confirmation shows work type, title, author, and whether the work will be blocked.
On confirmation the server:

1. Loads the work by type and ID.
2. Adds the work identity to `biz_blocked_work` when `blockWork` is true.
3. Deletes only the validated media directory owned by that row, including its HLS output.
4. Deletes the database row only after filesystem deletion succeeds or the path is already absent.
5. Returns the deleted media key.

The client removes that key from all feed arrays, player-pool state, profile work state, and the
visible playlist, then activates the next available work without reloading the page.

## Author Deletion Workflow

The profile drawer adds a destructive `删除作者` action. A preview dialog must complete before
the final confirmation is enabled. The final dialog displays exact video, graphic, and direct
task counts and requires a second explicit confirmation.

Execution phases are:

1. Revalidate platform and canonical UID and snapshot matching work IDs and direct task IDs.
2. Disable direct author collection tasks such as Douyin `post{sec_uid}` while preserving their
   configuration. Do not disable shared favorites, recommendations, or collection folders.
3. For each current work, add its platform/work ID to the work block list.
4. Delete video and graphic files in bounded batches, then delete the corresponding rows whose
   files were deleted or already absent.
5. Delete name-history rows for every matching profile row.
6. Delete all matching canonical and duplicate legacy profile rows.
7. Publish a terminal summary containing deleted counts, already-absent counts, and failures.

The operation is idempotent. Re-running it skips absent rows and files. Profiles are deleted
last so a partial failure remains discoverable and can be retried from the profile or author
list. One author identity can have only one active deletion job.

## Job State And Failure Handling

Author deletion runs on a dedicated single-worker bounded executor so it cannot compete with
download or HLS worker pools. Job state is held by the service and exposed for polling. A
container restart may lose in-memory progress, but execution remains safe to retry because each
phase re-queries current state and every deletion is idempotent.

Filesystem failure for one work records that work as failed and leaves its database row intact.
The job continues with other works and finishes as `partial_failure`. Database failure is logged
with platform, canonical UID, phase, work type, and row ID; secrets and remote request headers
are never logged.

The UI keeps the profile drawer open while polling, renders processed/total counts, and offers a
retry for partial failures. Successful completion closes the drawer, clears author-filter state,
removes the author's works from the feed, and advances to the next remaining work.

## Compatibility And Safety

- No existing table or column is removed or renamed.
- The feature operates on video and graphic works in the mixed feed.
- Existing collection-task configuration is retained; only `taskenabled` changes to `N` for
  direct author tasks.
- Existing blocked-work semantics are reused for known deleted works.
- File paths must resolve under configured media roots before recursive deletion. Invalid or
  external paths fail closed and leave the row intact.
- Destructive endpoints reject unauthenticated requests, invalid work types, missing IDs,
  noncanonical Douyin UIDs, and stale confirmation tokens.

## Verification

Automated coverage must include:

- editable-field submission and stored override reapplication;
- authenticated and unauthenticated mutation routes;
- video and graphic single deletion, missing files, unsafe paths, and block-list behavior;
- canonical UID matching across `platformkey`, platform aliases, `authoruid`, and `secuid`;
- preview counts and direct-task matching without disabling shared tasks;
- author deletion ordering, profile/history cleanup, partial file failure, idempotent retry, and
  duplicate profile rows;
- feed removal and next-item activation after deleting video and graphic works;
- profile deletion progress and successful return to the all-author feed;
- template JavaScript parsing and duplicate top-level function checks.

The final gate is the complete Maven suite, admin-feed Node tests, template script sanity tests,
and `git diff --check`.
