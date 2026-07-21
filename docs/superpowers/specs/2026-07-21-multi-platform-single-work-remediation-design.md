# Multi-Platform Single-Work Remediation Design

## Scope

This remediation closes the review findings in Tasks 1-18 without replacing the existing
database tables, public API envelopes, legacy platform collectors, or rollout mechanism.
Audio-only works remain unsupported. DASH audio tracks may exist only as temporary inputs to
produce a final video file.

All platform rollout flags remain `legacy` until the relevant live matrix row passes. The work
is split into independently testable phases so a platform cannot be enabled while shared
persistence is still unsafe.

## Chosen Approach

Use the current normalized `WorkMetadata` and adapter SPI, but repair the shared boundaries
instead of adding a second persistence framework.

1. Introduce one path-mapping service that converts an approved local media path into its
   existing `/cos/...` public path. `videoaddr` and `markroute` retain local filesystem
   semantics; `videounrealaddr` and `images` retain browser-facing semantics.
2. Make filesystem replacement a two-phase operation. Keep the backup until database work has
   committed, restore it on every pre-commit failure, and retain non-replaced sidecar files.
3. Perform blocked-work and duplicate checks before downloading. Re-check inside persistence
   to protect against races without adding unique indexes or changing old duplicate data.
4. Keep platform-specific post-processing behind small hooks for cover, NFO, danmaku, HLS,
   notifications, and history. A hook runs only when the platform supports it.
5. Treat Aria2 as an explicit asynchronous workflow with a completion callback. A queued result
   cannot be promoted or persisted until the callback verifies downloaded files.
6. Repair Bilibili BV requests, multi-part dispatch, DURL segment handling, and existing
   NFO/danmaku behavior before its flag can be enabled.
7. Replace inline HTML/JavaScript interpolation in the direct-data page with DOM construction
   and event listeners. Return and render the full canonical preview metadata.
8. Record failed, rejected, paused, queued, and completed tasks as distinct states. API
   submission status must match whether the executor accepted the task.
9. Sanitize stored raw metadata recursively and keep signed URLs or authorization material out
   of logs and persisted diagnostic JSON.

## Data And Compatibility Rules

- No existing column is removed, renamed, or assigned a new meaning.
- `biz_video.videoaddr`: absolute or configured local media path.
- `biz_video.videounrealaddr`: public `/cos/...` path.
- `biz_graphic_content.markroute`: local work directory.
- `biz_graphic_content.images`: ordered public media paths.
- `videocover`: stable downloaded public cover path when a cover was downloaded; otherwise an
  explicitly validated remote URL may be retained as a fallback.
- Canonical additions (`platformkey`, `contenttype`, author homepage, overrides) remain nullable
  for old rows.
- Manual overrides continue to win after refresh and redownload. Editable author username,
  signature, and cover URL are added only where they can be represented without changing work
  identity.
- Existing Douyin and Bilibili collection paths stay on legacy implementations.

## File Replacement Protocol

For a new work, download to a staging directory, verify every declared file, persist the row,
and expose the final directory only after successful persistence.

For replacement, create a candidate directory from the existing directory so sidecars survive,
replace only declared media files, verify the candidate, move the original to a backup, and
move the candidate into place. Database updates then run in a transaction. The backup is
deleted only after transaction commit. Any earlier failure restores the original without first
deleting it unless a valid backup exists.

HLS rebuild and external notification are after-commit actions. A failed database transaction
cannot enqueue work or announce success.

## Aria2 Workflow

Adapters that honor the existing Aria2 download mode return a queue identity and intended
staging directory. A coordinator records `QUEUED` and performs no persistence. The existing
Aria2 completion observation path calls a shared finalizer that verifies files, applies the same
replacement protocol, persists metadata, and runs after-commit hooks. Failure leaves existing
media untouched and records `FAILED`.

If the current Aria2 integration cannot provide a reliable completion identity, the affected
platform remains on `legacy`; synchronous fallback is not silently substituted.

## Platform Corrections

- Bilibili: send the full `BV...` value as `bvid`; submit every selected/default part as one
  cid-based work; preserve every DURL segment or merge it; restore cover, NFO, danmaku, member
  quality, and filename behavior.
- Douyin: preserve f2 diagnostics, cookie-risk reporting, filename templates, cover/NFO, and
  HTTP/Aria2 modes. Preview remains side-effect free.
- Kuaishou, Xiaohongshu, Weibo, YouTube, Twitter/X, Instagram, TikTok, and generic yt-dlp:
  retain their current normalized mappings, then validate them against real works before
  rollout.

## Security And Error Handling

External metadata is text, never trusted markup. Media URLs must be HTTP(S) before display or
download. UI code assigns text with `textContent`, attributes with DOM APIs, and actions through
event listeners.

Raw metadata sanitization removes cookies, authorization headers, tokens, signed query values,
and request-header collections recursively. Logs contain platform, stage, extractor, work ID,
HTTP status, and bounded safe error text only.

Filesystem cleanup failures are warnings and retain recoverable backups. They do not convert a
successful database transaction into an unreported partial failure.

## Verification

Each phase starts with a failing regression test for its reviewed defect. Required automated
coverage includes public path mapping, duplicate-before-download, persistence failure, commit
failure, first-move failure, sidecar preservation, Aria2 queued completion, Bilibili BV/multi-P/
DURL, task status, preview fields, raw-metadata sanitization, and DOM injection fixtures.

The final gate is the complete Maven suite, an idempotent migration run on a database copy, and
one isolated live work per promised platform/content type. Douyin and Bilibili collection
regressions and existing Web, UniApp, native, desktop, extension, and API consumers must pass
before any individual flag changes to `new`.

## Rollout And Stop Conditions

Rollout is platform-by-platform and defaults remain `legacy`. Stop and report instead of
enabling a platform when any of these occur:

- a failed ingest or redownload changes existing media;
- a browser-facing field contains an unusable local path;
- a legacy sidecar or collection behavior is lost;
- queued downloads cannot be correlated with verified completion;
- runtime logs or stored raw metadata contain credentials or signed URLs;
- a real BV, multi-part Bilibili work, or promised content type fails its live matrix row.
