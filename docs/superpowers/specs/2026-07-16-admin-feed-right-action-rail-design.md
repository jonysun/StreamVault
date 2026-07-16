# Admin Feed Right Action Rail Design

Date: 2026-07-16

## Goal

Rearrange the `/admin/index` playback page right-side actions so the controls feel closer to a Douyin-style action rail:

- The action group sits near the lower-right side of the player.
- The author avatar is visually the first item at the top of the rail.
- Existing control behavior stays unchanged.

## Scope

This is a layout-only change for the admin web playback page. It does not change feed APIs, playback logic, profile loading, sorting behavior, or mobile/native app code.

## Design

Use the existing DOM and event bindings as much as possible.

The global `#feedControls` group will become a vertical action rail positioned on the right side of the feed, lower than its current top-right toolbar position. The buttons keep their existing IDs and click handlers: sort order, author filter, return-to-all, mute, playback mode, and playlist.

Each feed item already renders an author avatar button. That avatar button will be positioned on the same right-side rail and aligned above the global control group, making it the top visual action. This avoids moving author-profile state into a new global component.

Responsive CSS will keep the rail usable on narrow screens by reducing button/avatar dimensions and keeping the rail above the bottom title/progress area.

## Acceptance Criteria

- On `/admin/index`, the right-side controls are vertical and located near the lower-right side of the playback area.
- The author avatar appears above the other action buttons in that right-side column.
- Existing actions still work: author profile opens from the avatar, filters/drawers open from their buttons, mute and playback mode still update.
- The layout does not cover the bottom title/progress controls in normal desktop or mobile widths.

## Validation

- Inspect the resulting CSS/HTML diff to confirm no event IDs or handlers changed.
- Run inline template script syntax checks for `admin/index.html`.
- Run `git diff --check`.
