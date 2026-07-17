# Admin Feed Playback, Profile Search, and Douyin Graphic Links Design

## Scope

Fix three web-admin issues without changing the mobile app:

1. `/admin/index` sometimes gets stuck on the first frame when switching videos, especially after selecting a work from an author profile.
2. The author profile search icon should search works for the current author, and the profile should show the author's Douyin signature when available.
3. `/admin/graphicContentList` should copy/open Douyin graphic works with the user modal URL form, not `/note/{id}`.

## Playback Design

The feed player pool already mounts pooled `<video>` elements into the current, previous, and next feed slots. The failure mode is a timing gap: the current item asks to play before the pooled video is fully mounted and ready, or before HLS has produced a playable frame.

The fix adds a playback request token per video and a small ready/retry path:

- When the current item should play, mark a fresh play request token.
- Ensure the source is loaded and wait for `loadeddata`, `canplay`, or `playing`.
- Retry the play request if the video remains paused or stuck at the first frame shortly after the request.
- Keep retries scoped to the current feed index so old async events cannot start the wrong item.

## Author Profile Design

The profile search button becomes a current-author work search:

- Click the search icon to reveal a compact search input in the profile drawer.
- Search filters only the currently opened author's works.
- Matching uses title/name and description/content fields on the already returned work items.
- The existing profile tabs remain active, so search combines with `all/video/graphic`.

Author signature support:

- Add `signature` to `AuthorProfileEntity` and `AdminAuthorProfileSummary`.
- Store the signature when Douyin profile data contains `signature`.
- Show signature in the profile bio; fall back to the existing generic text when absent.

## Douyin Graphic Link Design

Use a single utility for Douyin source URLs:

- Video works remain `https://www.douyin.com/video/{awemeId}`.
- Graphic works prefer `https://www.douyin.com/user/{authorUid}?modal_id={awemeId}`.
- If `authorUid` is unavailable, keep the existing stored URL as a fallback instead of generating a known-bad link.

The graphic content list should use the normalized source URL for both "copy original link" and "view original work".

## Validation

- Unit tests for Douyin source URL generation.
- Unit tests or service tests for signature propagation where practical.
- Inline JavaScript syntax check for `/admin/index`.
- Maven test suite for backend regressions.
