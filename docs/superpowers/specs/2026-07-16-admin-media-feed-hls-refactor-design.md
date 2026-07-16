# Admin Media Feed and HLS Refactor Design

Date: 2026-07-16

## Scope

Refactor the `/admin/index` feed-mode implementation and the HLS transcode scheduler while preserving the existing grid mode, APIs, visual layout, and deployment model. The refactor includes all fixes from the smaller approaches: non-blocking HLS work, responsive HLS status, stable video startup without a black flash, incremental author-profile rendering, direct profile-work navigation, and the Thymeleaf list-page compatibility fix.

The implementation will not introduce a frontend framework, bundler, WebSocket service, database migration, or mobile-app behavior change.

## Goals

1. HLS transcoding must not block `/admin/index`, media-list APIs, or `/admin/api/hlsStats`.
2. The HLS status card must continue updating while jobs run and show all running work IDs.
3. Moving to an adjacent video must reuse its preloaded media element and buffered data.
4. A video must never expose an empty black frame between its cover and its first decoded frame.
5. Author-profile works must appear page by page while the remaining pages load automatically.
6. Clicking any visible profile work must immediately open that work in an author-scoped feed; later pages must continue loading as the user approaches the end.
7. `/admin/videoDataList`, `/admin/graphicContentList`, and `/admin/index` must render under Thymeleaf 3.1 without static-class access from templates.

## Architecture

### Server-side HLS scheduler

`HlsTranscodeService` will separate queue coordination from long-running FFmpeg execution.

- A short synchronized section reserves queued IDs and increments running state.
- Reserved jobs run on a dedicated executor and never hold the queue monitor while FFmpeg is active.
- Effective parallelism continues to follow `Global.hlsConcurrency`.
- Every FFmpeg encode is limited to two encoder threads so media serving and application requests retain CPU time.
- Completion updates success/failure timestamps, errors, and running IDs in a `finally` block, then asks the dispatcher to fill newly available capacity.
- The manual `hlsProcessNow` endpoint schedules available work and returns immediately.
- Shutdown stops accepting work and terminates the executor cleanly.

Status snapshots use independent thread-safe state and return immediately. The response keeps existing fields for compatibility and adds `runningVideoIds`. `runningVideoId` remains populated with the first running ID for older clients.

### Feed frontend modules

Feed-mode JavaScript will move out of the large inline template into plain browser scripts under `static/js/admin-feed/`. Scripts use the existing jQuery and Hls.js globals and require no build step.

- `feed-store.js`: owns feed items, current index, source context, paging state, playback mode, and request-generation tokens.
- `feed-player-pool.js`: owns pooled `<video>` elements, source binding, Hls.js instances, preloading, ready state, fallback, and cleanup.
- `feed-profile.js`: owns profile summary, incremental work pages, cancellation, and conversion to an author-scoped feed.
- `feed-controller.js`: binds gestures/buttons and coordinates rendering through the store and player/profile modules.

The existing grid-mode code remains in `index.html`. Compatibility wrappers retain the current global entry points during migration so existing controls do not require a simultaneous rewrite.

## Player Lifecycle

Pooled players are keyed by feed item identity/index rather than fixed `prev/current/next` roles.

Before the three feed slots are rerendered, active pooled video elements are moved to a stable staging node without clearing their `src`, Hls.js instance, or buffer. After slot markup is replaced, a player already bound to each desired item is reattached to its new host. Only a genuinely new adjacent item receives a spare player and a new source binding.

Each video host has a cover layer. The player remains visually hidden until it has decoded data and enters a playable/playing state. Once ready, the video is revealed and the cover fades out. Waiting or source fallback does not re-show a black element; the last visible cover/frame remains until the replacement source is ready.

Only the current item plays. Adjacent items may load and decode an initial frame while paused. Removed or distant items release Hls.js and media resources after a short delay.

## Author Profile Flow

Opening a profile starts summary and work requests with a new generation token. Work pages use the existing API with `pageSize=100` and are requested sequentially.

After every successful page:

1. Deduplicate and append the returned works to profile state.
2. Append cards to the visible grid immediately.
3. Update a small bottom loading indicator.
4. Automatically request the next page until `record.last` is true.

Clicking a visible card cancels further profile rendering, closes the drawer, and creates an author-feed context from the already loaded works. The selected media key is resolved before the old feed is reset, then the new three-slot window is rendered directly at that index. Playback starts through the normal player-ready gate.

If more author pages exist, the author-feed context retains the next page number and loads them near the list end. Returning to all media clears the author context and restores the global feed through its normal API.

## HLS Status UI

The runtime page keeps the HLS card beneath the collection scheduler. It displays enabled/mode state, queue size, running count and IDs, configured concurrency, last success/failure, and last error.

Polling runs every two seconds while work is queued or running and every eight seconds while idle. A failed status request leaves the previous values visible with a stale/unavailable marker instead of replacing the card with an empty state.

## Error Handling

- HLS jobs always release running state in `finally`, including command exceptions and missing files.
- Executor submission rejection returns the reserved ID to the queue when possible and records an explicit error.
- Profile responses from an obsolete token are ignored.
- A failed profile page preserves already rendered cards and offers automatic retry on the next profile open; it does not replace them with `Load failed`.
- Player fallback keeps the cover visible until the MP4 or HLS replacement is frame-ready.
- Media keys are normalized once and stored in state so HTML attribute escaping cannot alter navigation identity.

## Compatibility Fix

Thymeleaf templates must not use `T(com.flower.spirit.config.Global)`. `PageController` supplies media-mode and default-sort values as model attributes. This applies to the video list, graphic list, and media index templates.

## Verification

### Backend

- Unit test that `stats()` returns quickly while a simulated transcode is running.
- Unit test that configured concurrency reserves no more than the allowed number of jobs.
- Unit test that failure clears running state and records the error.
- Maven compile and focused service tests.

### Frontend

- Syntax-check every extracted script with Node.
- Test item-keyed player assignment across next, previous, and direct jumps.
- Test cover visibility until `loadeddata/playing` and across fallback.
- Test profile pages render after 100, 200, and 300 works rather than only at completion.
- Test clicking during background profile loading directly selects the intended work and preserves later author paging.
- Render the three affected Thymeleaf templates with ordinary model attributes.

### Manual Docker acceptance

- Run HLS conversion and simultaneously open, load, and swipe `/admin/index`.
- Confirm the status card updates during the same conversion.
- Move repeatedly between MP4 and HLS videos and confirm no cover/black/cover flash.
- Open an author with hundreds of works, observe incremental cards, click before loading completes, and continue swiping through the author feed.

## Rollout

The refactor remains behind the existing `mediaHomeMode=feed` setting. Grid mode is unchanged. Existing API fields remain available, allowing rollback to the old frontend without a database change.
