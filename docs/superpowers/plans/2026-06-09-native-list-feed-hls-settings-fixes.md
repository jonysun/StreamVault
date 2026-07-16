# Native List, Feed, HLS, and Settings Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the native Android list UI parity, Feed full-playlist semantics, HLS source/playback failures, and settings picker behavior.

**Architecture:** Implement a focused repair pass in `.worktrees/android-native-mvp/app/android-native`. Keep list-page uni-app styling local to `VideoListGridView`, separate Feed playlist size from preload count, and add small pure helpers for behavior that can be unit-tested without instrumentation.

**Tech Stack:** Java, Android programmatic UI, JUnit, Media3 ExoPlayer/HLS, Gradle wrapper. Do not create git commits unless the user explicitly asks.

---

## Files

- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/core/ui/AppShellNavView.java`: fixed nav font from `16sp` to `17sp`.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/core/ui/AppShellNavModelTest.java`: nav font expectation.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/videolist/VideoListState.java`: add `publishSort` and `author`.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/videolist/VideoListController.java`: add publish sort toggle, author filter, author options.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/videolist/VideoListControllerTest.java`: list state/filter tests.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/videolist/VideoListGridView.java`: uni-app-style layout and no card action buttons.
- Create `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/videolist/VideoListGridStructureTest.java`: pure UI structure descriptors.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/core/network/StreamVaultRepository.java`: pass `videoauthor` and publish sort request fields.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/MainActivity.java`: list callbacks, Feed author reset, preload queue, HLS diagnostics/errors.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/feed/FeedController.java`: full visible playlist, playback pool no longer caps `videos()`.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/feed/FeedControllerTest.java`: full playlist tests.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/feed/FeedPreloadPlanner.java`: add forward playback-pool queue.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/feed/FeedPreloadPlannerTest.java`: queue tests.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/feed/DouyinFeedUiText.java`: add order label helper.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/feed/DouyinFeedAdapter.java`: show `倒序/顺序/随机` instead of `排序`.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/feed/DouyinFeedUiTextTest.java`: order label tests.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/core/util/VideoUrlResolver.java`: relative/query HLS source detection.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/core/util/VideoUrlResolverTest.java`: HLS tests.
- Create `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/feed/FeedPlaybackErrorText.java`: specific playback error labels.
- Create `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/feed/FeedPlaybackErrorTextTest.java`: error label tests.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/main/java/com/streamvault/android/feature/admin/AdminSettingsView.java`: picker dialogs for enum/boolean rows.
- Modify `.worktrees/android-native-mvp/app/android-native/app/src/test/java/com/streamvault/android/feature/admin/AdminSettingsViewTextTest.java`: picker candidate descriptor tests.

## Commands

Run from `.worktrees/android-native-mvp/app/android-native`:

```powershell
$env:JAVA_HOME="C:\Users\Jonysun\AppData\Local\Temp\opencode\jdk17\jdk-17.0.18+8"; .\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests "com.streamvault.android.feature.videolist.*"
$env:JAVA_HOME="C:\Users\Jonysun\AppData\Local\Temp\opencode\jdk17\jdk-17.0.18+8"; .\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests "com.streamvault.android.feature.feed.*" --tests "com.streamvault.android.core.util.*"
$env:JAVA_HOME="C:\Users\Jonysun\AppData\Local\Temp\opencode\jdk17\jdk-17.0.18+8"; .\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests "com.streamvault.android.feature.admin.*" --tests "com.streamvault.android.core.ui.*"
$env:JAVA_HOME="C:\Users\Jonysun\AppData\Local\Temp\opencode\jdk17\jdk-17.0.18+8"; .\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest
$env:JAVA_HOME="C:\Users\Jonysun\AppData\Local\Temp\opencode\jdk17\jdk-17.0.18+8"; .\gradlew.bat --no-daemon --console=plain :app:assembleDebug
```

---

### Task 1: Bottom Nav 17sp

**Files:** `AppShellNavView.java`, `AppShellNavModelTest.java`

- [ ] Update `AppShellNavModelTest` so every fixed nav font assertion expects `17`:

```java
assertEquals(17, AppShellNavView.navLabelSpFor(UiScale.forDensity(UiDensity.COMPACT)));
assertEquals(17, AppShellNavView.navLabelSpFor(UiScale.forDensity(UiDensity.STANDARD)));
assertEquals(17, AppShellNavView.navLabelSpFor(UiScale.forDensity(UiDensity.LARGE)));
```

- [ ] Run `AppShellNavModelTest`; expected failure while `NAV_LABEL_SP` is still `16`.
- [ ] Change `public static final int NAV_LABEL_SP = 16;` to `17` in `AppShellNavView.java`.
- [ ] Re-run `AppShellNavModelTest`; expected PASS.

---

### Task 2: List Sort And Author State

**Files:** `VideoListState.java`, `VideoListController.java`, `VideoListControllerTest.java`

- [ ] Add failing tests to `VideoListControllerTest` for `togglePublishSort()`, `setAuthorFilter(String)`, `state().getPublishSort()`, `state().getAuthor()`, and `authorOptions()`.

```java
@Test
public void publishSortToggleResetsPaginationAndKeepsFilters() {
    VideoListController controller = new VideoListController();
    controller.resetQuery("cat", "douyin", "funny");
    controller.appendPage(Arrays.asList(video(1, "A")), true);
    controller.togglePublishSort();
    assertEquals("asc", controller.state().getPublishSort());
    assertEquals("cat", controller.state().getKeyword());
    assertEquals(1, controller.state().getNextPage());
    assertTrue(controller.state().getVideos().isEmpty());
}

@Test
public void authorFilterResetsPaginationAndState() {
    VideoListController controller = new VideoListController();
    controller.appendPage(Arrays.asList(video(1, "A").setVideoauthor("alice")), true);
    controller.setAuthorFilter("alice");
    assertEquals("alice", controller.state().getAuthor());
    assertEquals(1, controller.state().getNextPage());
    assertTrue(controller.state().getVideos().isEmpty());
}
```

- [ ] Run `VideoListControllerTest`; expected compile failure for missing methods/getters.
- [ ] Add `publishSort` and `author` fields/getters to `VideoListState`; constructor signature becomes `(List<VideoItem>, String keyword, String platform, String tag, String publishSort, String author, int nextPage, boolean hasMore)`.
- [ ] Add `publishSort = "desc"`, `author = ""`, `togglePublishSort()`, `setAuthorFilter(String)`, `authorOptions()`, and a private `resetPagingOnly()` to `VideoListController`.
- [ ] Update `state()` to pass the new fields.
- [ ] Re-run `VideoListControllerTest`; expected PASS.

---

### Task 3: List Query Wiring

**Files:** `StreamVaultRepository.java`, `MainActivity.java`

- [ ] Change `StreamVaultRepository.findVideoPage(...)` signature to include `String author, String publishSort`.
- [ ] In its form map, add:

```java
putIfNotBlank(form, "videoauthor", author);
putIfNotBlank(form, "publishSort", publishSort);
```

- [ ] Update list load in `MainActivity` to pass `state.getAuthor()` and `state.getPublishSort()`.
- [ ] Update Feed load call to pass an empty author and `feedController.orderMode()`.
- [ ] Add `VideoListGridView.Listener` callbacks in `MainActivity`: `onTogglePublishSort()` calls `videoListController.togglePublishSort(); loadVideoListPage();`; `onShowAuthorFilter()` opens an author picker with `全部作者` plus `videoListController.authorOptions()`.
- [ ] Run videolist tests; expected PASS.

---

### Task 4: Uni-App List UI

**Files:** `VideoListGridView.java`, `VideoListGridStructureTest.java`

- [ ] Create `VideoListGridStructureTest` with assertions that `SEARCH_PLACEHOLDER` is `搜索视频...`, header labels include `视频/筛选/发布时间/作者`, and card labels include only `封面/标题/平台/分类/发布时间` with no `播放/收藏`.
- [ ] Run the structure test; expected failure because descriptors are missing.
- [ ] Add to `VideoListGridView`:

```java
public static final String SEARCH_PLACEHOLDER = "搜索视频...";
public static java.util.List<String> headerControlLabels() { return java.util.Arrays.asList("视频", "筛选", "发布时间", "作者"); }
public static java.util.List<String> cardElementLabels() { return java.util.Arrays.asList("封面", "标题", "平台", "分类", "发布时间"); }
```

- [ ] Extend `Listener` with `onTogglePublishSort()` and `onShowAuthorFilter()`.
- [ ] Replace top action buttons with one compact pill row: `视频`, `筛选`, `发布时间↑/↓`, `作者`.
- [ ] Remove per-card `播放` and `收藏` buttons completely.
- [ ] Replace card time line with publish time only using `shortTime(video.getPublishtime())`.
- [ ] Keep card click playback and thumbnail behavior unchanged.
- [ ] Run all videolist tests; expected PASS.

---

### Task 5: Feed Full Playlist

**Files:** `FeedController.java`, `FeedControllerTest.java`

- [ ] Replace capped-window tests with tests asserting `setPlaybackPoolCount(6)` does not cap `videos()` for 25 videos and `currentPositionLabel()` is `1 / 25`.
- [ ] Add/keep tests that sort changes preserve current video and author filter resets index to first matching video.
- [ ] Run `FeedControllerTest`; expected failure because `videos()` is currently capped.
- [ ] Change `FeedController.videos()` to return `new ArrayList<>(filteredVideos())`.
- [ ] Keep `playbackPoolCount()` and clamping, but remove its effect on `videos()` and `currentPositionLabel()`.
- [ ] Make `replenishActiveWindowIfNeeded(int)` clamp-only or no-op; pagination should now be driven by `shouldLoadMore()` against full visible playlist.
- [ ] Simplify append/order path so appending videos applies current order and restores current video by stable key.
- [ ] Run `FeedControllerTest`; expected PASS.

---

### Task 6: Playback Pool Preload Queue

**Files:** `FeedPreloadPlanner.java`, `FeedPreloadPlannerTest.java`, `MainActivity.java`

- [ ] Add tests for `FeedPreloadPlanner.playbackPoolQueue(10, 3, 3)` returning `[4, 5, 6]` and `playbackPoolQueue(10, 8, 5)` returning `[9]`.
- [ ] Run `FeedPreloadPlannerTest`; expected failure for missing method.
- [ ] Add method:

```java
public static List<Integer> playbackPoolQueue(int itemCount, int currentIndex, int playbackPoolCount) {
    List<Integer> out = new ArrayList<>();
    if (itemCount <= 0 || playbackPoolCount <= 0) return out;
    int current = clamp(currentIndex, 0, itemCount - 1);
    int end = Math.min(itemCount - 1, current + playbackPoolCount);
    for (int i = current + 1; i <= end; i++) out.add(i);
    return out;
}
```

- [ ] In `MainActivity.prewarmAround`, replace old prefetch count queue with `FeedPreloadPlanner.playbackPoolQueue(videos.size(), position, settings.playbackPoolCount())`.
- [ ] Run feed tests; expected PASS.

---

### Task 7: Feed Sort Label And Author Reset

**Files:** `DouyinFeedUiText.java`, `DouyinFeedAdapter.java`, `DouyinFeedUiTextTest.java`, `MainActivity.java`

- [ ] Add `DouyinFeedUiTextTest` assertions for `orderLabel(DESC) == 倒序`, `orderLabel(ASC) == 顺序`, `orderLabel(RANDOM) == 随机`.
- [ ] Run the test; expected failure for missing helper.
- [ ] Add `public static String orderLabel(FeedSortMode mode) { return mode == null ? FeedSortMode.DESC.label() : mode.label(); }`.
- [ ] Add `FeedSortMode sortMode` to `DouyinFeedAdapter` constructor and use `DouyinFeedUiText.orderLabel(sortMode)` for the order action instead of fixed `排序`.
- [ ] Update adapter construction in `MainActivity` to pass `feedController.sortMode()`.
- [ ] Add `releaseFeedPlaybackOnly()` in `MainActivity` to remove progress ticks, delayed play callbacks, preview frames, and current `feedPlayer`.
- [ ] In `showAuthorFilterDialog()`, call `releaseFeedPlaybackOnly()` before `feedController.setAuthorFilter(...)`, then refresh the route.
- [ ] Run feed tests; expected PASS.

---

### Task 8: HLS Source And Error Messages

**Files:** `VideoUrlResolver.java`, `VideoUrlResolverTest.java`, `FeedPlaybackErrorText.java`, `FeedPlaybackErrorTextTest.java`, `MainActivity.java`

- [ ] Add resolver tests for absolute HLS with query, relative `videos/live/index.m3u8`, and normalize result `http://host:8080/videos/live/index.m3u8?apptoken=tok`.
- [ ] Create `FeedPlaybackErrorTextTest` for `无可用 HLS 源`, `无可用 MP4 源`, `无可用播放源`, `HLS 播放失败`, `MP4 播放失败`.
- [ ] Run resolver/error tests; expected failure if relative HLS or helper is missing.
- [ ] Ensure `VideoUrlResolver.isHlsSource` uses `.*\.m3u8(\?.*|$)` and does not require HTTP URLs.
- [ ] Create `FeedPlaybackErrorText` with `noSource(PlaybackSourceMode)` and `playbackFailed(String sourceUrl)`.
- [ ] In `MainActivity.playCurrentFeedItem`, if `resolvedSource(...)` returns blank, toast `FeedPlaybackErrorText.noSource(settings.sourceMode())` and do not call player.
- [ ] In `DouyinFeedPlayer.Listener.onError`, log source mode, video id/videoid, author, source URL, and message via `Log.w("SVFeed", ...)`; then fallback only when source mode allows it.
- [ ] In final fallback failure, toast `FeedPlaybackErrorText.playbackFailed(failedSource)` instead of generic `播放失败，请切换源策略`.
- [ ] Run feed and util tests; expected PASS.

---

### Task 9: Settings Picker Rows

**Files:** `AdminSettingsView.java`, `AdminSettingsViewTextTest.java`

- [ ] Add tests for picker candidate descriptors: theme has `抖音深色/uni-app 浅色`, density has `紧凑/标准/大`, playback end has `自动播放下一个/循环当前/播放后暂停`, source mode has `优先 MP4/优先 HLS/仅 MP4/仅 HLS`, boolean rows have `已启用/已关闭`.
- [ ] Run `AdminSettingsViewTextTest`; expected failure because picker descriptors do not exist.
- [ ] Add `OptionDescriptor<T>` or string-only descriptor type inside `AdminSettingsView` for tests.
- [ ] Replace click-to-cycle rows with `AlertDialog.Builder(context).setTitle(label).setItems(labels, ...)` and save selected setting.
- [ ] Keep numeric rows as input plus `保存`.
- [ ] Update labels to match requested text: `uni-app 浅色`, `循环当前`, `播放后暂停`.
- [ ] Run admin tests; expected PASS.

---

### Task 10: Final Verification

**Files:** all affected files.

- [ ] Run videolist targeted tests.
- [ ] Run feed and util targeted tests.
- [ ] Run admin and core UI targeted tests.
- [ ] Run full `:app:testDebugUnitTest`.
- [ ] Run `:app:assembleDebug`.
- [ ] Report APK path: `.worktrees/android-native-mvp/app/android-native/app/build/outputs/apk/debug/app-debug.apk`.

Expected final result: all commands pass and debug APK builds.

## Self-Review

Spec coverage: every spec section maps to Tasks 1 through 10. UI parity is Tasks 1 to 4, Feed playlist/preload is Tasks 5 to 7, HLS root-cause-oriented fix is Task 8, settings picker is Task 9, verification is Task 10.

Placeholder scan: no TBD/TODO placeholders are present. Backend all-author/full-sort API remains explicitly out of scope in the approved spec.

Type consistency: new names are consistent across tasks: `publishSort`, `author`, `togglePublishSort()`, `setAuthorFilter(String)`, `authorOptions()`, `playbackPoolQueue(...)`, `orderLabel(...)`, and `FeedPlaybackErrorText`.
