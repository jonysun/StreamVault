# Native List, Feed, HLS, and Settings Fixes Design

Date: 2026-06-09

Implementation target: `.worktrees/android-native-mvp/app/android-native`.

## Scope

This spec covers the next native Android repair pass for five user-reported issues:

1. Increase bottom navigation label size by one step.
2. Restore the `列表` page to the original uni-app list layout and sizing, while adding publish-time sort and author filter controls in the same style.
3. Correct `作品` Feed sorting semantics so the playback list is the full backend video set, not the playback pool.
4. Investigate and fix HLS playback failures, especially the author-filter first-video case where playback loops on segment 1 or reports a vague source error.
5. Replace click-to-cycle settings rows with explicit option picker dialogs.

The work remains in the native Android app at `.worktrees/android-native-mvp/app/android-native`. It does not change backend APIs in this pass.

## Current Findings

The current native `VideoListGridView` uses shared `ThemedViews` cards and buttons. That made the page larger than the original uni-app layout and introduced controls that do not exist in `app/uniapp/spirit/pages/video/videolist.vue`, including top-level search/load-more buttons and per-card `播放`/`收藏` buttons.

The uni-app list page uses a fixed white search header, a grey rounded search input, small pill tabs, a collapsible filter panel, two-column cards, 16:9 covers, compact tags, and publish/download time text. The requested native version should keep this visual language and remove the native-only action buttons. The user also requested publish-time sorting and author filtering in the header.

The current `FeedController.videos()` returns only an active playback window capped by `playbackPoolCount`. The user clarified that this is wrong: the `作品` page playlist is all backend videos. Sorting must apply across all available backend videos. The playback pool setting only controls how many subsequent videos are pre-buffered.

HLS resolution currently depends on source field matching and Media3 HLS playback. The user reproduced a severe failure by choosing an author in the `作品` page and playing the first video. That path currently calls `setAuthorFilter()` and refreshes the route, but the design must ensure old player and preview sessions do not continue to own playback after the filter switch. HLS fixes must be root-cause oriented, with source and Media3 error diagnostics rather than blind retries.

The current settings page cycles enum values immediately when a row is tapped. The user expects a picker with all candidates.

## Recommended Approach

Use a focused repair pass rather than a broad refactor. The implementation should fix the reported behavior directly, add focused tests, and avoid unrelated restructuring. Small extraction is acceptable only when needed to make Feed playlist versus preload semantics explicit.

Rejected alternatives:

- Full Feed/List architecture rewrite: cleaner long term, but too risky for this urgent repair and would mix HLS debugging with unrelated restructuring.
- UI-only patch: lower risk, but leaves the serious Feed and HLS playback issues unresolved.

## List Page Design

`VideoListGridView` should stop using large generic buttons for the main list layout and instead define compact uni-app-style primitives for this page:

- Page background: light grey, matching uni-app `#f6f7f8` in the light theme. Dark theme can use equivalent palette colors, but sizing and layout must match the uni-app structure.
- Header: fixed-looking top section inside the native page, white surface, compact horizontal padding, subtle shadow/divider.
- Search input: grey rounded pill with search icon, placeholder `搜索视频...`, internal clear icon only when text is non-empty.
- Header controls: one compact pill row containing `视频`, `筛选`, `发布时间↓` or `发布时间↑`, and `作者`.
- Filter panel: shown only when `筛选` is active; contains `视频平台`, `视频标签`, compact inputs, and `清空`/`应用筛选` buttons styled like the uni-app panel.
- List: two-column card grid with tight gutters.
- Card: white rounded card, 16:9 cover, optional privacy overlay, centered play glyph overlay behavior allowed only as a cover overlay, title, platform tag, category tag, and publish time.
- Card actions: remove `播放` and `收藏` buttons completely.
- Time: show publish time only in the card metadata. Do not show the previous `发:... / 下:...` combined line.
- Loading more: use bottom loading state; do not keep a top `加载更多` button.

Publish-time sorting and author filtering behavior:

- Tapping `发布时间↓/↑` toggles descending/ascending publish time order, resets list pagination to page 1, and re-queries.
- Tapping `作者` opens a picker. Because there is no confirmed all-author backend API, the picker uses authors from currently loaded list data plus `全部作者`. It must not fake unavailable authors.
- Selecting an author resets pagination to page 1 and re-queries using the selected author filter.

## Bottom Navigation Design

Increase `AppShellNavView.NAV_LABEL_SP` from `16` to `17`. The bottom navigation font remains fixed and must not follow `UiDensity`. Existing route labels and behavior remain unchanged: `推送`, `作品`, `列表`, `管理`.

## Feed Playlist And Preload Design

The Feed controller must separate three concepts:

- Base videos: all videos loaded from the backend so far.
- Visible playlist: all videos matching current author filter, ordered by current sort mode.
- Preload window: the next `playbackPoolCount` videos after the current item, used only by preloading/caching logic.

Required behavior:

- `videos()` or the adapter data source returns the full visible playlist, not a capped playback pool.
- `currentPositionLabel()` reflects the full visible playlist size.
- `ASC` sorts all available videos from oldest publish time to newest.
- `DESC` sorts all available videos from newest publish time to oldest.
- `RANDOM` shuffles all available videos into a current random playlist.
- Changing sort preserves the current video by stable id/videoid when possible.
- Changing author filter resets current index to the first matching video and starts playback from that item.
- `playbackPoolCount` remains clamped according to settings, but only affects the preload planner.
- If backend data is paged, native continues to load additional pages as the user nears the end. Newly loaded data joins the current full playlist order. Full 3000-item ordering before all pages are loaded requires a backend sort/all-list API and is outside this pass.

Feed UI changes:

- The order action label changes from fixed `排序` to the current sort mode label: `倒序`, `顺序`, or `随机`.
- The icon can remain the existing order icon.

Author-filter playback reset:

- When author filter changes, release or detach old preview slots and current player ownership before rebuilding adapter data.
- Rebuild the adapter with the filtered full playlist.
- Set current index to `0` when there are matching videos.
- Start playback only after the new holder for index `0` is bound.

## HLS Debugging And Playback Design

HLS must be handled with root-cause evidence. The implementation should add minimal diagnostics around source selection and player errors, then fix confirmed issues.

Source resolution requirements:

- `PREFER_HLS` chooses the first valid HLS source from `playurl`, `hlsUrl`, `sourceurl`, and `originaladdress`, then falls back to MP4-like sources.
- `HLS_ONLY` chooses only valid HLS sources and reports a clear no-HLS-source error if none exist.
- HLS detection must support `.m3u8` with query strings.
- Relative `.m3u8` paths must be normalized using server address, port, and token before playback.
- A source should not be discarded only because it is a relative path before normalization.

Playback diagnostics:

- Before Feed playback, capture video id/videoid, author, current source mode, selected raw source, normalized source, and whether it is HLS.
- On Media3 error, capture error code name, message, and selected source type.
- User-facing errors should distinguish `无可用播放源`, `无可用 HLS 源`, `HLS 播放失败`, `MP4 播放失败`, and fallback failure where applicable.

Likely fix areas to verify:

- Source resolver must normalize candidate URLs after choosing an HLS-like relative path.
- Author filter changes must not leave preview/player sessions attached to stale holders.
- HLS preview rendering should not steal or loop the active playback session after author filter changes.
- If backend m3u8 contents reference unauthenticated or unresolved segment URLs, native should report that clearly; backend changes are outside this pass.

Fallback rules:

- In `PREFER_HLS`, if HLS fails and an MP4-like fallback exists, fallback may be attempted and the error should note the HLS failure.
- In `HLS_ONLY`, do not fallback to MP4.
- In `MP4_ONLY`, do not fallback to HLS.

## Settings Picker Design

Settings rows with fixed candidates should open picker dialogs instead of cycling values on tap. Each row displays the current value. Selecting a candidate updates only that setting and persists it.

Rows to convert:

- `界面主题`: `抖音深色`, `uni-app 浅色`
- `界面大小`: `紧凑`, `标准`, `大`
- `播放结束策略`: `自动播放下一个`, `循环当前`, `播放后暂停`
- `播放源策略`: `优先 MP4`, `优先 HLS`, `仅 MP4`, `仅 HLS`
- `启用预缓存`: `已启用`, `已关闭`
- `仅 Wi-Fi 预缓存`: `已启用`, `已关闭`
- `允许隐私视频缓存`: `已启用`, `已关闭`

Numeric rows remain input-plus-save rows.

## Error Handling

The app should avoid fake success or vague failures. If a backend capability is missing, show an honest limitation. If a playback source cannot be resolved, show the source-mode-specific reason. If Media3 fails, preserve enough diagnostic detail for later investigation while keeping the user-facing text concise.

## Testing Plan

Add or update targeted unit tests around pure behavior and structural UI descriptors:

- Bottom nav: `navLabelSpFor(...)` returns `17` for all density values.
- List structure: no card `播放`/`收藏` buttons; header exposes search, `视频`, `筛选`, publish-time sort, and author filter controls; card descriptor contains cover, title, platform tag, category tag, and publish time only.
- List controller: publish-time sort toggles and resets pagination; author filter selection resets pagination and passes selected author to query state.
- Feed controller: full visible playlist is not capped by `playbackPoolCount`; position label uses full visible size; sort applies across the full visible list; author filter resets current index to first item.
- Preload planner: playback pool count controls only subsequent preload candidates.
- Feed UI text: order action label is `倒序`, `顺序`, or `随机` based on current mode.
- Video URL resolver: relative HLS, absolute HLS, query-string HLS, `PREFER_HLS`, and `HLS_ONLY` resolve as expected after normalization.
- HLS error mapping: no-source, HLS-only no source, HLS playback failure, and fallback failure map to specific messages.
- Settings view: enum and boolean rows expose picker candidates and selected values map to saved settings.

Final verification should run targeted tests for affected packages, full unit tests, and `:app:assembleDebug` with the known JDK path.

## Out Of Scope

- Backend changes for server-side full sorting, all-author lists, or HLS playlist rewriting.
- Large `MainActivity` refactor beyond the smallest changes needed for correct ownership and flow.
- New instrumentation UI tests.
- Changing the native plugin route.
