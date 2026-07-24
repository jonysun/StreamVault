# Admin Index More Actions Menu Design

## Goal

Reduce the vertical space used by infrequent controls on `/admin/index` while preserving the existing edit, playback-mode, and delete behavior.

## Interaction

- Replace the separate `编辑`, `自动下一条` / `单个循环`, and `删除` controls with one fixed `更多` control in the existing right-side action column.
- Clicking `更多` opens a compact menu to its left. The menu contains, in order: `编辑`, the current playback-mode action, and `删除`.
- The playback-mode label and icon continue to reflect the active state.
- Edit and delete remain disabled when the current work does not support those operations.
- Delete uses the existing warning treatment; other actions use the standard menu treatment.
- The menu closes after an action, when clicking outside it, when switching works, or when opening another feed drawer.
- The menu is positioned within the viewport on desktop and mobile and must not overlap the author avatar control.

## Implementation

- Keep the existing action button IDs and event handlers so the business operations are unchanged.
- Wrap the three buttons in a hidden popover anchored to the new `更多` button.
- Add a small menu state helper for opening, closing, and synchronizing `aria-expanded` and visibility.
- Reuse the existing `updateFeedButtons` state updates for labels, icons, and disabled states.
- Extend the existing control-element guards so clicks inside the menu do not trigger feed play/pause or slide navigation.

## Validation

- Add template assertions for the more-menu structure, the three existing action IDs, and close behavior.
- Run the index template JavaScript syntax check.
- Run the targeted template test and the full Maven test suite.
- Verify that the unrelated local `ConfigEntity.java` modification is not staged.
