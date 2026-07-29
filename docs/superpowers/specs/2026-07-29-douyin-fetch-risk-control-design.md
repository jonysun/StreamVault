# Douyin Author-Work Fetch Risk-Control Design

Date: 2026-07-29

## Problem

Production author-work fetches intermittently fail with an empty HTTP response from the Douyin user-post endpoint. F2 retries the request five times and raises `APIRetryExhaustedError`, but the Python command currently reports `UPSTREAM_SCHEMA_ERROR`. The Java command boundary then wraps every nonzero exit as `IllegalStateException`, so the persistent worker stores `UNEXPECTED` and applies the wrong retry policy.

The production database and logs show the failure is systemic rather than author-specific:

- task 63 completed at 2026-07-28 22:31:40;
- task 64 began the first 42-second empty-response failure at 22:31:44;
- 833 runs contain `APIRetryExhaustedError`;
- tasks 82, 83, and 84 temporarily succeeded on 2026-07-29 around 09:14-09:15;
- the configured Cookie pool contains one Cookie and uses `round_robin`, which does not honor the existing risk cooldown.

This behavior is consistent with endpoint-level rate limiting or risk control. It is not caused by SQLite corruption, deleted author works, or a permanent response-schema change.

## Goals

1. Use the F2 X-Bogus signing path for author-work page requests, matching the diagnostic request path that can receive valid JSON responses.
2. Preserve structured Python failure codes across the process boundary.
3. Distinguish upstream empty responses and timeouts from JSON schema failures.
4. Cool down a Cookie after a risk response under both `round_robin` and `risk_shift`.
5. Avoid sending requests when every configured Cookie is cooling.
6. Apply a one-hour persistent job retry delay to risk-control failures.
7. Keep all changes database-independent so the planned PostgreSQL migration does not require a rewrite.

## Non-goals

- Installing the complete unreleased F2 `main` branch.
- Increasing fetch or download worker concurrency.
- Changing incremental pagination, batch limits, newest-first selection, or download ordering.
- Mutating or repairing the copied production database.
- Adding SQLite-only tables, triggers, or locking behavior.

## Selected Approach

### Author-work signing

Only the incremental author-work command selects F2's `XBogusManager` for `fetch_user_profile` and `fetch_user_post`. Other F2 commands retain their existing signing configuration. The command does not automatically fall back to a second signature request after a failed request because a double request would amplify risk control.

The signature override is scoped to the crawler instance used by `fetch_douyin_list_incremental`; it does not modify F2 package files at runtime and does not require installing an unreleased F2 build.

### Python error protocol

The command emits one of these codes:

| Error code | Meaning | Retry class |
| --- | --- | --- |
| `F2_UPSTREAM_RATE_LIMIT` | F2 exhausted retries after empty responses, or Douyin returned an explicit risk/rate-limit signal | one-hour cooldown |
| `F2_UPSTREAM_TIMEOUT` | Network timeout without an explicit risk signal | normal transient retry |
| `F2_COOKIE_OR_VERIFY_REQUIRED` | Login or interactive verification is required | one-hour cooldown |
| `UPSTREAM_SCHEMA_ERROR` | A nonempty JSON response violates the required schema | normal error investigation |

The diagnostic payload remains bounded and Cookie-redacted. For F2 exceptions that do not expose the final HTTP response, diagnostics record the exception type and the page/cursor without pretending a schema response existed.

### Java command boundary

`DouyinIncrementalFetchService` parses the last valid `stream-vault-fetch-error=` JSON line when the Python process exits nonzero. It throws `CollectFetchException` with the emitted `errorCode` and safe message. Malformed or absent structured output remains an `IllegalStateException`, preserving a clear protocol-failure distinction.

This makes the persistent job row and run row store the actual fetch error code rather than `UNEXPECTED`.

### Cookie cooldown

The author fetch path reports risk against the exact Cookie used by the failed request. Cookie selection skips active cooldowns under both supported strategies. If all Cookies are cooling, selection returns no Cookie and the fetch fails locally with `F2_COOKIE_COOLDOWN`; no Douyin request is sent.

The current ten-minute in-process Cookie cooldown is retained as the selection guard. The persistent job retry delay for rate-limit, Cookie, verification, and cooldown errors is one hour. This deliberately separates request suppression from durable retry scheduling.

The cooldown remains an in-process optimization. After PostgreSQL migration and before multiple application instances are enabled, it may be moved to PostgreSQL or Redis without changing error codes or worker semantics.

### Worker behavior

The fetch worker remains single-threaded. Download processing remains independent and unchanged. A risk failure does not pause unrelated platforms, but the affected Douyin Cookie is no longer selected until its cooldown expires. Persistent retries continue to use the existing job table and attempt limits.

## Data Flow

1. The fetch worker claims one persistent author job.
2. The service selects a non-cooling Douyin Cookie.
3. The incremental Python command creates an F2 crawler scoped to X-Bogus.
4. Valid page JSON continues through the existing incremental pagination and batch selection.
5. An empty-response retry exhaustion emits `F2_UPSTREAM_RATE_LIMIT`.
6. Java parses the structured error and raises `CollectFetchException`.
7. The service marks the Cookie risky.
8. The worker persists the exact error code and schedules the retry one hour later.
9. Subsequent jobs either use another non-cooling Cookie or fail locally with `F2_COOKIE_COOLDOWN`.

## Verification

### Python tests

- The incremental crawler uses X-Bogus while unrelated commands remain unchanged.
- `APIRetryExhaustedError` maps to `F2_UPSTREAM_RATE_LIMIT`.
- timeout maps to `F2_UPSTREAM_TIMEOUT`.
- malformed nonempty JSON remains `UPSTREAM_SCHEMA_ERROR`.
- diagnostics remain bounded and redact Cookie data.

### Java tests

- Structured command errors become `CollectFetchException` with the original code.
- Missing or malformed structured errors remain protocol failures.
- `round_robin` skips cooling Cookies.
- all-cooling pools return no Cookie.
- risk codes receive a one-hour worker retry delay; ordinary transient failures do not.

### Regression tests

- Existing pagination, incremental batch limits, known-ID deduplication, and newest-first item order remain unchanged.
- Existing queue attempt limits and single fetch/download worker defaults remain unchanged.
- The full relevant Maven and Python test suites pass.

### Production-data verification

The copied production database is opened read-only with `PRAGMA query_only=ON`. Queries verify that historical `APIRetryExhaustedError` runs would map to the new risk code and that no schema or data migration is required. No production Cookie value is printed or copied into tests.

## Deployment and Rollback

Deploy the rebuilt image with the updated script and application JAR. After startup, confirm the script hash, run one author fetch, and observe:

- valid requests no longer produce the repeated 42-second empty-response pattern;
- risk failures store `F2_UPSTREAM_RATE_LIMIT` rather than `UNEXPECTED`;
- the next retry is approximately one hour later;
- the same Cookie is not immediately reused.

Rollback restores the previous image. No database rollback is necessary because this design adds no schema or data changes. If X-Bogus still fails in production, keep the error-propagation and cooldown changes and separately evaluate a pinned upstream F2 commit.
