# Collection Fetch/Download Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split collection list fetching from per-work media downloading, add true incremental Douyin pagination, persistent per-work retries, observable stage-specific status, and safe recovery while retaining SQLite and all existing media behavior.

**Architecture:** `CollectJobWorker` remains the single fetch worker and persists an observed-list/run-item plan before completing its fetch run. A new `CollectDownloadWorker` independently claims only generation-tagged run items, refreshes each work through the existing `WorkIngestService`, and records completion or retry in short serialized SQLite transactions. A project-controlled Python paginator owns page-by-page stopping and returns a typed envelope to Java; legacy `like`, `fav`, and `recommend` modes keep bounded fetching until their cursor semantics are separately adapted.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring JDBC/JPA, SQLite WAL, Thymeleaf/jQuery, Python 3, F2 `0.0.1.7`, JUnit 5, AssertJ, Python `unittest`

---

## Scope And File Map

The implementation is one deployable feature, but it is split into independently testable commits. PostgreSQL, Redis, additional fetch concurrency, automatic replay of historical `PENDING` items, and production database data rewrites are explicitly out of scope.

**Create:**

- `backstage/src/main/java/com/flower/spirit/config/CollectPipelineSchemaInitializer.java` - idempotent queue/watermark schema upgrade.
- `backstage/src/main/java/com/flower/spirit/service/DouyinFetchMode.java` - `INITIAL`, `INCREMENTAL`, and `AUDIT` protocol values.
- `backstage/src/main/java/com/flower/spirit/service/DouyinFetchRequest.java` - typed Java-to-Python request.
- `backstage/src/main/java/com/flower/spirit/service/DouyinFetchEnvelope.java` - typed Python result and diagnostics.
- `backstage/src/main/java/com/flower/spirit/service/DouyinIncrementalFetchService.java` - temporary known-ID/result files, process invocation, parsing, and cleanup.
- `backstage/src/main/java/com/flower/spirit/service/CollectDownloadClaim.java` - immutable claimed-item payload.
- `backstage/src/main/java/com/flower/spirit/service/CollectDownloadException.java` - per-work error code and retryability.
- `backstage/src/main/java/com/flower/spirit/service/CollectDownloadService.java` - one-item ingest and collection-detail linkage.
- `backstage/src/main/java/com/flower/spirit/service/CollectDownloadWorker.java` - independent persistent download worker.
- `backstage/src/main/java/com/flower/spirit/service/transaction/CollectDownloadTransaction.java` - claim, complete, retry, fail, requeue, and stale-lock recovery.
- `backstage/src/main/docker/buildx/script/douyin_incremental.py` - pure pagination/normalization policy.
- `backstage/src/test/python/test_douyin_incremental.py` - deterministic paginator tests without network or F2.
- Focused Java tests named in each task below.

**Modify:**

- `backstage/src/main/java/com/flower/spirit/entity/CollectDataEntity.java` - successful-fetch watermarks.
- `backstage/src/main/java/com/flower/spirit/entity/CollectRunItemEntity.java` - persistent download queue fields.
- `backstage/src/main/java/com/flower/spirit/service/CollectRunFetchedItem.java` - explicit decision and initial process state.
- `backstage/src/main/java/com/flower/spirit/service/CollectDataService.java` - fetch-and-plan only for persistent runs; retain legacy direct entry points.
- `backstage/src/main/java/com/flower/spirit/service/CollectRunService.java` - atomic plan storage/watermark completion facade.
- `backstage/src/main/java/com/flower/spirit/service/transaction/CollectQueueTransaction.java` - generation-tagged item insertion and fetch-stage completion counts.
- `backstage/src/main/java/com/flower/spirit/service/CollectJobWorker.java` - finish after plan persistence, never download media.
- `backstage/src/main/java/com/flower/spirit/service/CollectTriggerType.java` - add explicit `AUDIT` trigger.
- `backstage/src/main/java/com/flower/spirit/service/CollectEnqueueService.java` - enqueue a full audit.
- `backstage/src/main/java/com/flower/spirit/utils/CommandUtil.java` - structured incremental command invocation.
- `backstage/src/main/java/com/flower/spirit/task/TaskService.java` - independently wake fetch and download workers.
- `backstage/src/main/java/com/flower/spirit/config/DatabaseIndexInitializer.java` - queue claim and active-work indexes.
- `backstage/src/main/java/com/flower/spirit/config/SqliteSchemaPreflight.java` - required pipeline column validation.
- `backstage/src/main/java/com/flower/spirit/dto/CollectTaskListItem.java` - split fetch/download status.
- `backstage/src/main/java/com/flower/spirit/service/CollectRunQueryService.java` - queue fields and download summaries.
- `backstage/src/main/java/com/flower/spirit/service/RuntimeJobQueryService.java` - fetch/download/HLS dashboard grouping.
- `backstage/src/main/java/com/flower/spirit/web/admin/AdminController.java` - audit and retry endpoints.
- `backstage/src/main/resources/templates/admin/collectDataList.html` - stage status, table columns, retry, and audit controls.
- `backstage/src/main/resources/application-docker.properties` and `application-dev.properties` - conservative defaults.
- Every `backstage/src/main/docker/buildx/Dockerfile*` that installs F2 - pin `f2==0.0.1.7` and print version.

### Task 1: Add Idempotent Queue And Watermark Schema

**Files:**
- Create: `backstage/src/main/java/com/flower/spirit/config/CollectPipelineSchemaInitializer.java`
- Modify: `backstage/src/main/java/com/flower/spirit/entity/CollectDataEntity.java`
- Modify: `backstage/src/main/java/com/flower/spirit/entity/CollectRunItemEntity.java`
- Modify: `backstage/src/main/java/com/flower/spirit/config/DatabaseIndexInitializer.java`
- Modify: `backstage/src/main/java/com/flower/spirit/config/SqliteSchemaPreflight.java`
- Test: `backstage/src/test/java/com/flower/spirit/config/CollectPipelineSchemaInitializerTest.java`
- Test: `backstage/src/test/java/com/flower/spirit/config/DatabaseIndexInitializerTest.java`
- Test: `backstage/src/test/java/com/flower/spirit/config/SqliteSchemaPreflightTest.java`

- [x] **Step 1: Write failing idempotent migration tests**

Create a SQLite test database with the current production-shaped tables, call `initialize()` twice, and assert these exact columns and defaults:

```java
@Test
void addsPipelineColumnsWithoutChangingHistoricalRows() {
    jdbc.execute("CREATE TABLE biz_collect_data (id INTEGER PRIMARY KEY, taskname TEXT)");
    jdbc.execute("CREATE TABLE biz_collect_run_item (id INTEGER PRIMARY KEY, run_id INTEGER, process_state TEXT)");
    jdbc.update("INSERT INTO biz_collect_run_item(id, run_id, process_state) VALUES (1, 7, 'PENDING')");

    initializer.initialize();
    initializer.initialize();

    assertThat(columns("biz_collect_data")).contains(
            "last_successful_fetch_at", "last_seen_publish_time", "last_seen_work_id");
    assertThat(columns("biz_collect_run_item")).contains(
            "attempt_count", "max_attempts", "available_at", "locked_by", "locked_at",
            "started_at", "finished_at", "error_detail", "queue_generation");
    assertThat(jdbc.queryForObject(
            "SELECT process_state FROM biz_collect_run_item WHERE id = 1", String.class))
            .isEqualTo("PENDING");
    assertThat(jdbc.queryForObject(
            "SELECT queue_generation FROM biz_collect_run_item WHERE id = 1", String.class))
            .isNull();
}
```

- [x] **Step 2: Run the migration tests and verify they fail**

Run: `mvn -f backstage/pom.xml -Dtest=CollectPipelineSchemaInitializerTest,DatabaseIndexInitializerTest,SqliteSchemaPreflightTest test`

Expected: FAIL because `CollectPipelineSchemaInitializer` and the required columns/indexes do not exist.

- [x] **Step 3: Implement the schema initializer and mappings**

Use `DatabaseWriteExecutor` plus `DatabaseInitializationTransaction` for each DDL statement. The initializer must inspect `PRAGMA table_info`, add only missing columns, and never update old rows:

```java
private static final Map<String, List<String>> COLUMNS = Map.of(
        "biz_collect_data", List.of(
                "last_successful_fetch_at TIMESTAMP",
                "last_seen_publish_time VARCHAR(64)",
                "last_seen_work_id VARCHAR(255)"),
        "biz_collect_run", List.of(
                "fetch_stop_reason VARCHAR(64)",
                "fetch_warning VARCHAR(255)"),
        "biz_collect_run_item", List.of(
                "attempt_count INTEGER NOT NULL DEFAULT 0",
                "max_attempts INTEGER NOT NULL DEFAULT 4",
                "available_at TIMESTAMP",
                "locked_by VARCHAR(255)",
                "locked_at TIMESTAMP",
                "started_at TIMESTAMP",
                "finished_at TIMESTAMP",
                "error_detail CLOB",
                "queue_generation VARCHAR(32)"));

@Order(120)
@EventListener(ApplicationReadyEvent.class)
public void initialize() {
    COLUMNS.forEach(this::ensureColumns);
}
```

Map `CollectDataEntity` with `Date lastSuccessfulFetchAt`, `String lastSeenPublishTime`, and `String lastSeenWorkId`, each using an explicit snake-case `@Column`. Map all nine queue fields on `CollectRunItemEntity` and add complete getters/setters for each field.

`last_seen_publish_time` stores Douyin epoch seconds as a decimal string, not a formatted local date. This keeps the upstream boundary comparison timezone-independent. `biz_collect_run.fetch_stop_reason` stores every successful paginator outcome; `fetch_warning` stores only nonfatal abnormal classifications such as `NO_PUBLIC_WORKS`, `ACCOUNT_DEACTIVATED`, `WORKS_UNAVAILABLE`, `EMPTY_PAGINATION`, and `MAX_PAGE_GUARD`.

Add these indexes to `DatabaseIndexInitializer.defaultIndexSqlStatements()`:

```java
"CREATE INDEX IF NOT EXISTS idx_collect_run_item_download_claim "
        + "ON biz_collect_run_item(queue_generation, process_state, available_at, ordinal, created_at, id)",
"CREATE INDEX IF NOT EXISTS idx_collect_run_item_active_work "
        + "ON biz_collect_run_item(platform_key, work_id, process_state)",
"CREATE INDEX IF NOT EXISTS idx_collect_run_item_run_state "
        + "ON biz_collect_run_item(run_id, process_state)"
```

Extend `SqliteSchemaPreflight` to throw with the table and missing column names after the migration listener has run. Keep native identity validation and add:

```java
private static final Map<String, Set<String>> REQUIRED_PIPELINE_COLUMNS = Map.of(
        "biz_collect_data", Set.of("last_successful_fetch_at", "last_seen_publish_time", "last_seen_work_id"),
        "biz_collect_run", Set.of("fetch_stop_reason", "fetch_warning"),
        "biz_collect_run_item", Set.of("attempt_count", "max_attempts", "available_at", "locked_by",
                "locked_at", "started_at", "finished_at", "error_detail", "queue_generation"));
```

Move the preflight listener to `@Order(180)` so it validates after schema initialization and before queue workers receive scheduled ticks.

- [x] **Step 4: Run focused schema tests**

Run: `mvn -f backstage/pom.xml -Dtest=CollectPipelineSchemaInitializerTest,DatabaseIndexInitializerTest,SqliteSchemaPreflightTest test`

Expected: PASS; the historical row remains `PENDING` with `queue_generation IS NULL`.

- [x] **Step 5: Commit the schema boundary**

```bash
git add backstage/src/main/java/com/flower/spirit/config backstage/src/main/java/com/flower/spirit/entity backstage/src/test/java/com/flower/spirit/config
git commit -m "feat: add collection pipeline queue schema"
```

### Task 2: Build And Test The Pure Incremental Pagination Policy

**Files:**
- Create: `backstage/src/main/docker/buildx/script/douyin_incremental.py`
- Create: `backstage/src/test/python/test_douyin_incremental.py`

- [x] **Step 1: Write failing paginator tests**

Cover all stopping and diagnostic cases using an injected async page fetcher:

```python
class IncrementalPaginatorTest(unittest.IsolatedAsyncioTestCase):
    async def test_stops_after_known_streak_crosses_watermark(self):
        pages = [page([
            work("new-1", 200),
            work("known-1", 150),
            work("known-2", 140),
        ], has_more=1, cursor=100)]
        result = await paginate(fake_fetch(pages), known_ids={"known-1", "known-2"},
                                watermark=150, known_boundary=2,
                                max_pages=20, empty_page_limit=3,
                                mode="incremental")
        self.assertEqual("KNOWN_BOUNDARY", result["outcome"])
        self.assertEqual(["new-1"], result["newWorkIds"])

    async def test_empty_terminal_page_is_no_public_works(self):
        result = await paginate(fake_fetch([page([], has_more=0, cursor=0)]), set(), None,
                                20, 20, 3, "initial")
        self.assertEqual("NO_PUBLIC_WORKS", result["outcome"])

    async def test_three_empty_has_more_pages_are_empty_pagination(self):
        pages = [page([], has_more=1, cursor=i + 1) for i in range(3)]
        result = await paginate(fake_fetch(pages), set(), None, 20, 20, 3, "initial")
        self.assertEqual("EMPTY_PAGINATION", result["outcome"])

    async def test_null_aweme_list_is_works_unavailable(self):
        result = await paginate(fake_fetch([{"aweme_list": None, "has_more": 0}]), set(), None,
                                20, 20, 3, "initial")
        self.assertEqual("WORKS_UNAVAILABLE", result["outcome"])

    async def test_audit_ignores_known_boundary(self):
        result = await paginate(fake_fetch([
            page([work("known-1", 100)], has_more=1, cursor=1),
            page([work("new-2", 90)], has_more=0, cursor=2),
        ]), {"known-1"}, 100, 1, 20, 3, "audit")
        self.assertEqual("NO_MORE", result["outcome"])
        self.assertEqual(["new-2"], result["newWorkIds"])
```

- [x] **Step 2: Run the Python test and verify it fails**

Run: `python -m unittest discover -s backstage/src/test/python -p "test_douyin_incremental.py" -v`

Expected: FAIL with `ModuleNotFoundError: No module named 'douyin_incremental'`.

- [x] **Step 3: Implement normalized item and pagination functions**

The module must have no top-level F2 import so unit tests remain offline. Implement these public functions with the exact result keys:

```python
def normalize_aweme(aweme):
    author = aweme.get("author") or {}
    images = aweme.get("images") or []
    video = aweme.get("video") or {}
    cover = ((video.get("cover") or {}).get("url_list") or [])
    return {
        "aweme_id": str(aweme.get("aweme_id") or ""),
        "desc": aweme.get("desc") or "",
        "create_time": str(aweme.get("create_time") or ""),
        "nickname": author.get("nickname") or "",
        "uid": author.get("sec_uid") or "",
        "avatar_thumb": (((author.get("avatar_thumb") or {}).get("url_list") or [""])[0]),
        "cover": cover,
        "media_type": "image" if images else "video",
    }

async def paginate(fetch_page, known_ids, watermark, known_boundary, max_pages,
                   empty_page_limit, mode, max_items=0):
    observed, new_ids = [], []
    cursor, known_streak, empty_pages = 0, 0, 0
    diagnostics = {"pages": []}
    for page_number in range(1, max_pages + 1):
        raw = await fetch_page(cursor)
        aweme_list = raw.get("aweme_list") if isinstance(raw, dict) else None
        has_more = int(raw.get("has_more") or 0) if isinstance(raw, dict) else 0
        next_cursor = raw.get("max_cursor", cursor) if isinstance(raw, dict) else cursor
        diagnostics["pages"].append({"page": page_number, "cursor": str(cursor),
                                     "nextCursor": str(next_cursor), "hasMore": has_more,
                                     "awemeListState": "null" if aweme_list is None else "list",
                                     "itemCount": 0 if not aweme_list else len(aweme_list)})
        if aweme_list is None:
            return envelope(observed, new_ids, "WORKS_UNAVAILABLE", page_number,
                            empty_pages, next_cursor, diagnostics)
        if not aweme_list:
            empty_pages += 1
            if not has_more:
                outcome = "NO_PUBLIC_WORKS" if not observed else "NO_MORE"
                return envelope(observed, new_ids, outcome, page_number,
                                empty_pages, next_cursor, diagnostics)
            if empty_pages >= empty_page_limit:
                return envelope(observed, new_ids, "EMPTY_PAGINATION", page_number,
                                empty_pages, next_cursor, diagnostics)
            cursor = next_cursor
            continue
        empty_pages = 0
        for raw_work in aweme_list:
            item = normalize_aweme(raw_work)
            work_id = item["aweme_id"]
            known = work_id in known_ids
            item["knownAtFetch"] = known
            observed.append(item)
            if known:
                known_streak += 1
            else:
                known_streak = 0
                if work_id:
                    new_ids.append(work_id)
            if mode == "initial" and max_items > 0 and len(observed) >= max_items:
                return envelope(observed, new_ids, "INITIAL_LIMIT", page_number,
                                empty_pages, next_cursor, diagnostics)
            publish_time = int(item["create_time"] or 0)
            if (mode == "incremental" and known_streak >= known_boundary
                    and watermark is not None and publish_time <= int(watermark)):
                return envelope(observed, new_ids, "KNOWN_BOUNDARY", page_number,
                                empty_pages, next_cursor, diagnostics)
        if not has_more:
            return envelope(observed, new_ids, "NO_MORE", page_number,
                            empty_pages, next_cursor, diagnostics)
        cursor = next_cursor
    return envelope(observed, new_ids, "MAX_PAGE_GUARD", max_pages,
                    empty_pages, cursor, diagnostics)
```

Implement the result helper exactly as follows so duplicate IDs from upstream never create duplicate download candidates:

```python
def envelope(items, new_work_ids, outcome, pages_fetched, empty_pages,
             last_cursor, diagnostics):
    return {
        "items": items,
        "newWorkIds": list(dict.fromkeys(new_work_ids)),
        "outcome": outcome,
        "pagesFetched": pages_fetched,
        "emptyPages": empty_pages,
        "lastCursor": str(last_cursor),
        "diagnostics": diagnostics,
    }
```

- [x] **Step 4: Run the paginator tests**

Run: `python -m unittest discover -s backstage/src/test/python -p "test_douyin_incremental.py" -v`

Expected: PASS for known boundary, no-more, null list, empty-page guard, max-page guard, and audit mode.

- [x] **Step 5: Commit the pure pagination policy**

```bash
git add backstage/src/main/docker/buildx/script/douyin_incremental.py backstage/src/test/python/test_douyin_incremental.py
git commit -m "feat: add deterministic douyin incremental paginator"
```

### Task 3: Add The Project-Controlled F2 Command And Java Envelope Client

**Files:**
- Modify: `backstage/src/main/docker/buildx/script/douyin.py`
- Modify: `backstage/src/main/java/com/flower/spirit/utils/CommandUtil.java`
- Create: `backstage/src/main/java/com/flower/spirit/service/DouyinFetchMode.java`
- Create: `backstage/src/main/java/com/flower/spirit/service/DouyinFetchRequest.java`
- Create: `backstage/src/main/java/com/flower/spirit/service/DouyinFetchEnvelope.java`
- Create: `backstage/src/main/java/com/flower/spirit/service/DouyinIncrementalFetchService.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/DouyinIncrementalFetchServiceTest.java`

- [x] **Step 1: Write failing envelope and cleanup tests**

Inject a command runner into a package-private constructor. Verify object parsing, legacy-array compatibility, nonzero exit failure, malformed JSON diagnostics, and deletion of both temporary files:

```java
@Test
void parsesStructuredEnvelopeAndAlwaysDeletesTemporaryFiles() {
    runner.output = "stream-vault-ok\nstream-vault-fetch-outcome={\"outcome\":\"KNOWN_BOUNDARY\"}";
    runner.resultFile = """
            {"items":[{"aweme_id":"1","uid":"MS4-author","create_time":"100"}],
             "newWorkIds":["1"],"outcome":"KNOWN_BOUNDARY","pagesFetched":2,
             "emptyPages":0,"lastCursor":"99","diagnostics":{"pages":[]}}
            """;

    DouyinFetchEnvelope result = service.fetch(request(Set.of("old-1")));

    assertThat(result.outcome()).isEqualTo("KNOWN_BOUNDARY");
    assertThat(result.newWorkIds()).containsExactly("1");
    assertThat(runner.knownIdsFile).doesNotExist();
    assertThat(runner.outputFile).doesNotExist();
}
```

- [x] **Step 2: Run the client test and verify it fails**

Run: `mvn -f backstage/pom.xml -Dtest=DouyinIncrementalFetchServiceTest test`

Expected: FAIL because the typed protocol classes do not exist.

- [x] **Step 3: Implement the F2 command**

In `douyin.py`, import `DouyinCrawler`, `UserPost`, and `UserProfile`, then add a command whose closure performs one direct request per cursor:

```python
async def fetch_douyin_list_incremental(cookie, sec_user_id, known_ids_file,
                                        last_seen_publish_time, known_boundary,
                                        max_pages, empty_page_limit, mode, output_file):
    with open(known_ids_file, "r", encoding="utf-8") as handle:
        known_ids = set(json.load(handle))
    kwargs = douyin_kwargs(cookie)
    async with DouyinCrawler(kwargs) as crawler:
        async def fetch_page(cursor):
            params = UserPost(max_cursor=int(cursor), count=20, sec_user_id=sec_user_id)
            return await crawler.fetch_user_post(params)
        result = await paginate(fetch_page, known_ids,
                                int(last_seen_publish_time) if last_seen_publish_time else None,
                                int(known_boundary), int(max_pages), int(empty_page_limit), mode)
    if write_to_file(result, output_file):
        print("stream-vault-ok")
        print("stream-vault-fetch-outcome=" + json.dumps({
            "outcome": result["outcome"],
            "pagesFetched": result["pagesFetched"],
            "emptyPages": result["emptyPages"],
            "lastCursor": result["lastCursor"],
        }, ensure_ascii=False))
```

Register all arguments with `argparse`. Do not call `DouyinHandler.fetch_user_post_videos`; therefore the `nickname_raw` tail notification cannot replace a valid empty/partial result.

- [x] **Step 4: Implement the Java protocol and cleanup**

Use these exact types:

```java
public enum DouyinFetchMode { INITIAL, INCREMENTAL, AUDIT }

public record DouyinFetchRequest(String secUserId, Set<String> knownWorkIds,
        String lastSeenPublishTime, int knownBoundary, int maxPages,
        int emptyPageLimit, DouyinFetchMode mode, int maxItems) { }

public record DouyinFetchEnvelope(List<JSONObject> items, Set<String> newWorkIds,
        String outcome, int pagesFetched, int emptyPages, String lastCursor,
        JSONObject diagnostics) { }
```

`DouyinIncrementalFetchService.fetch` must write a JSON array of IDs, call `CommandUtil.f2IncrementalFetch(request, knownIdsFile, outputFile)`, require exit code `0`, read the result as UTF-8, validate every required envelope key, and delete both files in `finally`. Map outcomes to existing diagnostics without treating these successful outcomes as process failures: `NO_PUBLIC_WORKS`, `ACCOUNT_DEACTIVATED`, `WORKS_UNAVAILABLE`, `EMPTY_PAGINATION`, `KNOWN_BOUNDARY`, `INITIAL_LIMIT`, `NO_MORE`, and `MAX_PAGE_GUARD`.

The command list must pass each value as a separate `ProcessBuilder` argument and preserve cookie masking:

```java
List<String> command = List.of(
        "/opt/venv/bin/python3", "/home/app/script/douyin.py",
        "fetch_douyin_list_incremental", "--cookie", cookie,
        "--sec_user_id", request.secUserId(),
        "--known_ids_file", knownIdsFile.toString(),
        "--last_seen_publish_time", nullToEmpty(request.lastSeenPublishTime()),
        "--known_boundary", String.valueOf(request.knownBoundary()),
        "--max_pages", String.valueOf(request.maxPages()),
        "--empty_page_limit", String.valueOf(request.emptyPageLimit()),
        "--mode", request.mode().name().toLowerCase(Locale.ROOT),
        "--max_items", String.valueOf(request.maxItems()),
        "--output", outputFile.toString());
```

Before the first post page, request `UserProfile(sec_user_id=sec_user_id)` with the same crawler. If `special_state_info`, `user_not_see`, or the profile status text identifies a cancelled/deactivated account, write an empty `ACCOUNT_DEACTIVATED` envelope. Put a redacted profile status summary in `diagnostics`; never put the cookie or complete upstream payload there. A verification/login/captcha response must exit nonzero with structured code `F2_COOKIE_OR_VERIFY_REQUIRED`; a non-object response or missing required page keys must exit nonzero with `UPSTREAM_SCHEMA_ERROR`. These two conditions remain fetch failures and use the existing cookie-health/retry flow rather than becoming successful empty lists.

- [x] **Step 5: Run Python and Java protocol tests**

Run: `python -m unittest discover -s backstage/src/test/python -p "test_douyin_incremental.py" -v`

Run: `mvn -f backstage/pom.xml -Dtest=DouyinIncrementalFetchServiceTest test`

Expected: both PASS; logs include page/cursor/has-more/item-count diagnostics but never an unmasked cookie.

- [x] **Step 6: Commit the fetch protocol**

```bash
git add backstage/src/main/docker/buildx/script backstage/src/main/java/com/flower/spirit/utils/CommandUtil.java backstage/src/main/java/com/flower/spirit/service/DouyinFetch*.java backstage/src/main/java/com/flower/spirit/service/DouyinIncrementalFetchService.java backstage/src/test/java/com/flower/spirit/service/DouyinIncrementalFetchServiceTest.java
git commit -m "feat: add structured douyin fetch envelope"
```

### Task 4: Persist A Fetch Plan And Complete The Fetch Run Immediately

**Files:**
- Modify: `backstage/src/main/java/com/flower/spirit/service/CollectRunFetchedItem.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/CollectDataService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/CollectRunQueryService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/CollectRunService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/transaction/CollectQueueTransaction.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/CollectJobWorker.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/CollectTriggerType.java`
- Modify: `backstage/src/main/java/com/flower/spirit/config/CollectPipelineSchemaInitializer.java`
- Modify: `backstage/src/main/java/com/flower/spirit/config/DatabaseIndexInitializer.java`
- Modify: `backstage/src/main/java/com/flower/spirit/config/SqliteSchemaPreflight.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/transaction/DatabaseInitializationTransaction.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/CollectDataServiceFetchPlanTest.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/transaction/CollectQueueTransactionTest.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/CollectJobWorkerTest.java`

- [x] **Step 1: Write failing fetch-plan tests**

Assert these behaviors separately:

```java
@Test
void persistentRunStoresObservedItemsButQueuesOnlyNewWorks() {
    fetchClient.returns(envelope(List.of(work("new-1"), work("known-1")), Set.of("new-1")));
    service.executeQueuedCollectTask(7, 90L, CollectTriggerType.SCHEDULED);

    assertThat(items(90L)).extracting("workId", "processState", "queueGeneration")
            .containsExactly(
                    tuple("new-1", "QUEUED", "FETCH_DOWNLOAD_V1"),
                    tuple("known-1", "SKIPPED_EXISTING", null));
    assertThat(task(7).getLastSeenWorkId()).isEqualTo("new-1");
}

@Test
void persistentFetchDoesNotCallLegacyPerItemDownloader() {
    service.executeQueuedCollectTask(7, 90L, CollectTriggerType.SCHEDULED);
    verifyNoInteractions(videoDownloader, imageTextExecutorGateway);
}
```

Also assert that known IDs are the union of `biz_collect_data_detail.videoid` and active/new-generation run-item work IDs in `QUEUED`, `RUNNING`, `RETRY_WAIT`, and `COMPLETED`.

Add two more cases: a candidate already active in another run becomes `SKIPPED_EXISTING_ACTIVE_DOWNLOAD`, and an `AUDIT` run requeues a known work when its detail status is failed or its media row/file is missing.

- [x] **Step 2: Run focused tests and verify they fail**

Run: `mvn -f backstage/pom.xml -Dtest=CollectDataServiceFetchPlanTest,CollectQueueTransactionTest,CollectJobWorkerTest test`

Expected: FAIL because persistent execution still enters the old per-item download loop.

- [x] **Step 3: Extend the fetched-item contract**

Replace the record with:

```java
public record CollectRunFetchedItem(int ordinal, String platformKey, String workId,
        String authorUid, String nickname, String title, String publishTime,
        String mediaType, String decision, String processState) { }
```

For every observed envelope item, produce one run item. Use `newWorkIds` to choose `NEW/QUEUED`; known works use `EXISTING/SKIPPED_EXISTING`; blocked works use `BLOCKED/SKIPPED_BLOCKED`. If upstream repeats a work in the same run, retain the later observation as `DUPLICATE_OBSERVATION/SKIPPED_EXISTING`; only the first occurrence can be queued. Only `QUEUED` rows receive `available_at=now`, `max_attempts=4`, and `queue_generation='FETCH_DOWNLOAD_V1'`.

Replace the old unique `(run_id, platform_key, work_id)` index with a nonunique lookup index so repeated upstream observations remain auditable. Download deduplication remains enforced by the plan decision plus the active-work check, not by silently discarding observed rows.

- [x] **Step 4: Add fetch mode, known IDs, watermarks, and compatibility routing**

Add a new public method and keep `createDyData(entity, monitor)` unchanged for nonpersistent legacy calls:

```java
public void executeQueuedCollectTask(int taskId, long runId, CollectTriggerType triggerType) {
    CollectDataEntity task = collectdDataDao.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("collection task not found: " + taskId));
    if (!isDouyinPostTask(task)) {
        executeBoundedLegacyFetchAndPlan(task, runId);
        return;
    }
    DouyinFetchMode mode = triggerType == CollectTriggerType.AUDIT
            ? DouyinFetchMode.AUDIT
            : task.getLastSuccessfulFetchAt() == null
                    ? DouyinFetchMode.INITIAL : DouyinFetchMode.INCREMENTAL;
    Set<String> knownIds = collectRunQueryService.findKnownWorkIds(taskId);
    DouyinFetchEnvelope envelope = douyinIncrementalFetchService.fetch(new DouyinFetchRequest(
            sourceId(task), knownIds, task.getLastSeenPublishTime(), incrementalKnownBoundary,
            mode == DouyinFetchMode.AUDIT ? auditMaxPages : incrementalMaxPages,
            emptyPageLimit, mode, mode == DouyinFetchMode.INITIAL ? firstFetchLimit(task) : 0));
    List<CollectRunFetchedItem> plan = buildFetchPlan(task, envelope);
    FetchWatermark watermark = newestWatermark(envelope.items());
    collectRunService.storeFetchPlan(runId, taskId, plan, envelope.outcome(), watermark);
}
```

For `like`, `fav`, and `recommend`, retain current bounded F2 list retrieval but stop before media download and convert the returned list into the same run-item plan. Do not apply post-mode `sec_uid`, watermark, or known-boundary assumptions to those modes.

Reject non-Douyin tasks in `CollectEnqueueService` before creating a persistent run or job. Return an explicit unsupported result to manual and scheduled callers; retain the fetch-worker platform assertion only as defense for historical malformed queue rows.

`firstFetchLimit(task)` returns positive `omaxcur`, falling back to the existing initial default. `AUDIT` ignores this count and is guarded only by `auditMaxPages`. During audit planning, queue a work when it is newly observed, has a failed collection-detail status, lacks its expected `biz_video`/`biz_graphic_content` row, or references a missing/empty local file. Otherwise mark it `SKIPPED_EXISTING`. Before inserting any candidate, atomically check for another generation-tagged active item with the same `platform_key + work_id`; if present, mark the current row `SKIPPED_EXISTING_ACTIVE_DOWNLOAD` with no queue generation.

Implement `CollectRunQueryService.findKnownWorkIds(int taskId)` with two indexed queries and a `LinkedHashSet`: select nonblank `videoid` from `biz_collect_data_detail WHERE dataid=?`, then select `work_id` from generation-tagged items joined through `biz_collect_run.collect_task_id=?` where state is active or completed. This method is read-only and must not parse legacy snapshots.

- [x] **Step 5: Make plan persistence and run completion atomic at the database boundary**

Add `CollectRunService.storeFetchPlan(runId, taskId, items, stopReason, watermark)` as one `DatabaseWriteExecutor` operation calling one `@Transactional(REQUIRES_NEW)` transaction method. That method must:

1. Transition `FETCHING -> PROCESSING`.
2. Insert all run items with strict `INSERT`; any constraint or write failure rolls back the full plan and prevents watermark advancement.
3. Update `biz_collect_data` watermarks only after all inserts succeed.
4. Store the envelope outcome in `biz_collect_run.fetch_stop_reason`, store its nonfatal warning classification in `fetch_warning`, and append a `FETCH_STOP` run event containing page and cursor counts.

Change `CollectQueueTransaction.complete` counts so fetch completion reports observed/planned/skipped values without requiring any item to finish downloading:

```sql
SELECT COUNT(*) AS fetched,
       SUM(CASE WHEN process_state IN ('QUEUED','RUNNING','RETRY_WAIT','COMPLETED') THEN 1 ELSE 0 END) AS planned,
       SUM(CASE WHEN process_state IN ('SKIPPED_EXISTING','SKIPPED_EXISTING_ACTIVE_DOWNLOAD') THEN 1 ELSE 0 END) AS skipped,
       SUM(CASE WHEN process_state = 'FAILED' THEN 1 ELSE 0 END) AS failed
FROM biz_collect_run_item WHERE run_id = ?
```

Set compatibility `taskstatus` to `抓取完成，下载排队 N` and do not overwrite `carriedout` with a fetch-stage count.

- [x] **Step 6: Stop the fetch worker after persistence**

Pass `claim.triggerType()` into `executeQueuedCollectTask`. The worker sequence must be exactly:

```java
collectRunService.start(claim.runId());
collectDataService.executeQueuedCollectTask(claim.taskId(), claim.runId(), claim.triggerType());
collectRunService.complete(claim.runId(), claim.jobId());
```

The method returns after the plan commit. There must be no media HTTP request, sleep-per-item, FFmpeg, or filesystem write in the fetch worker.

- [x] **Step 7: Run fetch-stage tests**

Run: `mvn -f backstage/pom.xml -Dtest=CollectDataServiceFetchPlanTest,CollectQueueTransactionTest,CollectJobWorkerTest test`

Expected: PASS; the run is `COMPLETED` while its new item is still `QUEUED`.

- [x] **Step 8: Commit fetch-only execution**

```bash
git add backstage/src/main/java/com/flower/spirit/service backstage/src/main/java/com/flower/spirit/service/transaction backstage/src/test/java/com/flower/spirit/service
git commit -m "feat: complete collection runs after fetch planning"
```

### Task 5: Implement Atomic Fair Download Claiming, Retry, And Recovery

**Files:**
- Create: `backstage/src/main/java/com/flower/spirit/service/CollectDownloadClaim.java`
- Create: `backstage/src/main/java/com/flower/spirit/service/transaction/CollectDownloadTransaction.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/transaction/CollectDownloadTransactionTest.java`

- [ ] **Step 1: Write failing transaction tests**

Cover atomic claim, fair ordering, generation isolation, retry timing, final failure, manual retry, and stale recovery:

```java
@Test
void claimsOnlyNewGenerationAndOrdersByManualPriorityThenOrdinal() {
    insertItem(1, 10, 9, "PENDING", null, 0, 4, now, "old");
    insertItem(2, 10, 2, "QUEUED", "FETCH_DOWNLOAD_V1", 0, 4, now, "normal");
    insertItem(3, 11, 1, "QUEUED", "FETCH_DOWNLOAD_V1", 0, 4, now, "other-author");
    insertItem(4, 12, 8, "QUEUED", "FETCH_DOWNLOAD_V1", 0, 4, now, "manual");
    jdbc.update("UPDATE biz_collect_run_item SET decision='MANUAL_RETRY' WHERE id=4");

    assertThat(transaction.claimNext("worker-a", now).id()).isEqualTo(4L);
    assertThat(transaction.claimNext("worker-a", now).id()).isEqualTo(3L);
    assertThat(item(1).get("process_state")).isEqualTo("PENDING");
}

@Test
void retryScheduleIsOneFiveThirtyMinutesThenFailed() {
    CollectDownloadClaim claim = runningClaim(attemptCount);
    transaction.retryOrFail(claim, "NETWORK_IO", "unexpected end of stream", "stack", now);
    assertThat(stateAndAvailableAt(claim.id())).isEqualTo(expectedFor(attemptCount));
}
```

Use two transaction instances against the same SQLite file and assert only one can update the same selected row to `RUNNING`.

- [ ] **Step 2: Run the transaction test and verify it fails**

Run: `mvn -f backstage/pom.xml -Dtest=CollectDownloadTransactionTest test`

Expected: FAIL because claim/retry operations do not exist.

- [ ] **Step 3: Implement claim payload and guarded claim**

Use this immutable payload:

```java
public record CollectDownloadClaim(long id, long runId, int taskId, String taskName,
        String platformKey, String workId, String mediaType, int ordinal,
        int attemptCount, int maxAttempts) { }
```

`claimNext(workerId, now)` must select one eligible row and then issue a guarded update in the same short transaction:

```sql
SELECT i.id, i.run_id, r.collect_task_id, t.taskname, i.platform_key,
       i.work_id, i.media_type, i.ordinal, i.attempt_count, i.max_attempts
FROM biz_collect_run_item i
JOIN biz_collect_run r ON r.id=i.run_id
JOIN biz_collect_data t ON t.id=r.collect_task_id
WHERE i.queue_generation='FETCH_DOWNLOAD_V1'
  AND i.process_state IN ('QUEUED','RETRY_WAIT')
  AND (i.available_at IS NULL OR i.available_at<=?)
ORDER BY CASE WHEN i.decision='MANUAL_RETRY' THEN 0 ELSE 1 END,
         i.ordinal ASC, i.available_at ASC, i.created_at ASC, i.id ASC
LIMIT 1
```

```sql
UPDATE biz_collect_run_item
SET process_state='RUNNING', attempt_count=attempt_count+1,
    locked_by=?, locked_at=?, started_at=COALESCE(started_at, ?), updated_at=?
WHERE id=? AND queue_generation='FETCH_DOWNLOAD_V1'
  AND process_state IN ('QUEUED','RETRY_WAIT')
```

Return a claim only when the update count is one. Network and filesystem operations happen after this transaction commits.

- [ ] **Step 4: Implement terminal, retry, and recovery transitions**

Use delays indexed by the completed failed attempt:

```java
private static final List<Duration> RETRY_DELAYS = List.of(
        Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30));
```

If `claim.attemptCount() < claim.maxAttempts()`, transition `RUNNING -> RETRY_WAIT`, clear lock fields, set the corresponding `available_at`, and retain `error_code`, a 2048-character `error_message`, and a 10000-character `error_detail`. Otherwise transition to `FAILED` and set `finished_at`.

`recoverStale(staleBefore, now)` changes stale generation-tagged `RUNNING` rows to `RETRY_WAIT`, clears locks, sets `available_at=now`, and records `WORKER_RESTART_RECOVERY`. `manualRetry(itemId, now)` changes only `FAILED` to `QUEUED`, resets `attempt_count=0`, sets `decision='MANUAL_RETRY'`, and retains the prior error detail for audit. `retryFailedRun(runId, now)` performs the same guarded update for failed rows in that run.

- [ ] **Step 5: Run transaction tests**

Run: `mvn -f backstage/pom.xml -Dtest=CollectDownloadTransactionTest test`

Expected: PASS, including the old `PENDING` row never being claimable.

- [ ] **Step 6: Commit queue transactions**

```bash
git add backstage/src/main/java/com/flower/spirit/service/CollectDownloadClaim.java backstage/src/main/java/com/flower/spirit/service/transaction/CollectDownloadTransaction.java backstage/src/test/java/com/flower/spirit/service/transaction/CollectDownloadTransactionTest.java
git commit -m "feat: add persistent collection download claims"
```

### Task 6: Process One Work Through The Unified Ingest Pipeline

**Files:**
- Create: `backstage/src/main/java/com/flower/spirit/service/CollectDownloadException.java`
- Create: `backstage/src/main/java/com/flower/spirit/service/CollectDownloadService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/transaction/CollectDownloadTransaction.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/CollectDownloadServiceTest.java`

- [ ] **Step 1: Write failing one-item processing tests**

Mock `WorkIngestService` and test completed, duplicate, blocked, retriable network failure, and permanent validation failure. Most importantly, failure of work A must not touch work B:

```java
@Test
void oneNetworkFailureSchedulesOnlyThatItemForRetry() {
    when(ingestService.ingest(anyString(), any(Function.class), eq(false)))
            .thenThrow(new WorkMetadataValidationException(
                    "Douyin download failed", new IOException("unexpected end of stream")));

    service.process(claim("work-a"));

    verify(transaction).retryOrFail(argThat(c -> c.workId().equals("work-a")),
            eq("NETWORK_IO"), contains("unexpected end of stream"), anyString(), any());
    verify(transaction, never()).fail(any(CollectDownloadClaim.class), eq("AUTHOR_FETCH_FAILED"), any(), any(), any());
}
```

- [ ] **Step 2: Run the service test and verify it fails**

Run: `mvn -f backstage/pom.xml -Dtest=CollectDownloadServiceTest test`

Expected: FAIL because the per-work processor does not exist.

- [ ] **Step 3: Implement source URL, output directory, and ingest**

For Douyin, use a canonical work URL whose ID can be parsed for either video or graphic metadata:

```java
String source = "https://www.douyin.com/video/" + claim.workId();
Function<WorkMetadata, Path> directory = metadata -> Path.of(FileUtil.generateDir(
        true, metadata.getPlatformDisplayName(), false, null, claim.taskName(), null));
WorkIngestService.IngestResult result = workIngestService.ingest(source, directory, false);
```

Require `COMPLETED`; the current Douyin adapter is synchronous, so an unexpected `QUEUED` result becomes retryable `INGEST_NOT_TERMINAL` instead of being marked successful. `WorkIngestService` already refreshes signed detail/media URLs, stages files, verifies nonempty resources, atomically promotes them, persists media, and updates the canonical author profile.

- [ ] **Step 4: Link the completed work back to its collection task**

After ingest commits, call a short `CollectDownloadTransaction.complete(claim, result, now)` that:

1. Inserts or updates one `biz_collect_data_detail` row keyed by `dataid + videoid` with media type, title, `已完成`, and a concise process log.
2. Transitions only the claimed item `RUNNING -> COMPLETED` and clears lock fields.
3. Recalculates `biz_collect_data.carriedout` from successful detail rows instead of incrementing a stale Java counter.

If ingest reports an existing media record, use `SKIPPED_EXISTING` rather than `COMPLETED`, but still ensure the collection-detail link exists.

- [ ] **Step 5: Classify per-work errors without leaking them to the fetch worker**

`CollectDownloadException` carries `errorCode`, `retryable`, and root message. Map `IOException`, OkHttp `IllegalStateException`, `unexpected end of stream`, timeout, empty media, and detail refresh failures to retryable codes. Map blocked work to `SKIPPED_BLOCKED`; unsupported platform/schema validation is terminal. Save stack summaries through the transaction and return normally from `process` after recording the state.

- [ ] **Step 6: Run one-item tests**

Run: `mvn -f backstage/pom.xml -Dtest=CollectDownloadServiceTest,MediaDownloadServiceTest test`

Expected: PASS; staging rollback behavior remains green.

- [ ] **Step 7: Commit the per-work processor**

```bash
git add backstage/src/main/java/com/flower/spirit/service/CollectDownloadException.java backstage/src/main/java/com/flower/spirit/service/CollectDownloadService.java backstage/src/main/java/com/flower/spirit/service/transaction/CollectDownloadTransaction.java backstage/src/test/java/com/flower/spirit/service/CollectDownloadServiceTest.java
git commit -m "feat: process collection downloads per work"
```

### Task 7: Run Fetch And Download Workers Independently

**Files:**
- Create: `backstage/src/main/java/com/flower/spirit/service/CollectDownloadWorker.java`
- Modify: `backstage/src/main/java/com/flower/spirit/task/TaskService.java`
- Modify: `backstage/src/main/resources/application-docker.properties`
- Modify: `backstage/src/main/resources/application-dev.properties`
- Test: `backstage/src/test/java/com/flower/spirit/service/CollectDownloadWorkerTest.java`
- Test: `backstage/src/test/java/com/flower/spirit/task/TaskServiceTest.java`

- [ ] **Step 1: Write failing worker and pause-isolation tests**

```java
@Test
void pausedDownloadsDoNotPreventFetchWakeup() {
    when(runtime.mayRun(TaskCategory.COLLECT_FETCH)).thenReturn(PauseDecision.allowed());
    when(runtime.mayRun(TaskCategory.MEDIA_DOWNLOAD)).thenReturn(PauseDecision.paused("pause.download"));

    taskService.collectQueueTick();

    verify(fetchWorker).wakeUp();
    verifyNoInteractions(downloadWorker);
}

@Test
void failedItemDoesNotStopNextClaim() {
    when(transaction.claimNext(anyString(), any())).thenReturn(first, second, null);
    worker.processAvailable();
    verify(service).process(first);
    verify(service).process(second);
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run: `mvn -f backstage/pom.xml -Dtest=CollectDownloadWorkerTest,TaskServiceTest test`

Expected: FAIL because `TaskService.collectQueueTick()` currently requires both pause categories and no download worker exists.

- [ ] **Step 3: Implement one conservative download worker**

Mirror `CollectJobWorker` lifecycle with its own single-thread executor, worker ID, running guard, and shutdown. On first wake after startup, recover stale locks older than the configured threshold. A tick may process up to a bounded batch so it does not monopolize the scheduler:

```java
public void processAvailable() {
    if (!runtimeControlService.mayRun(TaskCategory.MEDIA_DOWNLOAD).allowed()) return;
    for (int processed = 0; processed < batchSize; processed++) {
        CollectDownloadClaim claim = databaseWriteExecutor.execute("collect-download-claim",
                () -> transaction.claimNext(workerId, Instant.now()));
        if (claim == null) return;
        downloadService.process(claim);
        if (!runtimeControlService.mayRun(TaskCategory.MEDIA_DOWNLOAD).allowed()) return;
    }
}
```

No network, filesystem, F2, sleep, or media processing may be wrapped in `DatabaseWriteExecutor`; only claim/transition calls use it.

- [ ] **Step 4: Separate scheduler pause gates**

Replace the existing combined guard with:

```java
@Scheduled(fixedDelayString = "${streamvault.collect-queue.poll-delay-ms:5000}")
public void collectQueueTick() {
    if (runtimeControlService.mayRun(TaskCategory.COLLECT_FETCH).allowed()) {
        collectJobWorker.wakeUp();
    }
    if (runtimeControlService.mayRun(TaskCategory.MEDIA_DOWNLOAD).allowed()) {
        collectDownloadWorker.wakeUp();
    }
}
```

Add conservative defaults to Docker and dev properties:

```properties
streamvault.collect.fetch-workers=1
streamvault.collect.download-workers=1
streamvault.collect.download-batch-size=10
streamvault.collect.download-lock-timeout-minutes=30
streamvault.collect.download-max-retries=3
streamvault.collect.incremental-known-boundary=20
streamvault.collect.incremental-max-pages=20
streamvault.collect.audit-max-pages=500
streamvault.collect.empty-page-limit=3
```

Validate `fetch-workers` and `download-workers` are exactly `1` for this release; log a warning and clamp larger values rather than opening unsafe SQLite/Cookie concurrency.

- [ ] **Step 5: Run worker tests**

Run: `mvn -f backstage/pom.xml -Dtest=CollectDownloadWorkerTest,TaskServiceTest test`

Expected: PASS; fetch can run while downloads are paused and vice versa.

- [ ] **Step 6: Commit independent workers**

```bash
git add backstage/src/main/java/com/flower/spirit/service/CollectDownloadWorker.java backstage/src/main/java/com/flower/spirit/task/TaskService.java backstage/src/main/resources/application-docker.properties backstage/src/main/resources/application-dev.properties backstage/src/test/java/com/flower/spirit/service/CollectDownloadWorkerTest.java backstage/src/test/java/com/flower/spirit/task/TaskServiceTest.java
git commit -m "feat: run collection fetch and download independently"
```

### Task 8: Expose Split Fetch/Download Status And Queue Diagnostics

**Files:**
- Modify: `backstage/src/main/java/com/flower/spirit/dto/CollectTaskListItem.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/CollectDataService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/CollectRunQueryService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/RuntimeJobQueryService.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/CollectRunQueryServiceTest.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/RuntimeJobQueryServiceTest.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/CollectDataServiceFindPageTest.java`

- [ ] **Step 1: Write failing projection tests**

Seed one completed fetch run with items in every download state and assert the task row contains:

```java
assertThat(item).extracting(
        CollectTaskListItem::fetchState,
        CollectTaskListItem::downloadQueued,
        CollectTaskListItem::downloadRunning,
        CollectTaskListItem::downloadRetryWait,
        CollectTaskListItem::downloadCompleted,
        CollectTaskListItem::downloadSkipped,
        CollectTaskListItem::downloadFailed,
        CollectTaskListItem::latestStopReason)
        .containsExactly("COMPLETED", 2L, 1L, 1L, 9L, 30L, 1L, "KNOWN_BOUNDARY");
```

Assert run-item rows include `attemptCount`, `maxAttempts`, `availableAt`, `startedAt`, `finishedAt`, `errorDetail`, and `queueGeneration`.

- [ ] **Step 2: Run query tests and verify they fail**

Run: `mvn -f backstage/pom.xml -Dtest=CollectRunQueryServiceTest,RuntimeJobQueryServiceTest,CollectDataServiceFindPageTest test`

Expected: FAIL because only legacy run/job fields are projected.

- [ ] **Step 3: Extend task and run projections**

Append these exact fields to `CollectTaskListItem`:

```java
String fetchState, long downloadQueued, long downloadRunning, long downloadRetryWait,
long downloadCompleted, long downloadSkipped, long downloadFailed,
String latestStopReason, String latestFetchWarning
```

Join a latest-run item aggregate with conditional `SUM` expressions. Keep `jobState`, `runState`, queue position, and heartbeat for compatibility. `findItems` must select every queue timing/attempt/error field and still paginate by `id`.

- [ ] **Step 4: Add download queue diagnostics**

Add:

```java
public Map<String, Object> downloadQueue(Integer taskId, int limit) {
    // counts by process_state plus oldest eligible items ordered by claim order
}
```

Return `counts`, `items`, `oldestQueuedAt`, and `nextRetryAt`. Do not return raw snapshot JSON. Extend `RuntimeJobQueryService.dashboard` with `fetchQueue` from `biz_job_queue`, `downloadQueue` from generation-tagged run items, and the existing HLS summary supplied by its current service/controller integration.

- [ ] **Step 5: Run query tests**

Run: `mvn -f backstage/pom.xml -Dtest=CollectRunQueryServiceTest,RuntimeJobQueryServiceTest,CollectDataServiceFindPageTest test`

Expected: PASS and query plans use `idx_collect_run_item_run_state` or `idx_collect_run_item_download_claim`.

- [ ] **Step 6: Commit observable query models**

```bash
git add backstage/src/main/java/com/flower/spirit/dto/CollectTaskListItem.java backstage/src/main/java/com/flower/spirit/service/CollectDataService.java backstage/src/main/java/com/flower/spirit/service/CollectRunQueryService.java backstage/src/main/java/com/flower/spirit/service/RuntimeJobQueryService.java backstage/src/test/java/com/flower/spirit/service
git commit -m "feat: expose collection download queue status"
```

### Task 9: Add Manual Retry And Full-Audit APIs

**Files:**
- Modify: `backstage/src/main/java/com/flower/spirit/service/CollectTriggerType.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/CollectEnqueueService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/CollectRunService.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/transaction/CollectQueueTransaction.java`
- Modify: `backstage/src/main/java/com/flower/spirit/web/admin/AdminController.java`
- Test: `backstage/src/test/java/com/flower/spirit/web/admin/AdminControllerCollectPipelineTest.java`

- [ ] **Step 1: Write failing controller tests**

```java
mockMvc.perform(post("/admin/api/collectData/retryItem").param("id", "41"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.processState").value("QUEUED"));
mockMvc.perform(post("/admin/api/collectData/retryFailedItems").param("runId", "9"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.requeued").value(3));
mockMvc.perform(post("/admin/api/collectData/audit").param("taskId", "7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("QUEUED"));
```

- [ ] **Step 2: Run controller tests and verify they fail**

Run: `mvn -f backstage/pom.xml -Dtest=AdminControllerCollectPipelineTest test`

Expected: FAIL with 404 for all three endpoints.

- [ ] **Step 3: Add audit enqueue semantics**

Add `AUDIT` to `CollectTriggerType` and `CollectEnqueueService.enqueueAudit(taskId)`, with priority `10`. Reuse active-task dedupe so an audit cannot race an existing fetch run. In `retryOrFailJob`, preserve `AUDIT` when retrying an audit run; ordinary failed runs continue with `RETRY`.

- [ ] **Step 4: Add guarded endpoints**

Implement:

```java
@PostMapping("/collectData/retryItem")
public AjaxEntity retryCollectDownloadItem(@RequestParam long id) {
    return new AjaxEntity(Global.ajax_success, "下载项已重新排队",
            collectRunService.retryDownloadItem(id));
}

@PostMapping("/collectData/retryFailedItems")
public AjaxEntity retryCollectDownloadItems(@RequestParam long runId) {
    return new AjaxEntity(Global.ajax_success, "失败下载项已重新排队",
            Map.of("requeued", collectRunService.retryFailedDownloads(runId)));
}

@PostMapping("/collectData/audit")
public AjaxEntity auditCollectTask(@RequestParam int taskId) {
    return new AjaxEntity(Global.ajax_success, "全量审计已排队",
            collectEnqueueService.enqueueAudit(taskId));
}

@GetMapping("/collectData/downloadQueue")
public AjaxEntity collectDownloadQueue(@RequestParam(required=false) Integer taskId,
        @RequestParam(defaultValue="100") int limit) {
    return new AjaxEntity(Global.ajax_success, "下载队列获取成功",
            collectRunQueryService.downloadQueue(taskId, limit));
}
```

Add `CollectRunService.retryDownloadItem(long)` and `retryFailedDownloads(long)` facades; each calls `CollectDownloadTransaction` through `DatabaseWriteExecutor` and returns the newly persisted state/count. This keeps controllers out of transaction implementation details.

Retry endpoints reject nonfailed or old-generation rows. Audit returns the existing active run rather than creating a duplicate. Use the existing authenticated `/admin/api` controller boundary and `AjaxEntity` response conventions.

- [ ] **Step 5: Run endpoint tests**

Run: `mvn -f backstage/pom.xml -Dtest=AdminControllerCollectPipelineTest,CollectQueueTransactionTest,CollectDownloadTransactionTest test`

Expected: PASS, including duplicate audit prevention.

- [ ] **Step 6: Commit pipeline controls**

```bash
git add backstage/src/main/java/com/flower/spirit/service backstage/src/main/java/com/flower/spirit/service/transaction backstage/src/main/java/com/flower/spirit/web/admin/AdminController.java backstage/src/test/java/com/flower/spirit/web/admin/AdminControllerCollectPipelineTest.java
git commit -m "feat: add collection audit and retry controls"
```

### Task 10: Update The Collection Page Without Reintroducing Raw JSON

**Files:**
- Modify: `backstage/src/main/resources/templates/admin/collectDataList.html`
- Modify: `backstage/src/test/java/com/flower/spirit/web/admin/AdminTemplateScriptSanityTest.java`

- [ ] **Step 1: Add failing template sanity assertions**

```java
assertThat(template).contains("downloadQueued", "downloadRetryWait", "downloadFailed");
assertThat(template).contains("retryCollectItem", "retryFailedRun", "startFullAudit");
assertThat(template).contains("attemptCount", "availableAt", "errorDetail");
assertThat(template).doesNotContain("JSON.stringify(record.items)");
```

- [ ] **Step 2: Run the template test and verify it fails**

Run: `mvn -f backstage/pom.xml -Dtest=AdminTemplateScriptSanityTest test`

Expected: FAIL because the page does not render split-stage fields or new controls.

- [ ] **Step 3: Render split status in the task table**

Keep the existing compact table and render two lines in the status cell:

```javascript
function buildCollectRuntimeStatus(item) {
    var fetch = stateLabel(item.fetchState || item.runState || item.jobState);
    var download = '待下载 ' + number(item.downloadQueued)
        + ' / 下载中 ' + number(item.downloadRunning)
        + ' / 重试 ' + number(item.downloadRetryWait)
        + ' / 完成 ' + number(item.downloadCompleted)
        + ' / 跳过 ' + number(item.downloadSkipped)
        + ' / 失败 ' + number(item.downloadFailed);
    return '<div>抓取：' + escapeHtml(fetch) + '</div>'
        + '<div class="text-muted">下载：' + escapeHtml(download) + '</div>';
}
```

Show `latestStopReason` and `latestFetchWarning` as short diagnostic text, not as a false risk-control status.

- [ ] **Step 4: Extend plan/full tables and actions**

The full list continues to show every observed item. The plan list shows queued/running/retry/completed/skipped/failed rows with columns: work ID, title, publish time, media type, process state, attempts (`attemptCount/maxAttempts`), next retry, last error, and updated time. Add a retry icon button only for `FAILED` rows and a “重试失败项” command for a run.

Add “全量审计” to a task's action menu. The first click opens a confirmation dialog stating that it ignores the incremental boundary and may make many upstream requests; only the confirmation callback posts `/admin/api/collectData/audit`.

- [ ] **Step 5: Add incremental table loading**

Retain table rendering and request subsequent pages with `afterId` when a response has `hasMore=true`. Never put an entire JSON array into a `<pre>` block. Keep row insertion incremental so a large audit becomes visible as data arrives.

- [ ] **Step 6: Run template and service projection tests**

Run: `mvn -f backstage/pom.xml -Dtest=AdminTemplateScriptSanityTest,CollectRunQueryServiceTest,CollectDataServiceFindPageTest test`

Expected: PASS; no JavaScript reference errors and no raw JSON fallback for current run items.

- [ ] **Step 7: Commit the administration UI**

```bash
git add backstage/src/main/resources/templates/admin/collectDataList.html backstage/src/test/java/com/flower/spirit/web/admin/AdminTemplateScriptSanityTest.java
git commit -m "feat: show collection fetch and download stages"
```

### Task 11: Pin F2 And Log The Runtime Version

**Files:**
- Modify: `backstage/src/main/docker/buildx/Dockerfile`
- Modify: `backstage/src/main/docker/buildx/Dockerfile.dev`
- Modify: `backstage/src/main/docker/buildx/Dockerfile.jre17`
- Modify: `backstage/src/main/docker/buildx/Dockerfile.multi`
- Modify: `backstage/src/main/docker/buildx/Dockerfile.ubuntu`
- Create: `backstage/src/main/java/com/flower/spirit/config/F2RuntimeVersionLogger.java`
- Test: `backstage/src/test/java/com/flower/spirit/config/F2RuntimeVersionLoggerTest.java`

- [ ] **Step 1: Write failing pin and logger tests**

Read every Dockerfile and assert all F2 installs are exact pins:

```java
for (Path dockerfile : dockerfiles) {
    String text = Files.readString(dockerfile);
    assertThat(text).doesNotContain("pip install --no-cache-dir f2\n");
    assertThat(text).contains("f2==0.0.1.7");
}
```

Inject a command runner into `F2RuntimeVersionLogger` and assert startup log data contains the package version or a clear `unavailable` warning without failing application startup.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `mvn -f backstage/pom.xml -Dtest=F2RuntimeVersionLoggerTest test`

Expected: FAIL because Dockerfiles install floating `f2` and no runtime logger exists.

- [ ] **Step 3: Pin and expose version metadata**

Use these exact install/version commands. For `Dockerfile`, `Dockerfile.jre17`, and `Dockerfile.ubuntu`:

```dockerfile
RUN /opt/venv/bin/pip install --no-cache-dir f2==0.0.1.7 \
    && /opt/venv/bin/python3 -c "import importlib.metadata; print('f2=' + importlib.metadata.version('f2'))"
```

For `Dockerfile.dev`, retain its activated virtual environment but call the absolute binaries:

```dockerfile
RUN python3 -m venv /opt/venv && \
    /opt/venv/bin/pip install --no-cache-dir f2==0.0.1.7 && \
    /opt/venv/bin/python3 -c "import importlib.metadata; print('f2=' + importlib.metadata.version('f2'))"
```

For `Dockerfile.multi`, which installs into the builder's system Python:

```dockerfile
RUN pip install --upgrade pip && \
    pip install --no-cache-dir f2==0.0.1.7 && \
    python3 -c "import importlib.metadata; print('f2=' + importlib.metadata.version('f2'))"
```

`F2RuntimeVersionLogger` checks `/opt/venv/bin/python3` first and `/usr/local/bin/python3` second, runs the metadata expression on `ApplicationReadyEvent`, and logs `pythonPath`, `scriptPath`, and exact version. If neither exists or metadata lookup fails, log one `unavailable` warning without failing application startup. Mask process output if it ever contains sensitive tokens.

- [ ] **Step 4: Run logger and protocol tests**

Run: `mvn -f backstage/pom.xml -Dtest=F2RuntimeVersionLoggerTest,DouyinIncrementalFetchServiceTest test`

Expected: PASS.

- [ ] **Step 5: Build the primary Docker image through the existing build workflow**

Run the repository's existing buildx command from `.github/workflows` or documented release script, then run:

```bash
docker run --rm streamvault-collect-pipeline:test /opt/venv/bin/python3 -c "import importlib.metadata; print(importlib.metadata.version('f2'))"
```

Expected: output exactly `0.0.1.7`.

- [ ] **Step 6: Commit reproducible F2 runtime**

```bash
git add backstage/src/main/docker/buildx backstage/src/main/java/com/flower/spirit/config/F2RuntimeVersionLogger.java backstage/src/test/java/com/flower/spirit/config/F2RuntimeVersionLoggerTest.java
git commit -m "build: pin and report f2 runtime version"
```

### Task 12: Add End-To-End SQLite Regression Coverage

**Files:**
- Create: `backstage/src/test/java/com/flower/spirit/service/CollectPipelineIntegrationTest.java`
- Modify: `backstage/src/test/java/com/flower/spirit/service/transaction/CollectQueueTransactionTest.java`
- Modify: `backstage/src/test/java/com/flower/spirit/service/MediaDownloadServiceTest.java`

- [ ] **Step 1: Write the integration test around fake upstream and media gateways**

Use a real temporary SQLite WAL database and fakes for the list fetch, detail refresh, and media download. The test scenario must be:

1. Fetch author A and persist two queued works.
2. Complete the fetch run while both items remain queued.
3. Fetch author B before author A finishes downloading.
4. Fail A1 with `unexpected end of stream`.
5. Complete B1 and A2.
6. Advance the test clock one minute and complete the retry of A1.
7. Restart worker objects, recover one stale `RUNNING` item, and finish it once.

Core assertions:

```java
assertThat(fetchRunState(authorARun)).isEqualTo("COMPLETED");
assertThat(fetchRunState(authorBRun)).isEqualTo("COMPLETED");
assertThat(downloadStates(authorARun)).containsExactlyInAnyOrder("COMPLETED", "COMPLETED");
assertThat(downloadStates(authorBRun)).containsExactly("COMPLETED");
assertThat(mediaRowCount("A1")).isEqualTo(1);
assertThat(oldPendingItemState()).isEqualTo("PENDING");
```

- [ ] **Step 2: Run the integration test and verify it catches incomplete wiring**

Run: `mvn -f backstage/pom.xml -Dtest=CollectPipelineIntegrationTest test`

Expected before final wiring: FAIL at the first missing stage transition, retry, or detail link.

- [ ] **Step 3: Complete only the wiring exposed by the integration test**

Ensure all writes use existing `DatabaseWriteExecutor` coordination, no external call is inside a transaction, each media work is persisted once, and old rows with null generation remain inert. Do not increase connection pool or worker counts to make the test pass.

- [ ] **Step 4: Run the complete focused suite**

Run:

```bash
mvn -f backstage/pom.xml -Dtest=CollectPipelineIntegrationTest,CollectQueueTransactionTest,CollectDownloadTransactionTest,CollectDownloadServiceTest,CollectJobWorkerTest,CollectDownloadWorkerTest,CollectRunQueryServiceTest,RuntimeJobQueryServiceTest,AdminControllerCollectPipelineTest,AdminTemplateScriptSanityTest,DatabaseIndexInitializerTest,SqliteSchemaPreflightTest test
```

Expected: PASS with no `database is locked`, duplicate media, or old-item replay.

- [ ] **Step 5: Run all backend tests**

Run: `mvn -f backstage/pom.xml test`

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit integration coverage**

```bash
git add backstage/src/test/java/com/flower/spirit
git commit -m "test: cover collection fetch download pipeline"
```

### Task 13: Production-Copy Migration And Release Verification

**Files:**
- Create: `docs/runbooks/collect-fetch-download-pipeline-release.md`
- Modify: `README.md` only if it already links deployment runbooks; otherwise leave it unchanged.

- [ ] **Step 1: Write the release runbook with exact non-destructive checks**

Document these commands and expected results:

```bash
sqlite3 spirit-copy.db "PRAGMA quick_check;"
# expected: ok

sqlite3 spirit-copy.db "SELECT process_state, queue_generation, COUNT(*) FROM biz_collect_run_item GROUP BY process_state, queue_generation ORDER BY 1,2;"
# expected immediately after migration: historical rows keep queue_generation NULL

sqlite3 spirit-copy.db "PRAGMA index_list('biz_collect_run_item');"
# expected: idx_collect_run_item_download_claim, idx_collect_run_item_active_work, idx_collect_run_item_run_state
```

Include backup requirements for `spirit.db`, `spirit.db-wal`, and `spirit.db-shm`; pause fetch/download/HLS before backup; record hashes; and never run migration directly against the repository's copied production database.

- [ ] **Step 2: Test startup against a disposable production-database copy**

Start the built container with the copied DB and media paths mounted read/write only inside a disposable directory. Expected startup logs:

```text
Collection pipeline schema initialization completed
SQLite pipeline preflight passed
F2 runtime version=0.0.1.7
Collection download recovery recovered=0 (or the exact stale count)
```

Expected database result: existing media/detail/author rows are unchanged; only nullable schema columns and indexes are added.

- [ ] **Step 3: Perform the small-task smoke test**

With the global schedule interval left at the user-configured six hours:

1. Resume fetch only and manually enqueue one small author task.
2. Confirm its run becomes `COMPLETED` immediately after plan persistence.
3. Confirm the download queue continues while the next author's fetch can start.
4. Force one disposable media URL failure and confirm only that item reaches `RETRY_WAIT`.
5. Pause downloads and confirm fetch still runs; pause fetch and confirm an already queued download still runs.
6. Resume all categories and verify HLS receives only committed media.

- [ ] **Step 4: Verify incremental behavior on the second run**

Run the same author again. Logs must show each page's cursor, `has_more`, and observed count; the stop reason must be `KNOWN_BOUNDARY` after 20 consecutive known works at or before the stored publish watermark. It must not request `historical success count + monitor window` works.

- [ ] **Step 5: Verify abnormal author outcomes**

Use known test fixtures or mocked responses for:

- Empty list plus `has_more=0` -> `NO_PUBLIC_WORKS`, successful fetch with zero items.
- Deactivated profile -> `ACCOUNT_DEACTIVATED`, warning without rapid retry.
- `aweme_list=null` -> `WORKS_UNAVAILABLE`, diagnostic warning.
- Three empty pages with `has_more=1` -> `EMPTY_PAGINATION`.
- Cookie/verification response -> `F2_COOKIE_OR_VERIFY_REQUIRED` and existing cookie-health alert flow.

No case may surface `nickname_raw was initialized` as the root cause.

- [ ] **Step 6: Commit the release runbook**

```bash
git add docs/runbooks/collect-fetch-download-pipeline-release.md README.md
git commit -m "docs: add collection pipeline release runbook"
```

## Final Verification Checklist

- [ ] Run `python -m unittest discover -s backstage/src/test/python -p "test_douyin_incremental.py" -v`; expect all tests PASS.
- [ ] Run `mvn -f backstage/pom.xml test`; expect BUILD SUCCESS.
- [ ] Run `git diff --check`; expect no output.
- [ ] Search `rg -n "pip install --no-cache-dir f2($|\\s)" backstage/src/main/docker/buildx`; expect no unpinned install.
- [ ] Search `rg -n "queue_generation='FETCH_DOWNLOAD_V1'|queue_generation = 'FETCH_DOWNLOAD_V1'" backstage/src/main/java`; verify every download claim/recovery/retry path is generation-gated.
- [ ] Search `rg -n "fetch_user_post_videos" backstage/src/main/docker/buildx/script/douyin.py`; verify the legacy command remains only for bounded compatibility and the incremental post path uses `DouyinCrawler.fetch_user_post` directly.
- [ ] Review all `@Transactional` methods touched by this feature; verify none call F2, HTTP, sleep, FFmpeg, or filesystem download operations.
- [ ] Confirm the implementation does not modify the user's six-hour global collection interval.
- [ ] Confirm there are still exactly one fetch worker and one download worker by default.
- [ ] Confirm no PostgreSQL or Redis dependency/configuration was introduced.
- [ ] Confirm old `PENDING` rows with null `queue_generation` remain unclaimed after startup and smoke testing.

## Spec Coverage Self-Review

- Fetch/download stage split: Tasks 4, 6, and 7.
- True post pagination and known boundary: Tasks 2 through 4.
- Initial, incremental, and explicit audit modes: Tasks 2, 4, and 9.
- `like`/`fav`/`recommend` bounded compatibility: Task 4.
- Root-cause F2 outcomes and removal of `nickname_raw` masking: Tasks 2, 3, and 13.
- One-minute/five-minute/thirty-minute retry with four total attempts: Tasks 5 and 6.
- Fair author ordering and active-work safety: Tasks 4 and 5.
- Restart recovery and no historical replay: Tasks 1, 5, 7, and 12.
- SQLite short serialized writes and future PostgreSQL claim boundary: Tasks 5 through 7.
- Split API/UI status, tabular plans, retry, and audit controls: Tasks 8 through 10.
- Fixed F2 runtime: Task 11.
- Production-copy migration safety and rollout: Task 13.

The plan contains no deferred implementation placeholders. Type names and signatures are consistent across tasks: `DouyinFetchRequest`, `DouyinFetchEnvelope`, `CollectRunFetchedItem`, `CollectDownloadClaim`, `CollectDownloadTransaction`, and `CollectDownloadWorker` retain the same fields and responsibilities from definition through integration testing.
