# Admin Feed Playback and Home Thumbnail Fixes Design

Date: 2026-07-16

## Goal

Fix three user-facing issues in the Docker web admin UI:

1. On `/admin/index`, clicking the visible video area toggles play/pause.
2. When an author profile work is selected, the target video starts reliably instead of freezing on the first frame.
3. On `/admin/home`, the bottom-right graphic content examples render usable thumbnails instead of broken images.

## Scope

This change is limited to the existing admin web playback and homepage template code. It does not change mobile playback, backend media APIs, database schema, or HLS transcoding behavior.

## Design

### Feed Video Click Toggle

The current feed already has video control helpers and a dedicated play button path. The click-to-toggle behavior should reuse the same playback synchronization path used by explicit controls, so starting playback updates the current feed item state consistently.

Only clicks on the current visible video surface should toggle playback. Clicks on controls, author avatar buttons, profile panels, debug UI, menus, links, or buttons must keep their existing behavior. Graphic posts keep their current left/right click behavior for changing slides.

### Profile Video First-Frame Freeze

The likely failure path is a stale `primeVideoFrame` callback from a pooled/preloaded player. That callback can fire after the player has been promoted to the current item, then pause and reset the current video back to time zero.

The fix is to invalidate or guard those priming callbacks. A callback may only pause/reset the video if the player is still in its original priming role and still represents the same media source/key captured when priming began. Once the player is mounted as the current item, old priming callbacks must no longer affect it.

### Home Graphic Thumbnails

Graphic content image data may arrive as a JSON string or array, may contain Windows backslashes, and may include non-image media such as MP4/HLS files. The homepage renderer should normalize paths, filter to image-like entries, escape HTML attributes, and provide a fallback placeholder when no usable image exists or an image fails to load.

## Testing

Run focused JavaScript/template verification after implementation:

- Existing admin feed module tests.
- JavaScript syntax checks for touched feed files.
- Template parsing checks for touched Thymeleaf templates.
- Maven tests if template or backend wiring risk is present.
- `git diff --check`.

## Acceptance Criteria

- `/admin/index` video area click toggles play/pause only for the current video, without stealing clicks from controls or profile UI.
- Profile work selection switches directly to that author's feed item and the selected video can play immediately.
- `/admin/home` graphic content examples show valid image thumbnails or placeholders, not broken image icons.
- No unrelated worktree files, root Android/uniapp files, or `.tmp/` files are staged.
