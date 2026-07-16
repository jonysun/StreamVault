# Native Backend Feed, Admin Data, and Uni-App Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add backend full-video feed support and update the native Android app so Feed author/sort, admin data, screen-awake behavior, navigation state, and non-video uni-app styling match the approved design.

**Architecture:** Backend adds a full-list app endpoint by reusing existing `VideoDataService` filtering/sorting. Native Feed switches from paged append to full-list replace for playback route, with generation guards and distinct sort icons. Admin reliability and non-video UI parity are handled with small focused helpers/views rather than a broad app rewrite.

**Tech Stack:** Spring Boot 3.5 Java 17 backend, Android Java programmatic UI, JUnit, Gradle wrapper, Maven. No git commits unless the user explicitly asks.

---

## File Structure

Backend:

- Modify `backstage/src/main/java/com/flower/spirit/service/VideoDataService.java`: extract shared query logic and add `findAll(VideoDataEntity res)`.
- Modify `backstage/src/main/java/com/flower/spirit/web/ApiController.java`: add `POST /api/findAllVideos` with existing token validation.
- Create `backstage/src/test/java/com/flower/spirit/service/VideoDataServiceFindAllTest.java`: pure/unit coverage for full-list sort/filter/random helper behavior where feasible.

Native Feed and chrome:

- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/core/network/StreamVaultRepository.java`: add `findAllVideos(...)`.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/core/network/StreamVaultRepositoryTest.java`: full-list request tests.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/core/network/VideoJsonParser.java`: parse `record` arrays for full-list responses if not already supported.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/core/network/VideoJsonParserTest.java`: full-list record array tests.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/feed/FeedController.java`: add/verify `replaceVideos(...)`, author/sort semantics.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/feed/FeedControllerTest.java`: replace/preserve tests.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/feed/DouyinFeedUiText.java`: add icon helper.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/feed/DouyinFeedAdapter.java`: bind distinct sort icons.
- Add native drawables `ic_feed_order_desc.xml`, `ic_feed_order_asc.xml`, `ic_feed_order_random.xml`.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/feed/DouyinFeedUiTextTest.java`: label/icon mapping tests.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/MainActivity.java`: Feed full-list loading, author/sort refresh, route keep-screen-on, admin diagnostics, Home/Server routing.
- Add a small testable model if needed: `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/core/ui/RouteChromePolicy.java` and test class.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/core/theme/ThemePalette.java`: dim inactive dark nav.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/core/ui/AppShellNavModelTest.java` or theme tests.

Native admin reliability:

- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/core/network/AdminLoginCookie.java`: preserve all cookie pairs.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/core/network/AdminLoginCookieTest.java`: multi-cookie tests.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/admin/AdminJsonParser.java`: support additional list envelopes.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/admin/AdminJsonParserTest.java`: envelope tests.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/MainActivity.java`: detect non-JSON/HTML admin responses and show session-expired message.

Native non-video uni-app parity:

- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/home/HomePushView.java`: uni-app hero/push/recent/server/quick cards.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/home/HomePushViewStructureTest.java`: structure descriptors.
- Create `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/server/ServerConfigView.java`: server management view.
- Create `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/server/ServerConfigViewStructureTest.java`: structure descriptors.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/MainActivity.java`: use `ServerConfigView`; wire existing add/import/edit/share/delete/default callbacks.

## Verification Commands

Backend from `backstage`:

```powershell
$env:JAVA_HOME="C:\Users\Jonysun\AppData\Local\Temp\opencode\jdk17\jdk-17.0.18+8"; .\mvnw.cmd -q test
```

If `mvnw.cmd` is missing, use Maven installed in the environment if available:

```powershell
$env:JAVA_HOME="C:\Users\Jonysun\AppData\Local\Temp\opencode\jdk17\jdk-17.0.18+8"; mvn -q test
```

Native from `.worktrees/android-native-mvp/app/android-native`:

```powershell
$env:JAVA_HOME="C:\Users\Jonysun\AppData\Local\Temp\opencode\jdk17\jdk-17.0.18+8"; .\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests "com.streamvault.android.core.network.*" --tests "com.streamvault.android.feature.feed.*"
$env:JAVA_HOME="C:\Users\Jonysun\AppData\Local\Temp\opencode\jdk17\jdk-17.0.18+8"; .\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests "com.streamvault.android.feature.admin.*" --tests "com.streamvault.android.feature.home.*" --tests "com.streamvault.android.feature.server.*" --tests "com.streamvault.android.core.ui.*"
$env:JAVA_HOME="C:\Users\Jonysun\AppData\Local\Temp\opencode\jdk17\jdk-17.0.18+8"; .\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest
$env:JAVA_HOME="C:\Users\Jonysun\AppData\Local\Temp\opencode\jdk17\jdk-17.0.18+8"; .\gradlew.bat --no-daemon --console=plain :app:assembleDebug
```

---

### Task 1: Backend Full Video Endpoint

**Files:**
- Modify: `backstage/src/main/java/com/flower/spirit/service/VideoDataService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/web/ApiController.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/VideoDataServiceFindAllTest.java`

- [ ] Add tests for full-list behavior. If repository mocking is too heavy, first extract pure helper methods for sort parameter validation and deterministic shuffle, then test those helpers. Required cases: `sortField=publishtime&sortOrder=asc`, `sortOrder=desc`, `videoauthor` filtering path keeps author predicate, `randomMode=1` with same `randomSeed` is stable.
- [ ] Run backend targeted tests and confirm failure before implementation.
- [ ] In `VideoDataService`, extract existing `findPage` filtering/sorting construction into shared private methods. Keep `findPage` response behavior unchanged.
- [ ] Add `public AjaxEntity findAll(VideoDataEntity res)` that builds the same specification, queries the full filtered set, applies sort/random, enriches each `VideoDataEntity` with the same HLS `playurl` logic, and returns `new AjaxEntity(Global.ajax_success, "查询成功", list)`.
- [ ] In `ApiController`, add `@RequestMapping("/findAllVideos")` or `@PostMapping("/findAllVideos")` method. Validate token the same as `/findVideos`; invalid token returns `Global.ajax_uri_error`.
- [ ] Run backend tests. Expected: PASS.

---

### Task 2: Native Repository And Parser For Full Feed

**Files:**
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/core/network/StreamVaultRepository.java`
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/core/network/StreamVaultRepositoryTest.java`
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/core/network/VideoJsonParser.java`
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/core/network/VideoJsonParserTest.java`

- [ ] Add `StreamVaultRepositoryTest` for `findAllVideos(server, author, FeedSortMode.ASC, seed)` asserting URL `/api/findAllVideos?token=...`, form `videoauthor`, `sortField=publishtime`, `sortOrder=asc`, no page number.
- [ ] Add repository test for random mode asserting `randomMode=1` and `randomSeed` are posted, while `sortField/sortOrder` are omitted.
- [ ] Add `VideoJsonParserTest` for a response shaped like `{"resCode":"000001","record":[{"id":1,"videoname":"A"}]}` returning one `VideoItem`.
- [ ] Run native network tests and confirm failure.
- [ ] Implement `StreamVaultRepository.findAllVideos(ServerConfig server, String author, String sortMode, String randomSeed)` returning `List<VideoItem>`.
- [ ] Implement or expose `VideoJsonParser.parseVideoList(String json)` that accepts a `record` array and reuses existing item parsing.
- [ ] Run native network tests. Expected: PASS.

---

### Task 3: Feed Controller Replace And Full-List MainActivity Flow

**Files:**
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/feed/FeedController.java`
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/feed/FeedControllerTest.java`
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/MainActivity.java`

- [ ] Add `FeedControllerTest` for `replaceVideos` replacing old items instead of appending. Include a case preserving current video by id when requested and a case resetting to index `0` for author switch.
- [ ] Run `FeedControllerTest`; confirm failure.
- [ ] Add `replaceVideos(List<VideoItem> videos, boolean preserveCurrent)` to `FeedController`. It clears `baseVideos`, adds unique non-null videos, applies current sort, and either restores current key or sets index `0`.
- [ ] In `MainActivity`, replace paged Feed load flow for `作品` with full-list load using the new repository method. Use a generation field to ignore stale full-list responses.
- [ ] On sort click: update mode, release current playback, full-list reload with preserve current.
- [ ] On author selection: set author, release current playback, full-list reload with preserveCurrent=false.
- [ ] Keep preload behavior using `playbackPoolCount` unchanged.
- [ ] Run Feed tests. Expected: PASS.

---

### Task 4: Feed Sort Icons

**Files:**
- Create: `.worktrees/android-native-mvp/app/android-native/app/src/main/res/drawable/ic_feed_order_desc.xml`
- Create: `.worktrees/android-native-mvp/app/android-native/app/src/main/res/drawable/ic_feed_order_asc.xml`
- Create: `.worktrees/android-native-mvp/app/android-native/app/src/main/res/drawable/ic_feed_order_random.xml`
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/feed/DouyinFeedUiText.java`
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/feed/DouyinFeedAdapter.java`
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/feed/DouyinFeedUiTextTest.java`

- [ ] Add tests asserting each `FeedSortMode` maps to distinct icon resource id and label.
- [ ] Run Feed UI text tests; confirm failure for missing icon helper/resources.
- [ ] Add three vector drawables. Use simple white line icons: down arrow/list, up arrow/list, shuffle arrows.
- [ ] Add helper `orderIconRes(FeedSortMode mode)` returning the matching drawable.
- [ ] In `DouyinFeedAdapter`, use `orderIconRes(sortMode)` for the order action icon and `orderLabel(sortMode)` for text.
- [ ] Run Feed tests. Expected: PASS.

---

### Task 5: Route Chrome And Nav Active State

**Files:**
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/core/theme/ThemePalette.java`
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/MainActivity.java`
- Test: `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/core/ui/AppShellNavModelTest.java` or new `RouteChromePolicyTest.java`

- [ ] Add/update tests asserting dark theme selected nav remains `0xFFFFFFFF` and unselected is `0x66FFFFFF`.
- [ ] Add a small pure helper if needed for keep-screen-on route policy, for example `RouteChromePolicy.keepScreenOn(AppRoute.FEED) == true`, other routes false.
- [ ] Run UI tests and confirm failure.
- [ ] Change dark theme `navUnselected` to `0x66FFFFFF`.
- [ ] In `MainActivity.applyRouteChrome`, add `FLAG_KEEP_SCREEN_ON` for `AppRoute.FEED` and clear it for other routes.
- [ ] Run UI tests. Expected: PASS.

---

### Task 6: Admin Cookie And Response Reliability

**Files:**
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/core/network/AdminLoginCookie.java`
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/core/network/AdminLoginCookieTest.java`
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/admin/AdminJsonParser.java`
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/admin/AdminJsonParserTest.java`
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/MainActivity.java`

- [ ] Add cookie test where response has `Set-Cookie: JSESSIONID=abc; Path=/` and `Set-Cookie: XSRF-TOKEN=def; Path=/`; expected cookie header is `JSESSIONID=abc; XSRF-TOKEN=def`.
- [ ] Add parser tests for `record.content`, `record.list`, `record.rows`, `record.data`, and top-level `content` list envelopes.
- [ ] Add parser/session test helper for detecting HTML/non-JSON admin response as session expired if practical.
- [ ] Run admin/network tests and confirm failure.
- [ ] Change `AdminLoginCookie.from(...)` to join all cookie pairs from `Set-Cookie` headers. Keep body fallback for `cookie`/`adminCookie`.
- [ ] Update `AdminJsonParser.parseListItems` to search the supported envelopes.
- [ ] In `MainActivity.parseAdminActionResult`, detect blank/non-JSON/HTML responses and return `AdminActionResult(false, "管理员登录已失效，请重新登录")` for admin calls. In list load failures, show this explicit message.
- [ ] Run admin/network tests. Expected: PASS.

---

### Task 7: Home Push Uni-App Parity

**Files:**
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/home/HomePushView.java`
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/home/HomePushViewStructureTest.java`
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/MainActivity.java`

- [ ] Add structure tests/descriptors for hero, push card, recent history, server status card, and quick cards `视频列表/沉浸浏览/服务器`.
- [ ] Run home tests and confirm failure.
- [ ] Extend `HomePushView.Listener` with quick-entry callbacks: `onOpenVideoList()`, `onOpenFeed()`.
- [ ] Update `HomePushView` layout to match uni-app `index.vue`: gradient hero, overlapping push card, paper-plane label, textarea, gradient submit, recent history section, server status card with dot/right arrow, quick cards.
- [ ] Wire `MainActivity` callbacks to routes `VIDEO_LIST`, `FEED`, and server management.
- [ ] Load recent history using existing repository support. If `HomePushView` only receives status text today, pass a small list model from `MainActivity`.
- [ ] Run home tests. Expected: PASS.

---

### Task 8: ServerConfigView Uni-App Parity

**Files:**
- Create: `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/server/ServerConfigView.java`
- Create: `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/server/ServerConfigViewStructureTest.java`
- Modify: `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/MainActivity.java`

- [ ] Add structure tests for header, count subtitle, server card, first-letter avatar, default badge, action labels/icons, empty state, and bottom add button.
- [ ] Run server feature tests and confirm failure.
- [ ] Implement `ServerConfigView` with a `Listener` covering add, import, edit, share, delete, set default, and back/dashboard actions if needed.
- [ ] Move inline server management UI construction in `MainActivity.createServerManagementContent()` to instantiate `ServerConfigView` while preserving existing dialogs and repository operations.
- [ ] Keep import server available as a secondary action even though uni-app primary bottom button is add.
- [ ] Run server feature tests. Expected: PASS.

---

### Task 9: Final Verification

**Files:** all affected files.

- [ ] Run backend targeted/full tests from `backstage` using Maven.
- [ ] Run native targeted network/feed tests.
- [ ] Run native targeted admin/home/server/ui tests.
- [ ] Run native full `:app:testDebugUnitTest`.
- [ ] Run native `:app:assembleDebug`.
- [ ] Report backend test result, native test result, and APK path `.worktrees/android-native-mvp/app/android-native/app/build/outputs/apk/debug/app-debug.apk`.

## Self-Review

Spec coverage: all spec sections map to tasks. Backend endpoint is Task 1. Native full Feed and sort icons are Tasks 2 to 4. Screen awake and nav active are Task 5. Admin data reliability is Task 6. Uni-app parity for push and server management is Tasks 7 and 8. Verification is Task 9.

Placeholder scan: no TBD/TODO placeholders are present. Backend admin endpoint changes remain intentionally out of scope unless tests prove incompatibility.

Type consistency: planned names are consistent: `findAllVideos`, `parseVideoList`, `replaceVideos`, `orderIconRes`, `RouteChromePolicy`, `ServerConfigView`.
