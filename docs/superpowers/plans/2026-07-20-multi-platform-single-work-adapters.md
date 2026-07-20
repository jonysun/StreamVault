# Multi-Platform Single-Work Adapters Implementation Plan

> **Execution note:** The `writing-plans` skill was not installed in the current environment. This plan follows the repository's existing executable-plan format and the approved design at `docs/superpowers/specs/2026-07-20-multi-platform-single-work-adapter-design.md`.

**Goal:** Normalize single-work parsing, downloading, persistence, display metadata, manual correction, refresh, and redownload across the nine formal platforms while preserving the current SQLite tables, APIs, clients, and mature Douyin/Bilibili collection workflows.

**Architecture:** Wrap every native parser and `yt-dlp` behind `PlatformWorkAdapter`, normalize results into `WorkMetadata`, route verified media through shared persistence and post-processing services, and keep legacy entry points as facades controlled by per-platform rollout properties.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Data JPA, SQLite JDBC, FastJSON, OkHttp, Thymeleaf/jQuery, JUnit 5, Mockito, AssertJ, existing f2 and `yt-dlp` runtimes.

**Safety Rules:**

- Preserve unrelated working-tree changes and stage only task-owned files.
- Do not rewrite legacy platform display values, media paths, work IDs, or raw JSON during migration.
- Keep all new database columns nullable and all new indexes non-unique.
- Keep existing endpoint parameters and response envelopes.
- Do not enable a new platform adapter by default until its regression and live smoke checks pass.
- Do not automatically run both new and legacy adapters for one request.

---

## Phase 1: Canonical Model And Database Compatibility

### Task 1: Platform Catalog And Normalized Work Model

**Files:**

- Create: `backstage/src/main/java/com/flower/spirit/platform/PlatformCatalog.java`
- Create: `backstage/src/main/java/com/flower/spirit/platform/PlatformDefinition.java`
- Create: `backstage/src/main/java/com/flower/spirit/platform/PlatformSupportTier.java`
- Create: `backstage/src/main/java/com/flower/spirit/platform/WorkContentType.java`
- Create: `backstage/src/main/java/com/flower/spirit/platform/WorkMediaResource.java`
- Create: `backstage/src/main/java/com/flower/spirit/platform/WorkMetadata.java`
- Create: `backstage/src/main/java/com/flower/spirit/platform/WorkParseRequest.java`
- Create: `backstage/src/main/java/com/flower/spirit/platform/WorkDownloadRequest.java`
- Create: `backstage/src/main/java/com/flower/spirit/platform/DownloadResult.java`
- Test: `backstage/src/test/java/com/flower/spirit/platform/PlatformCatalogTest.java`
- Test: `backstage/src/test/java/com/flower/spirit/platform/WorkMetadataTest.java`

- [ ] Write failing tests for every confirmed platform alias and stable display name.
- [ ] Test that unknown `yt-dlp` extractors receive a sanitized key and `GENERIC` tier.
- [ ] Test `video`, `graphic`, and `mixed` content types and ordered media resources.
- [ ] Implement immutable or defensively copied normalized model objects without persistence annotations.
- [ ] Add validation helpers for required platform key, work ID, source semantics, and media ordering.
- [ ] Run `mvn -Dtest=PlatformCatalogTest,WorkMetadataTest test` from `backstage`.
- [ ] Commit only the platform model and tests.

### Task 2: Additive Entity And SQLite Schema Upgrade

**Files:**

- Modify: `backstage/src/main/java/com/flower/spirit/entity/VideoDataEntity.java`
- Modify: `backstage/src/main/java/com/flower/spirit/entity/GraphicContentEntity.java`
- Modify: `backstage/src/main/java/com/flower/spirit/entity/AuthorProfileEntity.java`
- Create: `backstage/src/main/java/com/flower/spirit/config/PlatformSchemaInitializer.java`
- Modify: `backstage/src/main/java/com/flower/spirit/config/DatabaseIndexInitializer.java`
- Test: `backstage/src/test/java/com/flower/spirit/config/PlatformSchemaInitializerTest.java`
- Modify test: `backstage/src/test/java/com/flower/spirit/config/DatabaseIndexInitializerTest.java`

- [ ] Write a failing SQLite-backed migration test using an old representative schema.
- [ ] Add nullable work columns: `platformkey`, `contenttype`, `authorhomepage`, `metadataoverrides`, `metadataeditedat`, and `metadataeditedby`.
- [ ] Add nullable graphic columns: `privacy` and `favorite`.
- [ ] Align `biz_author_profile` with `platformkey` and the already-declared `signature` entity property.
- [ ] Make the initializer inspect `PRAGMA table_info` before each additive `ALTER TABLE`.
- [ ] Backfill only `platformkey`, in bounded batches, using the confirmed alias table.
- [ ] Add non-unique canonical platform/work and platform/author indexes.
- [ ] Verify repeated initialization is idempotent and old values are byte-for-byte unchanged apart from new columns.
- [ ] Verify a backfill failure is logged but does not remove old schema usability.
- [ ] Run `mvn -Dtest=PlatformSchemaInitializerTest,DatabaseIndexInitializerTest test`.
- [ ] Commit entity and schema changes separately from adapter behavior.

### Task 3: Legacy Read Compatibility And Canonical DTO Fields

**Files:**

- Create: `backstage/src/main/java/com/flower/spirit/service/PlatformMetadataCompatibilityService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/dto/AdminVideoListItem.java`
- Modify: `backstage/src/main/java/com/flower/spirit/dto/AdminMediaFeedItem.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/VideoDataService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/GraphicContentService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/MediaFeedService.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/PlatformMetadataCompatibilityServiceTest.java`
- Modify test: `backstage/src/test/java/com/flower/spirit/service/MediaFeedServiceTest.java`
- Modify test: `backstage/src/test/java/com/flower/spirit/dto/AdminVideoListItemTest.java`

- [ ] Write failing tests for legacy rows with missing canonical columns.
- [ ] Derive platform keys from aliases without writing the row during reads.
- [ ] Treat old video rows as `video` and infer old graphic media type from ordered extensions.
- [ ] Resolve a missing author homepage from `biz_author_profile` only when platform and author identity match.
- [ ] Add canonical response fields without removing or renaming any legacy field.
- [ ] Normalize displayed platform names while preserving legacy filter compatibility through alias expansion.
- [ ] Confirm list, mixed feed, author navigation, privacy, favorite, and playback DTO fields remain unchanged.
- [ ] Run focused DTO and service tests.
- [ ] Commit compatibility reads before enabling any new writer.

---

## Phase 2: Adapter, Persistence, And Ingest Infrastructure

### Task 4: Adapter SPI, Registry, Resolver, And Rollout Flags

**Files:**

- Create: `backstage/src/main/java/com/flower/spirit/platform/adapter/PlatformWorkAdapter.java`
- Create: `backstage/src/main/java/com/flower/spirit/platform/adapter/PlatformAdapterRegistry.java`
- Create: `backstage/src/main/java/com/flower/spirit/platform/PlatformResolver.java`
- Create: `backstage/src/main/java/com/flower/spirit/config/PlatformAdapterProperties.java`
- Modify: `backstage/src/main/resources/application-dev.properties`
- Modify: `backstage/src/main/resources/application-docker.properties`
- Modify: `backstage/src/main/resources/application-prod.properties`
- Test: `backstage/src/test/java/com/flower/spirit/platform/PlatformResolverTest.java`
- Test: `backstage/src/test/java/com/flower/spirit/platform/adapter/PlatformAdapterRegistryTest.java`

- [ ] Test shared-text URL extraction, known domains, `x.com`, mobile/short domains, and unknown URLs.
- [ ] Test exactly one adapter is selected for a formal platform.
- [ ] Bind `streamvault.adapter.<platform>=legacy|new` with a safe default of `legacy`.
- [ ] Reject an invalid mode at startup with a specific configuration error.
- [ ] Do not add platform rollout state to the database.
- [ ] Run resolver and registry tests.
- [ ] Commit the SPI and feature-flag infrastructure.

### Task 5: Metadata Normalization And Validation

**Files:**

- Create: `backstage/src/main/java/com/flower/spirit/platform/WorkMetadataNormalizer.java`
- Create: `backstage/src/main/java/com/flower/spirit/platform/WorkMetadataValidationException.java`
- Test: `backstage/src/test/java/com/flower/spirit/platform/WorkMetadataNormalizerTest.java`

- [ ] Test publish-time normalization from epoch seconds, epoch milliseconds, `yyyyMMdd`, ISO timestamps, and platform date strings.
- [ ] Test work source and author homepage are distinct fields.
- [ ] Test missing optional metadata remains null rather than receiving an unrelated fallback.
- [ ] Test formal adapters require a stable work ID and at least one valid media resource.
- [ ] Test generic adapters can return partial optional metadata but still require a canonical source and downloadable video.
- [ ] Implement normalization without platform-specific network calls.
- [ ] Run the focused normalizer tests and commit.

### Task 6: Shared Persistence, Deduplication, And Author Upsert

**Files:**

- Create: `backstage/src/main/java/com/flower/spirit/service/WorkPersistenceService.java`
- Create: `backstage/src/main/java/com/flower/spirit/service/WorkDeduplicationService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/dao/VideoDataDao.java`
- Modify: `backstage/src/main/java/com/flower/spirit/dao/GraphicContentDao.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/AuthorProfileService.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/WorkPersistenceServiceTest.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/WorkDeduplicationServiceTest.java`

- [ ] Test `video` routes to `biz_video` and `graphic`/`mixed` route to `biz_graphic_content`.
- [ ] Test all legacy entity columns and canonical additions receive correct meanings.
- [ ] Deduplicate by canonical platform key, work ID, and content type, then check legacy aliases and original address.
- [ ] Keep blocked-work checks platform-alias aware.
- [ ] Preserve local tag, privacy, and favorite state on update.
- [ ] Store complete raw metadata in `jsonData`, not truncated `videoinfo`.
- [ ] Upsert author profiles using canonical platform key and stable author identity.
- [ ] Do not create unique indexes or automatically merge existing duplicates.
- [ ] Run persistence and deduplication tests and commit.

### Task 7: Download Coordinator And Post-Processing Hooks

**Files:**

- Create: `backstage/src/main/java/com/flower/spirit/service/MediaDownloadService.java`
- Create: `backstage/src/main/java/com/flower/spirit/service/WorkIngestService.java`
- Create: `backstage/src/main/java/com/flower/spirit/service/WorkPostProcessingService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/ProcessHistoryService.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/MediaDownloadServiceTest.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/WorkIngestServiceTest.java`

- [ ] Test preview stops before filesystem, database, author, HLS, and notification effects.
- [ ] Test synchronous downloads require successful completion and non-empty media files.
- [ ] Test ordered graphic/mixed media paths are preserved.
- [ ] Test temporary files are not promoted on parse, download, or verification failure.
- [ ] Record recognizing, parsing, downloading, verifying, persisting, post-processing, completed, and failed stages.
- [ ] Keep Aria2 results in a queued state and do not remove old files before confirmed completion.
- [ ] Run author upsert, HLS enqueue, history completion, and one notification through shared post-processing.
- [ ] Run focused ingest tests and commit.

---

## Phase 3: Metadata Editing, Refresh, And Redownload

### Task 8: Manual Metadata Overrides

**Files:**

- Create: `backstage/src/main/java/com/flower/spirit/dto/UpdateWorkMetadataRequest.java`
- Create: `backstage/src/main/java/com/flower/spirit/service/WorkMetadataEditService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/web/admin/AdminController.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/WorkMetadataEditServiceTest.java`
- Test: `backstage/src/test/java/com/flower/spirit/web/admin/AdminControllerWorkMetadataTest.java`

- [ ] Test authenticated `POST /admin/api/updateWorkMetadata` for video and graphic rows.
- [ ] Derive `metadataeditedby` from the session and reject unauthenticated requests.
- [ ] Permit only the approved metadata fields and reject platform key, work ID, content type, local paths, and raw JSON.
- [ ] Distinguish absent keys from explicit JSON null clears.
- [ ] Update legacy columns and `metadataoverrides` in one transaction.
- [ ] Sync author name/avatar/homepage only when `syncAuthorProfile=true` and a stable author ID exists.
- [ ] Verify profile-sync failure does not discard a valid work-level edit.
- [ ] Keep the old `/admin/api/updateVideoData` behavior intact.
- [ ] Run focused service and controller tests and commit.

### Task 9: Metadata Refresh And Unified Redownload

**Files:**

- Create: `backstage/src/main/java/com/flower/spirit/dto/WorkOperationRequest.java`
- Create: `backstage/src/main/java/com/flower/spirit/service/WorkRefreshService.java`
- Create: `backstage/src/main/java/com/flower/spirit/service/WorkRedownloadService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/web/admin/AdminController.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/VideoDataService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/GraphicContentService.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/WorkRefreshServiceTest.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/WorkRedownloadServiceTest.java`

- [ ] Test refresh reparses metadata without downloading media.
- [ ] Apply latest platform metadata, preserve local state, then apply field-level overrides.
- [ ] Test redownload uses `sourceurl` with fallback to `originaladdress`.
- [ ] Download to a temporary location and atomically promote only verified resources.
- [ ] Preserve row ID and old files when replacement fails.
- [ ] Rebuild HLS only after a successful video replacement.
- [ ] Keep old video/graphic redownload endpoints as compatibility facades.
- [ ] Route existing Douyin maintenance through the unified service only after Douyin rollout is enabled.
- [ ] Run refresh/redownload tests and commit.

---

## Phase 4: Platform Adapters

### Task 10: `yt-dlp` JSON Parser, YouTube, And Generic Adapter

**Files:**

- Create: `backstage/src/main/java/com/flower/spirit/platform/adapter/YtDlpMetadataParser.java`
- Create: `backstage/src/main/java/com/flower/spirit/platform/adapter/YtDlpPlatformAdapter.java`
- Modify: `backstage/src/main/java/com/flower/spirit/utils/YtDlpUtil.java`
- Add fixtures: `backstage/src/test/resources/platform/ytdlp/`
- Test: `backstage/src/test/java/com/flower/spirit/platform/adapter/YtDlpMetadataParserTest.java`
- Test: `backstage/src/test/java/com/flower/spirit/platform/adapter/YtDlpPlatformAdapterTest.java`

- [ ] Add sanitized fixtures for YouTube ordinary, Shorts, DASH, multiple JSON lines, and missing optional fields.
- [ ] Parse each JSON object structurally; do not split or infer JSON with ad hoc text rules.
- [ ] Use extractor `id` as work ID and `webpage_url` as work source.
- [ ] Normalize uploader ID, uploader URL, thumbnail, timestamp/upload date, description, and formats.
- [ ] Keep DASH video/audio resources paired for preview and download.
- [ ] Explicitly reject playlists, channels, active live streams, and unsupported non-video entries in the single-work adapter.
- [ ] Make generic support tier and extractor key visible in normalized results and history.
- [ ] Preserve existing cookie-file, proxy, and user-agent behavior.
- [ ] Run parser/adapter tests before setting only `youtube` to `new` in a local smoke environment.
- [ ] Commit YouTube/generic support.

### Task 11: Kuaishou Adapter

**Files:**

- Create: `backstage/src/main/java/com/flower/spirit/platform/adapter/KuaishouPlatformAdapter.java`
- Modify: `backstage/src/main/java/com/flower/spirit/utils/KuaishouParser.java`
- Add fixture: `backstage/src/test/resources/platform/kuaishou/video.json`
- Test: `backstage/src/test/java/com/flower/spirit/platform/adapter/KuaishouPlatformAdapterTest.java`

- [ ] Test short-link and canonical-link resolution.
- [ ] Map H265 with H264 fallback while retaining one canonical work ID.
- [ ] Map author ID, name, avatar, and homepage without using work cover or work URL as substitutes.
- [ ] Preserve Kuaishou cookie selection, success, and risk reporting.
- [ ] Test preview, synchronous download, persistence, duplicate submission, and HLS hook.
- [ ] Keep the existing `kuaishou()` method as a rollout-controlled facade.
- [ ] Run focused tests and a controlled live smoke test, then commit.

### Task 12: Xiaohongshu And Weibo Adapters

**Files:**

- Create: `backstage/src/main/java/com/flower/spirit/platform/adapter/XiaohongshuPlatformAdapter.java`
- Create: `backstage/src/main/java/com/flower/spirit/platform/adapter/WeiboPlatformAdapter.java`
- Refactor: `backstage/src/main/java/com/flower/spirit/executor/HongShuExecutor.java`
- Refactor: `backstage/src/main/java/com/flower/spirit/executor/WeiBoExecutor.java`
- Add fixtures: `backstage/src/test/resources/platform/xiaohongshu/`
- Add fixtures: `backstage/src/test/resources/platform/weibo/`
- Test: `backstage/src/test/java/com/flower/spirit/platform/adapter/XiaohongshuPlatformAdapterTest.java`
- Test: `backstage/src/test/java/com/flower/spirit/platform/adapter/WeiboPlatformAdapterTest.java`

- [ ] Extract side-effect-free parsing from each existing executor while retaining its public facade.
- [ ] Test Xiaohongshu video, graphic, and mixed resources using Note ID as work ID.
- [ ] Test Weibo image, video, and mixed resources in platform order.
- [ ] Normalize publish time, work source, author identity, avatar, and homepage.
- [ ] Store one graphic-content row for a mixed or multi-resource work.
- [ ] Preserve existing cookie and request-header behavior without logging secrets.
- [ ] Run focused tests and one live smoke work per supported content type, then commit.

### Task 13: Twitter/X, Instagram, And TikTok Video Adapters

**Files:**

- Create: `backstage/src/main/java/com/flower/spirit/platform/adapter/TwitterPlatformAdapter.java`
- Create: `backstage/src/main/java/com/flower/spirit/platform/adapter/InstagramPlatformAdapter.java`
- Create: `backstage/src/main/java/com/flower/spirit/platform/adapter/TikTokPlatformAdapter.java`
- Modify: `backstage/src/main/java/com/flower/spirit/utils/URLUtil.java`
- Add fixtures: `backstage/src/test/resources/platform/twitter/`
- Add fixture: `backstage/src/test/resources/platform/instagram/reel.json`
- Add fixture: `backstage/src/test/resources/platform/tiktok/video.json`
- Test: `backstage/src/test/java/com/flower/spirit/platform/adapter/SocialYtDlpPlatformAdapterTest.java`

- [ ] Recognize both `twitter.com` and `x.com` status links.
- [ ] Store a multi-video tweet as one ordered multi-resource work.
- [ ] Use the tweet/post/video page as source URL, never uploader URL.
- [ ] Accept Instagram Reel and single-video posts; reject images and carousels explicitly.
- [ ] Register TikTok as a formal video adapter and preserve configured cookie/proxy behavior.
- [ ] Reject TikTok Photo Mode explicitly without partial persistence.
- [ ] Replace silent exception catches with staged process-history failures.
- [ ] Keep existing Twitter and Instagram methods as rollout facades.
- [ ] Run fixture tests and controlled video smoke tests, then commit.

### Task 14: Bilibili Adapter

**Files:**

- Create: `backstage/src/main/java/com/flower/spirit/platform/adapter/BilibiliPlatformAdapter.java`
- Refactor: `backstage/src/main/java/com/flower/spirit/utils/BiliUtil.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/AnalysisService.java`
- Add fixtures: `backstage/src/test/resources/platform/bilibili/`
- Test: `backstage/src/test/java/com/flower/spirit/platform/adapter/BilibiliPlatformAdapterTest.java`
- Regression test: existing Bilibili collection and danmaku test coverage or a new focused service test.

- [ ] Test BV, AV, `b23.tv`, single-part, and multi-part inputs.
- [ ] Retain one row per `cid`, preserve `bvid`, part number, owner, `ctime`, cover, and canonical part URL.
- [ ] Keep NFO, optional danmaku, cookie/member quality, and merge behavior intact.
- [ ] Reject bangumi, film/TV, and live inputs with explicit unsupported errors.
- [ ] Prove existing favorite/upload/season collection paths still ingest and deduplicate as before.
- [ ] Enable only Bilibili single-work routing after all collection regressions pass.
- [ ] Commit Bilibili adapter changes independently.

### Task 15: Douyin Adapter

**Files:**

- Create: `backstage/src/main/java/com/flower/spirit/platform/adapter/DouyinPlatformAdapter.java`
- Refactor: `backstage/src/main/java/com/flower/spirit/utils/DouUtil.java`
- Refactor: `backstage/src/main/java/com/flower/spirit/executor/DouYinExecutor.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/AnalysisService.java`
- Add fixtures: `backstage/src/test/resources/platform/douyin/`
- Test: `backstage/src/test/java/com/flower/spirit/platform/adapter/DouyinPlatformAdapterTest.java`
- Regression tests: `CollectDataServiceStatusTest`, `DouyinWorkMaintenanceServiceTest`, `AuthorProfileServiceTest`, and cookie tests.

- [ ] Separate Douyin parsing from download/persistence so preview has no image-text side effect.
- [ ] Test video, graphic, image carousel, and mixed media fixtures.
- [ ] Keep stable `sec_uid` author identity, unique ID, avatar, signature, publish time, and canonical source URLs.
- [ ] Preserve f2 diagnostics, cookie selection/risk reporting, HTTP/Aria2 modes, filename templates, NFO, and notifications.
- [ ] Prove works, likes, own favorites, recommendation, detail logs, metadata repair, and redownload regressions pass.
- [ ] Enable Douyin single-work routing last.
- [ ] Commit Douyin adapter changes independently.

---

## Phase 5: Entry Points, Web UI, And End-To-End Regression

### Task 16: Route Existing APIs Through The New Ingest Service

**Files:**

- Modify: `backstage/src/main/java/com/flower/spirit/service/AnalysisService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/web/ApiController.java`
- Modify: `backstage/src/main/java/com/flower/spirit/web/admin/AdminController.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/AnalysisServiceRoutingTest.java`
- Test: `backstage/src/test/java/com/flower/spirit/web/SingleWorkApiCompatibilityTest.java`

- [ ] Test `/api/processingVideos`, `/api/directData`, and `/admin/api/directData` legacy parameter compatibility.
- [ ] Return existing Ajax envelopes and append task/canonical fields without removing old fields.
- [ ] Route preview and ingest through one adapter selection for enabled platforms.
- [ ] Ensure disabled platforms execute exactly one legacy path.
- [ ] Ensure new adapter failure does not automatically execute legacy or generic persistence.
- [ ] Preserve pause controls, bounded executors, queue status, and process history.
- [ ] Run API compatibility tests and commit.

### Task 17: Admin Single-Work Processing UI

**Files:**

- Modify: `backstage/src/main/resources/templates/admin/directData.html`
- Modify if needed: `backstage/src/main/resources/templates/admin/include/common.html`
- Add a focused template/JavaScript test using the repository's available test mechanism.

- [ ] Keep `/admin/directData` and existing navigation links valid.
- [ ] Replace the checkbox with explicit `Parse preview` and `Download and ingest` modes.
- [ ] Keep platform auto-detection and display formal/generic support status from the API.
- [ ] Render normalized single and multi-resource previews without triggering download.
- [ ] On ingest, display the returned task ID and a process-history navigation action.
- [ ] Keep public `video_standard` quick submission and local browser-download parsing unchanged.
- [ ] Verify long titles, URLs, errors, and platform labels fit desktop and mobile layouts.
- [ ] Run template checks and inspect the page at desktop and mobile widths, then commit.

### Task 18: Full Regression, Live Matrix, And Default Rollout

**Files:**

- Create: `docs/testing/multi-platform-single-work-smoke-matrix.md`
- Update only if behavior changed: `README.md`
- Update only if behavior changed: `doc/updaterecords.md`

- [ ] Run the complete Maven test suite.
- [ ] Run schema migration twice against a copy of a representative old database and compare old columns/values.
- [ ] Run one controlled live supported work for each formal platform and each promised content type.
- [ ] Verify duplicate submission, blocked work, manual override, refresh, redownload, source navigation, author navigation, and playback.
- [ ] Verify HLS, privacy, favorite, tag, delete/restore, author aggregation, process history, and notifications.
- [ ] Verify Douyin and Bilibili collection tasks without enabling new collection adapters.
- [ ] Verify legacy Web, UniApp, native, desktop, extension, and API payload consumers.
- [ ] Confirm cookies and signed URLs are absent from fixtures and logs.
- [ ] Enable each adapter by default only after its row in the smoke matrix passes.
- [ ] Document unsupported image/carousel behavior for Twitter/X, Instagram, and TikTok.
- [ ] Commit final compatibility documentation and rollout defaults.

---

## Required Verification Commands

Run from `backstage` unless stated otherwise:

```powershell
mvn test
```

For focused iterations:

```powershell
mvn '-Dtest=PlatformCatalogTest,WorkMetadataTest' test
mvn '-Dtest=PlatformSchemaInitializerTest,DatabaseIndexInitializerTest' test
mvn '-Dtest=WorkPersistenceServiceTest,WorkIngestServiceTest' test
mvn '-Dtest=WorkMetadataEditServiceTest,WorkRedownloadServiceTest' test
mvn '-Dtest=YtDlpPlatformAdapterTest,KuaishouPlatformAdapterTest' test
mvn '-Dtest=XiaohongshuPlatformAdapterTest,WeiboPlatformAdapterTest' test
mvn '-Dtest=SocialYtDlpPlatformAdapterTest' test
mvn '-Dtest=BilibiliPlatformAdapterTest,DouyinPlatformAdapterTest' test
```

Before every task commit:

```powershell
git -c safe.directory=F:/opencode/Project/streamV diff --check
git -c safe.directory=F:/opencode/Project/streamV status --short
```

Stage only files listed in the active task. Do not include unrelated working-tree changes.

## Stop Conditions

Stop the rollout for the affected platform and leave it in `legacy` mode when any of these occurs:

- A migration changes or removes an old value unexpectedly.
- One request invokes more than one persistence path.
- Preview creates a database row or file.
- A failed redownload removes the old playable media.
- Manual overrides are lost after refresh or redownload.
- Existing Douyin or Bilibili collection behavior regresses.
- A legacy API consumer loses an existing field or receives a different envelope.
- A platform cookie or signed URL appears in committed fixtures or unmasked logs.
