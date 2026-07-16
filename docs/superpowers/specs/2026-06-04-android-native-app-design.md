# StreamVault Native Android App Design

## Purpose

Create a new native Android app under `app/android-native` that can eventually replace the existing uni-app mobile client while keeping the current uni-app usable during migration.

The native app must implement the current mobile app's functionality in phases, with the first deliverable focused on user-facing video workflows and a Douyin/TikTok-inspired immersive feed.

## Approved Decisions

- Create the native Android app in `app/android-native`.
- Use Kotlin, Jetpack Compose, Navigation Compose, Retrofit, OkHttp, DataStore, Media3 ExoPlayer, Coil, Coroutines, and Flow.
- Start as one Gradle app module with clear internal packages under `core/` and `feature/`; defer multi-module splitting until the app is stable.
- Migrate in phases: user-facing MVP first, management screens later, release replacement last.
- Do not require automatic migration of uni-app local storage in phase 1; users can reconfigure servers or import shared server config.
- Use the existing native video plugin and prior PoC as behavior references, but rework the implementation into the native app architecture.
- Do not store admin passwords in plaintext. Remember username only by default; if password persistence is later required, use Android secure storage.
- Use a Douyin/TikTok-inspired UI for the immersive feed and player.
- Use a StreamVault Material-style UI for home, server management, lists, forms, settings, and admin workflows.
- Reference `running-libo/Tiktok` and `chenbool/uniapp-douyin` for interaction and layout patterns only. Do not copy source code or restricted assets.

## Existing Scope

The current uni-app mobile client includes these pages:

- `pages/index/index.vue`: home, server selection, submit video links, recent processing history.
- `pages/video/fallsVideo.vue`: immersive feed, native plugin bridge, swiper fallback.
- `pages/video/videolist.vue`: video list and single-item playback navigation.
- `pages/video/videoPlay.vue`: stable single-video playback fallback.
- `pages/server/serverlist.vue`: server list, default selection, edit, share, delete.
- `pages/server/addserver.vue`: server create/edit and encrypted import.
- `pages/admin/login.vue`: admin login and local session storage.
- `pages/admin/admin.vue`: admin dashboard, statistics, menu navigation.
- `pages/admin/videoData.vue`: video data management.
- `pages/admin/graphicData.vue`: graphic content management.
- `pages/admin/favData.vue`: collect/favorite task management.
- `pages/admin/directData.vue`: direct link parsing.
- `pages/admin/cacheSettings.vue`: cache and playback settings.

Shared uni-app utilities to migrate:

- `videoUrl.js`: URL normalization and MP4/HLS source selection.
- `cacheManager.js`: cache settings, cached URL lookup, prefetch, eviction, stats, clear.
- `xor-crypto.js`: server config share/import encryption.
- `nativeFeedPayload.js`: feed payload construction, useful as migration reference only.
- `nativeVideoBridge.js`: not needed in the native app, but documents current feed payload contract.

Backend API surface used by mobile:

- Public API: `/api/processingVideos`, `/api/findVideos`, `/api/updateVideoFavorite`, `/api/recentProcessHistory`, `/api/directData`.
- Admin API: `/admin/api/login`, `/admin/api/getDataStatistics`, `/admin/api/findVideoDataList`, `/admin/api/deleteVideoData`, `/admin/api/findGraphicContentList`, `/admin/api/findCollectDataList`, `/admin/api/submitCollectData`, `/admin/api/execCollectData`, `/admin/api/directData`, and related management endpoints added as pages are migrated.

## Architecture

Use a single Android app module at first:

```text
app/android-native/
  settings.gradle.kts
  build.gradle.kts
  app/
    build.gradle.kts
    src/main/
      AndroidManifest.xml
      java/com/streamvault/android/
        MainActivity.kt
        core/
          cache/
          crypto/
          media/
          model/
          network/
          storage/
          ui/
        feature/
          admin/
          feed/
          home/
          player/
          server/
          settings/
          videolist/
```

The app follows this data flow:

```text
Compose Screen
  -> ViewModel
  -> Repository
  -> ApiService / DataStore / MediaCache
  -> Backend
```

Each feature owns its UI state and view models. Shared concerns stay in `core/`.

## Core Components

### Storage

`ServerConfigRepository` stores and exposes:

- server name
- server address
- port
- app token
- streaming flag
- default flag

Default server behavior:

- The first server becomes default.
- Only one server can be default.
- If the default server is deleted, the first remaining server becomes default.
- If no servers exist, all network features show a server setup empty state.

`AdminSessionRepository` stores:

- admin cookie
- expiry timestamp
- username, if user chooses to remember it

It must not store admin passwords in plaintext.

`CacheSettingsRepository` stores phase-1 settings:

- cache enabled
- maximum cache size in MB
- Wi-Fi-only prefetch
- playback source mode: prefer MP4, prefer HLS, MP4 only, HLS only
- playback mode: auto-next or loop current

### Network

Use Retrofit with OkHttp.

The current server is dynamic, so the network layer needs a base URL provider that derives the base URL from the selected `ServerConfig`.

Public requests append or provide `token` as required by the backend. Admin requests attach the stored `Cookie` header through an interceptor.

All API results normalize to:

```kotlin
data class ApiResponse<T>(
    val resCode: String?,
    val message: String?,
    val resMsg: String?,
    val record: T?
)
```

Success is `resCode == "000001"`. User-facing error text uses `message`, then `resMsg`, then a generic fallback.

### URL Resolution

`VideoUrlResolver` ports the behavior of `videoUrl.js`:

- Empty input returns empty string.
- Full `http://` or `https://` URLs pass through unchanged.
- Relative paths normalize slashes, URL-encode path segments, and append `?apptoken=<token>`.
- Source selection supports MP4/HLS priority modes.

### Crypto

`XorCrypto` ports `xor-crypto.js` exactly enough to remain compatible with existing server-share strings.

It needs unit tests for:

- encrypt then decrypt returns original text
- Kotlin output can decrypt known JS-produced values
- invalid import data returns a recoverable error, not a crash

### Media

Use Media3 ExoPlayer for both feed and single-player screens.

`FeedPlayerManager` manages:

- one active player
- current item binding
- pause/resume on lifecycle events
- release on navigation disposal
- current progress
- mute state
- playback mode
- prewarming adjacent media

`MediaCacheManager` wraps Media3 `SimpleCache` and applies cache settings.

## UI Design System

The app uses two related UI modes.

### Immersive Feed Mode

Feed and player surfaces are Douyin/TikTok-inspired:

- full-screen black background
- edge-to-edge video
- transparent system bars
- right-side vertical action rail
- bottom-left author/title/source metadata
- bottom progress bar
- bottom sheets for detail, author, and mode choices

Colors:

- feed background: `#000000`
- primary overlay text: `#FFFFFF`
- secondary overlay text: `rgba(255,255,255,0.72)`
- favorite/accent red: `#FE2C55`
- auxiliary cyan: `#25F4EE`
- overlay scrim: `rgba(0,0,0,0.35)`

Feed controls:

- all icon buttons have at least 48dp touch target
- visual icons use a single vector icon family
- no emoji icons are used for structural UI
- selected favorite uses red fill or red-tinted icon
- all overlay text has shadow or gradient backing for readability

Gestures:

- vertical swipe changes videos
- single tap toggles pause/resume
- progress drag seeks
- title tap expands/collapses long description
- avatar opens author sheet
- info opens video detail sheet

Phase 1 feed keeps a lightweight translucent bottom navigation to avoid trapping users in the feed. If testing shows it hurts immersion, a later phase can auto-hide it while video is playing.

### StreamVault Material Mode

Home, server, list, settings, and admin pages use a clean Material-style UI:

- light and dark theme support
- cards for grouped information
- top app bars on secondary pages
- bottom nav for top-level pages
- visible labels and helper text in forms
- clear destructive confirmation dialogs

Colors:

- primary: `#2563EB` in light theme, `#60A5FA` in dark theme
- background: `#F6F7F8` in light theme, `#050505` in dark theme
- surface: `#FFFFFF` in light theme, `#121212` in dark theme
- danger: `#EF4444` in light theme, `#F87171` in dark theme
- success: `#10B981` in light theme, `#34D399` in dark theme

Spacing and sizing:

- use a 4dp/8dp spacing rhythm
- normal icon buttons are 48dp touch targets
- form controls are at least 48dp high
- bottom bars and fixed buttons respect safe area insets
- long text wraps before truncating unless the content is known to be secondary

Typography:

- use Android system font initially
- page title: 22-26sp, bold
- card title: 16-18sp, semibold
- body text: 16sp
- secondary text: 13-14sp
- feed author: 16sp semibold
- feed caption: 14sp regular, initially capped at 2-3 lines

Accessibility:

- all icon-only controls require `contentDescription`
- touch targets are at least 48dp on Android
- color is not the only indicator of state
- form fields have visible labels and inline errors
- text respects system font scaling
- reduced motion reduces nonessential animations
- Feed controls avoid bottom gesture navigation and display cutout conflicts

## Navigation

Top-level tabs:

- Push/Home
- Feed
- Video List
- Admin

Secondary screens:

- Server list
- Add/edit server
- Single video player
- Admin login
- Admin video data
- Admin graphic data
- Admin collect tasks
- Admin direct data
- Cache settings

Back behavior:

- secondary screens pop normally
- tab switching preserves each tab's state where practical
- returning from the player restores list scroll/filter state
- admin session expiration redirects to login with a clear message

## Feature Designs

### Home

Home covers the current `index.vue` behavior:

- display current server or setup prompt
- enter or paste video share link
- submit link to `/api/processingVideos`
- show recent processing history from `/api/recentProcessHistory`
- provide quick entry to list, feed, and server management

Clipboard behavior must be privacy-aware. On Android versions that show clipboard access notifications, prefer a visible "detected link, tap to fill" affordance over silently replacing user input.

### Server Management

Screens:

- `ServerListScreen`
- `ServerEditScreen`

Capabilities:

- add server
- edit server
- delete server with confirmation
- set default server
- share server config with XOR encryption
- import server config with XOR decryption

Validation:

- server name is required
- server address is required and should start with `http://` or `https://`
- port is required and numeric
- token is required
- decryption failure shows a recoverable error

### Video List

Phase 1 uses a single-column card list for stability. A waterfall grid can be added later after image sizing and pagination are stable.

Each card shows:

- thumbnail
- title or description
- author/source metadata when available
- privacy/favorite indicators

Clicking a card opens the single-video player, not the immersive feed.

### Single Video Player

Single player is a stable playback route for list/admin/shared entries.

It uses:

- black background
- ExoPlayer controls
- retry action
- copy/share URL action
- clear error state

It does not auto-advance by default.

### Immersive Feed

Feed is the primary native-app differentiator.

Capabilities:

- load videos from `/api/findVideos`
- filter out items with no playable source
- play the current item automatically
- prewarm adjacent items
- swipe vertically between items
- tap to pause/resume
- drag progress to seek
- toggle mute
- toggle favorite through `/api/updateVideoFavorite`
- switch auto-next/current-loop
- switch order: normal, reverse, random
- show video info sheet
- show author sheet/list where supported by available metadata

Acceptance criteria:

- MP4 and HLS both play when valid
- relative backend paths resolve correctly with token
- full URLs are not double-prefixed
- fast swiping through 50 videos does not crash
- backgrounding pauses playback
- returning resumes only the current item
- favorite state updates optimistically and rolls back on API failure

### Cache Settings

Phase 1 settings:

- cache enabled
- max cache size MB
- Wi-Fi-only prefetch
- playback source mode
- playback mode
- clear cache
- cache stats

The implementation uses Media3 SimpleCache instead of manually downloading files with a uni-app-style saved-file index.

### Admin

Admin migration starts after user-facing MVP.

Phase 2 screens:

- login
- admin dashboard
- video data management

Phase 3 screens:

- graphic content management
- collect/favorite task management
- direct data parsing
- additional admin actions currently exposed in uni-app

Admin pages use mobile-friendly cards and filters, not desktop-like dense tables.

## Phased Roadmap

### Phase 0: Spec and Plan

Deliverables:

- design spec
- implementation plan
- phased acceptance checklist

Acceptance:

- user approves design
- user approves implementation plan

### Phase 1: Android App Skeleton

Tasks:

- create `app/android-native`
- configure Gradle, Kotlin, Compose, Navigation, Retrofit, OkHttp, Media3, Coil, DataStore
- create package structure
- create `MainActivity`
- create app theme tokens
- create bottom nav shell
- configure Android manifest permissions and cleartext network config

Acceptance:

- debug APK builds
- app launches
- four top-level tabs are visible
- no-server empty state is shown

### Phase 2: Core Layer

Tasks:

- implement models
- implement DataStore repositories
- implement API response parsing
- implement dynamic base URL network layer
- implement token and cookie handling
- implement `VideoUrlResolver`
- implement `XorCrypto`
- implement shared UI states and errors

Acceptance:

- unit tests cover URL normalization
- unit tests cover XOR compatibility
- unit tests cover default server selection
- MockWebServer verifies token/cookie behavior

### Phase 3: Server and Home

Tasks:

- server list
- server add/edit
- encrypted share/import
- home screen
- submit video link
- recent process history

Acceptance:

- user can configure server
- user can submit a video link
- user can view recent history
- server config survives restart

### Phase 4: Video List and Single Player

Tasks:

- video list API integration
- single-column video list UI
- single video player
- source resolution
- playback retry and copy/share

Acceptance:

- list loads real backend videos
- tapping a list item plays video
- MP4/HLS and relative/full URLs work
- player releases cleanly on back

### Phase 5: Immersive Feed MVP

Tasks:

- feed data loading
- vertical pager/feed UI
- ExoPlayer feed manager
- right action rail
- bottom metadata overlay
- favorite integration
- mute and playback mode controls
- progress bar and seek

Acceptance:

- feed is usable as a Douyin-style full-screen video browser
- current video plays automatically
- swiping is smooth on a real Android device
- favorite state syncs with backend

### Phase 6: Cache and Feed Polish

Tasks:

- SimpleCache integration
- cache settings screen
- cache stats and clear
- adjacent prewarming based on settings
- info and author sheets
- order/reverse/random controls
- reduced-motion handling

Acceptance:

- cache settings affect playback/preload
- clear cache works
- feed remains stable under extended use

### Phase 7: Admin MVP

Tasks:

- admin login
- admin session handling
- admin dashboard statistics
- video data management

Acceptance:

- admin login works
- statistics load
- video data list loads
- delete and play actions work
- expired session redirects to login

### Phase 8: Admin Completion

Tasks:

- graphic content management
- collect/favorite task management
- direct data parsing
- remaining admin actions needed for parity

Acceptance:

- native app covers all uni-app admin pages used in practice
- destructive actions are confirmed
- backend failures show recoverable errors

### Phase 9: Release Replacement

Tasks:

- release signing
- R8/ProGuard configuration
- APK output conventions
- final real-device regression checklist
- decide whether to keep uni-app as legacy

Acceptance:

- release APK installs and runs
- user-facing and admin workflows pass regression
- there is a rollback path

## Testing Strategy

Unit tests:

- `VideoUrlResolver`
- `XorCrypto`
- `ServerConfigRepository`
- `ApiResponse` parsing
- `AdminSessionRepository`
- feed view model ordering and filtering

Integration tests:

- MockWebServer for public API token requests
- MockWebServer for admin cookie requests
- repository tests with DataStore test storage

UI tests:

- app launch no-server state
- add server form validation
- home submit loading/success/error states
- video list empty/loading/error states
- admin login form validation

Manual real-device tests:

- MP4 playback
- HLS playback
- full URL playback
- relative URL playback with token
- feed fast swipe stress test
- background/foreground playback lifecycle
- cache clear
- admin login expiry

## Risks and Mitigations

| Risk | Level | Mitigation |
| --- | --- | --- |
| Scope is too large | High | Phase migration; each phase has independent acceptance. |
| Feed player leaks or janks | High | Centralize ExoPlayer lifecycle; stress test on device. |
| Backend response shapes vary | Medium | Normalize through `ApiResponse` and tolerant parsing. |
| Dynamic base URL is awkward with Retrofit | Medium | Use a base URL provider or request URL rewriting interceptor. |
| Cookie session expires silently | Medium | Central admin session repository and login redirect. |
| Server share import breaks | Medium | Port XOR carefully and test against known JS-compatible values. |
| UI overfits Douyin outside video context | Medium | Keep Douyin style only for feed/player; Material style for forms/admin. |
| Cache behavior becomes complex | Medium | Start with SimpleCache size + clear; add policy details later. |

## Non-Goals for Initial MVP

- No comment system.
- No danmu/bullet comments.
- No full source-code copy from reference projects.
- No complex custom typography package.
- No two-column waterfall list in phase 1.
- No automatic migration from uni-app local storage in phase 1.
- No full admin API wrapper before the corresponding UI needs it.
- No long-term dependence on the DCloud native plugin bridge in the new app.

## Defaults for Implementation Plan

- Package name: `com.streamvault.android` for phase 1.
- Minimum SDK: 23, matching current native plugin assumptions.
- Feed bottom nav: visible as a lightweight translucent navigation layer in phase 1.
- Admin password persistence: forbidden in plaintext; phase 1 remembers username only. If password persistence is requested later, it must use Android secure storage.
- Release signing: defer full signing setup until the phase 1 MVP is usable; debug builds are enough for initial development.

These defaults are part of the plan unless the user explicitly overrides them before implementation begins.

## Success Criteria

Phase 1 user-facing MVP is successful when:

- the native app builds and installs independently
- a user can configure a server
- a user can submit a video link
- a user can view recent process history
- a user can browse the video list
- a user can play a single video
- a user can browse the immersive feed
- favorite/unfavorite syncs to the backend
- MP4, HLS, relative paths, and full URLs are handled correctly
- app restart preserves server and settings state
- real-device feed browsing is stable enough for daily use

Full replacement is successful when:

- all current uni-app mobile pages have native equivalents
- user-facing and admin workflows pass regression
- release APK builds and installs
- playback and cache behavior are stable on real devices
- uni-app can be kept only as legacy fallback or removed from primary use
