# UniApp Native Feed Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the UniApp video page use the root Android native plugin as the primary Feed experience, with the plugin loading the full backend feed itself.

**Architecture:** Keep UniApp as the launcher and fallback. Move Feed data loading, full-list state, author filtering, sort mode, playback source selection, and fallback behavior into `app/android-native-plugin/streamvault-native-video`. Use small pure Java helpers with unit tests before wiring them into `NativeVideoFeedActivity`.

**Tech Stack:** HBuilderX Android native plugin, Java, Android Gradle Plugin, ViewPager2, Media3 ExoPlayer, OkHttp, JUnit.

---

### Task 1: Plugin Unit Test Harness

**Files:**
- Modify: `app/android-native-plugin/streamvault-native-video/build.gradle`
- Create tests under: `app/android-native-plugin/streamvault-native-video/src/test/java/com/streamvault/nativefeed`

- [ ] Add JUnit test dependency.
- [ ] Run a targeted empty/missing test command to confirm Gradle unit-test wiring.

### Task 2: Native Feed State And Source Helpers

**Files:**
- Create: `NativeFeedController.java`
- Create: `NativeFeedSortMode.java`
- Modify: `VideoSourceResolver.java`
- Test: `NativeFeedControllerTest.java`
- Test: `VideoSourceResolverTest.java`

- [ ] Write failing tests for full-list replacement preserving current item, author replacement resetting index, sort modes, and MP4/HLS fallback selection.
- [ ] Implement minimal helpers until tests pass.

### Task 3: Native Backend Repository

**Files:**
- Create: `NativeFeedRepository.java`
- Test: `NativeFeedRepositoryTest.java`

- [ ] Write failing tests for `/api/findAllVideos` form construction and parsing `record` as either a direct array or page envelope.
- [ ] Implement repository request builder, parser, and async load method.

### Task 4: Activity Wiring

**Files:**
- Modify: `NativeVideoFeedActivity.java`
- Modify: `NativeVideoPlayer.java`
- Modify: `VideoFeedAdapter.java`

- [ ] Replace direct `items` state manipulation with `NativeFeedController`.
- [ ] Load seed videos immediately, then refresh from `/api/findAllVideos`.
- [ ] Preserve current item after refresh by `id`/`videoid`.
- [ ] Make sort and author actions reload through the repository.
- [ ] Add player error callback and MP4/HLS fallback behavior.

### Task 5: UniApp Launcher Payload

**Files:**
- Modify: `app/uniapp/spirit/pages/video/fallsVideo.vue`
- Modify: `app/uniapp/spirit/utils/nativeFeedPayload.js`

- [ ] Pass server config, sort, author, playback mode, and current item identity to native.
- [ ] Keep UniApp playback as fallback when native plugin is unavailable or fails to open.
- [ ] Avoid sending a large full-feed payload from UniApp.

### Task 6: Verification

**Commands:**
- `app\android-native-plugin\gradlew.bat -p app\android-native-plugin --no-daemon --console=plain :streamvault-native-video:testDebugUnitTest`
- `app\android-native-plugin\gradlew.bat -p app\android-native-plugin --no-daemon --console=plain :streamvault-native-video:copyReleaseAarToUniPlugin`

- [ ] Unit tests pass.
- [ ] Release AAR builds and copies into the UniApp native plugin directory.
