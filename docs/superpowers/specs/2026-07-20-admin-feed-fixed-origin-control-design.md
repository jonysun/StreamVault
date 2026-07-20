# Admin Feed Fixed Origin Control Design

Date: 2026-07-20

## Goal

Move the current work's origin-link action out of the per-item author action rail and into the fixed `/admin/index` control column. Keep the author avatar tied to the current work, but position it slightly above the expanded fixed control column.

## Scope

This change is limited to the admin media feed template. It does not change source URL generation, media APIs, author profile behavior, mobile application playback, or feed ordering.

## Design

### Fixed Origin Button

Add a stable `feedOriginBtn` to `feedControls`, directly below the author filter/return button and above the mute button. The button remains in the layout for every work so the control column does not shift.

Whenever feed controls are refreshed, resolve the current item from `feedAllItems[feedCurrentAbsIndex]` and normalize its existing source URL fields with `safeExternalUrl`. Store the valid URL on the fixed button. If no valid HTTP(S) URL exists, disable and visually dim the button. Clicking an enabled button opens the URL in a new tab with `noopener`.

Remove the origin-link button from `buildFeedAuthorActionHtml`. The per-item action rail then renders only the author avatar.

### Author Avatar Position

The fixed control column gains one item. Move the author action rail upward so the avatar sits about eight pixels above the expanded column:

- desktop: increase the rail's bottom offset from 414px to 470px;
- compact layout: increase the offset from 362px to 410px.

The avatar remains part of the current feed item and therefore continues to update with the current author. Only its visual position changes.

## Error Handling

Invalid, missing, or non-HTTP(S) source URLs do not open a window. The fixed origin button remains visible but disabled, preserving the control layout and making unavailable state explicit.

## Validation

- Check inline JavaScript syntax in `admin/index.html`.
- Run the existing admin feed JavaScript module tests.
- Verify the template contains one fixed origin button and no per-item origin button rendering.
- Verify desktop and compact rail offsets account for the added fixed control.
- Run `git diff --check`.

## Acceptance Criteria

- The origin button remains stationary while switching works.
- Its target always corresponds to the current work.
- Missing source URLs disable the button without moving other controls.
- The author avatar remains clickable and sits above the fixed controls without overlap on desktop and compact layouts.
- Existing ordering, media filtering, author filtering, mute, playback mode, playlist, profile, and feed navigation behavior remains unchanged.
