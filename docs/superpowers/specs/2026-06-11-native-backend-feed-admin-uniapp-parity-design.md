# Native Backend Feed, Admin Data, and Uni-App Parity Design

Date: 2026-06-11

Implementation targets:

- Backend: `backstage`
- Native Android app: `.worktrees/android-native-mvp/app/android-native`

## Scope

This design covers the next repair pass for six user-reported issues:

1. Make bottom navigation selected state visually obvious: active page is white and bold; inactive pages are visibly dimmer.
2. Keep the phone screen awake while the `作品` video playback page is active.
3. Make Feed author filtering return that author's complete backend video set, not only videos already loaded in the app.
4. Make Feed sort modes truly operate on all backend videos and use different icons for `倒序`, `顺序`, and `随机`.
5. Fix admin list pages so video list, graphic list, and favorite tasks show real backend data or explicit session/API errors instead of appearing like placeholders.
6. Bring non-video pages closer to the original uni-app style, especially `推送` and server management.

This pass changes both backend and native app. It does not add unrelated backend administration features, and it does not change video playback sizing.

## Current Findings

Bottom navigation already sets selected tabs to `palette.navSelected`, bold, and alpha `1f`. In the dark theme the active value is white, but inactive text is `0x99FFFFFF`, which remains bright and can look too close to active white.

The Feed route currently does not set any keep-screen-on flag. There is no `FLAG_KEEP_SCREEN_ON`, wake lock, or `keepScreenOn` usage in the native app.

Feed author filtering correctly filters `FeedController.baseVideos`, but `baseVideos` only contains videos already loaded through paged requests. The user needs author filters and sort modes to operate on all backend videos. The backend already has a `findPage(VideoDataEntity res)` path with filters, publish-time sorting, and random mode, but it returns a `Page`, not a full list endpoint.

The Feed sort button currently changes text but uses one fixed icon. The adapter is rebuilt after sorting, so the text updates, but the icon does not distinguish modes.

Admin pages call real APIs through `AdminRepository`, but the user sees no data. The likely failure modes are session cookie handling, HTML login redirects being parsed as JSON/list data, and parser coverage for backend list envelopes. The current login cookie extraction prefers a single likely session cookie; if the backend sets multiple cookies, later admin calls can lose required cookie pairs.

The `推送` page is still a simple card stack. It lacks the uni-app `index.vue` gradient hero, recent history, server status card with dot, and quick-entry grid. Server management is inline in `MainActivity` and does not match `serverlist.vue` cards/buttons.

## Recommended Approach

Use a focused end-to-end repair pass:

- Add a backend full-list video endpoint by reusing existing `VideoDataService` filter/sort logic.
- Switch Feed to full-list loading for route entry, sort, and author filter.
- Fix native UI state and session diagnostics in small, testable helpers.
- Restore `推送` and server management visual structure from uni-app without rewriting unrelated admin screens.

Rejected alternatives:

- Frontend-only full loading by repeatedly paging: works without backend changes but is slower, fragile, and was rejected because backend changes are allowed.
- Large architecture rewrite of all native screens: cleaner long-term but too broad for the reported issues.
- Treat admin data pages as backend-only problem: incomplete because native cookie handling and parser/diagnostic behavior can hide valid responses.

## Backend Full Video Endpoint

Add `POST /api/findAllVideos?token=...` in `ApiController`.

Token behavior matches `/api/findVideos`: both `Global.apptoken` and `Global.readonlytoken` are accepted.

Request form fields reuse `VideoDataEntity` fields already used by `/api/findVideos`:

- `videoauthor`
- `videoname`
- `videodesc`
- `videoplatform`
- `videotag`
- `excludePlatform`
- `publishStart`
- `publishEnd`
- `favorite`
- `sortField=publishtime`
- `sortOrder=asc|desc`
- `randomMode=1`
- `randomSeed=<seed>`

Response format:

- `resCode=000001`
- `message` describes success
- `record` is a complete `List<VideoDataEntity>`

Service design:

- Extract the current `findPage` predicate/sort behavior into shared private helpers inside `VideoDataService`.
- Add `findAll(VideoDataEntity res)` returning `AjaxEntity` with a full list.
- For non-random modes, use the same filters and order as `findPage`.
- For random mode, query the filtered set, shuffle with `randomSeed` when provided, then return the shuffled full list.
- Apply the same HLS play URL enrichment currently applied in `findPage`, so native receives usable `playurl` values.

The full-list endpoint is intended for app playback. Existing paged endpoints remain unchanged for search/list and admin pages.

## Native Feed Full-List Loading

Add a native repository method, for example `StreamVaultRepository.findAllVideos(...)`, that calls `/api/findAllVideos` and parses a non-paged list response into `List<VideoItem>`.

Feed route behavior:

- Entering `作品` loads all backend videos for the current Feed state.
- Selecting an author loads all videos for that author from backend, then replaces the Feed controller list.
- Switching sort loads the backend full list with the selected mode and replaces the Feed controller list.
- `倒序` sends `sortField=publishtime`, `sortOrder=desc`.
- `顺序` sends `sortField=publishtime`, `sortOrder=asc`.
- `随机` sends `randomMode=1` and a generated `randomSeed` for that random order.

Feed controller behavior:

- Add or use `replaceVideos(List<VideoItem>)` so full-list loads do not append stale pages.
- Preserve current video by stable `id`/`videoid` when sorting all videos.
- On author switch, reset current index to `0` because the selected author playlist is a new playlist.
- Keep `playbackPoolCount` only for preload, not playlist size.
- Keep manual pause and favorite update behavior.

Feed request safety:

- Use a Feed request generation guard so stale full-list responses cannot overwrite a newer author/sort selection.
- Show clear loading/error status or toast if full-list loading fails.

## Feed Sort Icons

Add three vector drawables:

- `ic_feed_order_desc.xml`
- `ic_feed_order_asc.xml`
- `ic_feed_order_random.xml`

Add a helper that maps `FeedSortMode` to both label and icon resource:

- `DESC`: icon `ic_feed_order_desc`, label `倒序`
- `ASC`: icon `ic_feed_order_asc`, label `顺序`
- `RANDOM`: icon `ic_feed_order_random`, label `随机`

`DouyinFeedAdapter` should bind both icon and text from the current `FeedSortMode`. Sorting must visibly change both.

## Screen Awake On Playback Page

In `MainActivity.applyRouteChrome(AppRoute route)`:

- When `route == AppRoute.FEED`, call `getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)`.
- For all other routes, call `getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)`.

This avoids wake locks and limits screen-awake behavior to the playback page.

## Bottom Navigation Active State

Keep selected navigation item white and bold.

For dark theme, reduce inactive nav color opacity from `0x99FFFFFF` to `0x66FFFFFF`, while selected remains `0xFFFFFFFF`.

Existing fixed font size remains `17sp`.

## Admin Data Reliability

Admin pages should continue using real backend endpoints:

- `/admin/api/findVideoDataList`
- `/admin/api/findGraphicContentList`
- `/admin/api/findCollectDataList`

Native fixes:

- Preserve all `Set-Cookie` cookie pairs from admin login, joined as a single `Cookie` header, rather than selecting only one likely session cookie.
- Keep the login failure behavior when no cookie is returned.
- Detect non-JSON/HTML login redirects in admin API responses. Show `管理员登录已失效，请重新登录` and clear or invalidate session rather than showing an empty list.
- Improve `AdminJsonParser.parseListItems()` to handle list envelopes:
  - `record.content`
  - `record.list`
  - `record.rows`
  - `record.data`
  - top-level `content/list/rows/data`
- If `resCode` indicates success and the parsed list is empty, show `已加载 0 条...`.
- If response is malformed or session invalid, show an explicit error message.

Backend admin endpoints remain unchanged unless tests prove they return an incompatible structure. If the interceptor returns HTML for expired sessions, the native app handles it explicitly.

## Uni-App Style For Non-Video Pages

### Push Page

Update `HomePushView` to match `app/uniapp/spirit/pages/index/index.vue`:

- Blue gradient hero with `StreamVault` and `智能视频管理平台`.
- Push card overlapping the hero, with paper-plane style icon and label `推送链接`.
- Text area styled like the uni-app `input-area`.
- Gradient `提交链接` button.
- Recent history section powered by existing `/api/recentProcessHistory?limit=8`.
- Server status card with green/grey dot, server name/address, and right arrow.
- Quick-entry row with three cards:
  - `视频列表` → `AppRoute.VIDEO_LIST`
  - `沉浸浏览` → `AppRoute.FEED`
  - `服务器` → server management screen

Add native repository support for recent process history if not already exposed to `HomePushView`.

### Server Management Page

Extract inline server management UI from `MainActivity` into `ServerConfigView`, styled after `app/uniapp/spirit/pages/server/serverlist.vue`:

- Header: `服务器列表` plus `已配置 N 个服务器`.
- Server cards with circular first-letter avatar.
- Default badge for selected default server.
- Server address line.
- Action row:
  - `当前默认` / `设为默认`
  - edit icon button
  - share icon button
  - delete icon button
- Empty state with server icon and `暂无服务器，请添加`.
- Bottom primary action `添加新服务器`.

Preserve existing behavior: add server, import server if still available, edit server, share server, delete server, set default.

### Admin Dashboard/Login

Admin dashboard and login already mostly match uni-app. This pass only adds missing icon/spacing polish where low-risk. It should not rewrite the dashboard or change admin routing.

## Error Handling

- Backend full-list endpoint returns normal `AjaxEntity` failures for invalid token.
- Native Feed full-list load failure should not leave stale videos from a different author/sort as if they were current.
- Admin session failures should be explicit and should not be rendered as empty data.
- UI pages should not fake unavailable backend data.

## Testing Plan

Backend tests:

- `findAllVideos` rejects invalid token.
- `findAllVideos` returns full list, not a page.
- `sortField=publishtime&sortOrder=asc` returns oldest first.
- `sortField=publishtime&sortOrder=desc` returns newest first.
- `videoauthor` filters returned videos.
- `randomMode=1&randomSeed=same` returns stable shuffled order for the same seed.

Native tests:

- Repository builds `/api/findAllVideos` request with token and form fields.
- Parser handles full-list `record` arrays.
- Feed controller `replaceVideos` replaces instead of appending and preserves current video when requested.
- Feed sort icon helper maps each mode to distinct icon and label.
- Route chrome applies and clears keep-screen-on flag through a testable helper or model.
- Theme palette exposes dimmer inactive dark nav state.
- Admin login cookie helper joins all cookie pairs.
- Admin parser handles `record.content/list/rows/data` and top-level list envelopes.
- HomePushView structure descriptors include hero, push card, recent history, server card, and quick cards.
- ServerConfigView structure descriptors include header, server cards, badges, actions, empty state, and bottom add button.

Verification:

- Backend targeted tests for `VideoDataService` / `ApiController` where available.
- Native targeted unit tests for feed, repository, admin parser/session, home, server config, and UI.
- Full native unit tests.
- Native debug APK build.

## Out Of Scope

- Changing video playback dimensions or contain behavior.
- Adding new admin CRUD features beyond making existing lists reliable.
- Rewriting all admin child pages.
- Full visual companion mockups; text design is enough for this pass.
- Git commits unless explicitly requested.
