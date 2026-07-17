# Profile Feed Startup And Author Identity Fix

## Context

Two regressions remain in the admin media feed:

1. Opening a work from the author profile can leave a video stuck on the first frame.
2. Works from the same Douyin author do not always aggregate together when older rows only have a nickname and no `authoruid` or `secuid`.

The existing feed prefers HLS when available and only falls back to MP4 for fatal HLS errors or "time moving but no decoded frame". A startup stall at `currentTime` near zero can therefore keep retrying without switching source. The author profile APIs already prefer UID matching, but records missing UID fields fall back to username or nickname, so renamed authors can split across profiles.

## Design

### Playback Startup

Keep the current pooled video architecture. Add a current-video startup watchdog that is scoped by the existing play request token and feed index. When the current video stays near the first frame after playback is requested:

- retry normal `play()`;
- ask HLS.js to `startLoad()` and recover media errors when available;
- if the active source is HLS and an MP4 fallback exists, switch the current video to MP4 and replay.

Profile work clicks should also schedule a post-render play pass after the current slot is mounted, so the click path does not depend only on the IntersectionObserver.

### Author Identity

Treat UID/secuid as the canonical author identity.

- Frontend author profile queries should keep passing `authoruid` when available.
- The in-feed author list should key authors by UID when present and use display name only as the label.
- Backend profile queries with a UID should match exact `authoruid/secuid` first.
- For old rows with blank UID fields, use the matched author profile's current display name and name history as a fallback alias list. This fallback only applies to rows whose UID fields are blank, reducing accidental collisions with another author that happens to share a nickname.

### Validation

- Add service tests for UID-based profile matching with nickname-history fallback for blank-UID old rows.
- Add or adjust JS syntax validation for the admin templates.
- Run the backend Maven test suite.
