# Douyin Cookie Health, Download Lease, and Dashboard Design

Date: 2026-08-03

## Problem

Production logs show that the Douyin cookie health check calls `fetch_user_collects` and crashes when the upstream response contains `collects_list=null`. The resulting traceback is currently presented as a cookie failure even though author fetches and downloads continue to succeed. The probe therefore cannot distinguish an expired cookie from an empty collection list, risk control, a network failure, or an upstream schema change.

Collection fetch and download run as separate persistent pipelines. Download execution already resolves the work and its current media URLs when a queued item is claimed, so it does not retain a media URL from the earlier collection fetch. However, Douyin parsing and media download currently request a cookie independently. A rotating cookie pool can consequently select different cookies within one download attempt.

The admin home page also loses runtime job field names after the PostgreSQL migration. PostgreSQL folds unquoted SQL aliases to lowercase, while the JavaScript expects camel-case keys. This produces labels equivalent to `#0 background task` even though the database contains valid job and task IDs. The page exposes only queue totals and does not show task-level download progress or individual running and queued items.

## Goals

- Make cookie health results accurate and non-destructive when the upstream response is ambiguous.
- Reuse one short-lived cookie selection for all Douyin requests in a single download attempt.
- Keep cookies out of persistent queue records and database tables.
- Preserve the existing separation between collection fetch and download pipelines.
- Return stable camel-case API fields on both SQLite and PostgreSQL.
- Show task-level download progress and individual running and queued downloads on the admin home page.
- Keep dashboard polling bounded and consistent with the worker's real claim order.

## Non-Goals

- Persisting cookies, sessions, or media URLs with queue items.
- Sharing cookie leases or global cooldown state across containers.
- Replacing polling with WebSocket or server-sent event infrastructure.
- Refactoring unrelated database maintenance queries.

## Architecture

### Cookie Health Probe

The Python `fetch_user_collects` command will normalize and report the upstream response instead of iterating `collects_list` blindly. It will emit one structured, machine-readable result containing the upstream status, list state, collection count, and a sanitized error category. The result is a single JSON object between dedicated `stream-vault-start-cookie-probe` and `stream-vault-end-cookie-probe` markers. Its required fields are `probeStatus`, `upstreamStatus`, `listState`, `collectCount`, and `errorCategory`. Ordinary diagnostic output remains secondary and must not be parsed as the contract.

The Java health service will evaluate a configured cookie in this order:

1. Check required cookie fields. Missing fields produce `INCOMPLETE` without an upstream request.
2. If the process-wide Douyin cooldown is active, produce `COOLDOWN` and its remaining duration without an upstream request.
3. Consider recent successful real operations for the same cookie as positive evidence.
4. Run the structured collection probe when fresh evidence is unavailable.
5. Treat a successful response with an array as valid; an empty array is a valid response.
6. Treat a null or missing list, network failure, timeout, or schema mismatch as `INDETERMINATE`. This result must not mark the cookie expired or start global cooldown.
7. Produce `EXPIRED` and report risk only for explicit login failure, HTTP 401/403, verification, or an equivalent unambiguous authentication signal.

The result shown in the admin UI will explain whether it was established by a real operation, a probe, static field validation, or a cooldown decision. Cookie values and unsanitized upstream bodies must never be returned or logged.

### Download-Scoped Cookie Lease

`PlatformWorkAdapter` will expose a default no-op operation scope so non-Douyin adapters remain unchanged. `WorkIngestService` will open the adapter scope before parsing and close it after download, persistence, or failure. The Douyin implementation will acquire one cookie lease when the scope opens and make `parse`, media download, and cover download resolve the cookie from that lease.

The lease is in memory only and is released in a `finally`/`AutoCloseable` path. A queued download does not retain the cookie selected by collection fetch, and a retry may select a different healthy cookie. This preserves queue durability without storing credentials.

The effective download flow is:

```text
claim queue item
  -> open adapter operation scope and acquire cookie lease
  -> resolve work metadata and fresh media URLs
  -> download media and cover with the same cookie
  -> verify files and persist metadata
  -> close scope and release lease
```

### Runtime API Contract

Runtime job and collection download queries will use explicit row mapping or otherwise explicit output construction. API JSON must not depend on JDBC driver alias casing. Required canonical fields include `jobId`, `jobType`, `taskId`, `taskName`, `runId`, `runState`, `itemId`, `workId`, `processState`, `attemptCount`, and all corresponding timestamps and error fields.

The implementation will audit API-facing `JdbcTemplate.queryForList` and `queryForMap` results in the collection runtime path. Queries used only for internal calculations may retain case-insensitive maps. The change is scoped to API contracts affected by the PostgreSQL migration.

### Dashboard Endpoints

The existing background status request remains the lightweight summary request and returns:

- collection fetch queue counts;
- download queue counts;
- running, queued, and recently failed fetch jobs;
- task-and-run download aggregates.

Download aggregates are grouped by collection task and run and include task name, run ID, planned count, completed count, running count, queued count, retry-wait count, skipped count, and failed count.

The download queue endpoint returns two explicit lists:

- all currently `RUNNING` download items;
- the first 20 waiting items, with immediately claimable items ordered exactly as the download worker orders them and future retry items following in `availableAt` order.

The response also returns full state totals, total queued rows, oldest queued time, and next retry time. Each waiting row includes whether it is currently claimable. Manual retry priority and existing ordinal ordering remain authoritative for claimable rows. Dashboard queries do not mutate or reserve queue items.

The summary is polled every three seconds while the page is visible. Download details are polled every five seconds. Hidden-page polling uses a longer interval. A failure in one request preserves the most recent successful data for that section and marks it stale rather than clearing the whole dashboard.

## Admin Home Presentation

The existing background task control area gains three unframed sections:

1. Task progress: one compact row per active task/run with task name, run ID, completed/planned progress, and running, queued, retry, and failed counts.
2. Downloading now: one row per running item with task name, work ID, run ID, start time, and attempt count.
3. Waiting to download: the first 20 rows with task name, work ID, queue position, state, and available or retry time.

When more than 20 items are waiting, the page shows the total and links to the existing collection task details. Failed fetch jobs retain their real job ID, task name, run ID, attempt count, and error code. The page must never synthesize `#0` for a missing field; genuinely unavailable identity is displayed as unknown and treated as an API contract defect in tests.

## Error Semantics

- Network timeouts, connection resets, broken pipes, and transient I/O failures remain retryable and do not by themselves mark a cookie expired.
- Explicit authentication or verification responses report cookie risk, activate the process-wide cooldown, and defer the download item until the cooldown retry time.
- Upstream schema changes use `UPSTREAM_SCHEMA_ERROR` with sanitized diagnostics and do not contaminate cookie health.
- A deleted, blocked, or unavailable work reaches a terminal skipped/blocked state and is not retried indefinitely.
- Lease cleanup happens for success, retryable failure, terminal failure, and persistence failure.
- Dashboard detail failure leaves prior values visible with a stale indicator.

## Compatibility and Performance

No schema migration is required. The solution works with the current PostgreSQL production database and retains SQLite compatibility for development and rollback. Cookies and leases remain process-local, matching the established requirement that cooldown state need not span containers.

Queries remain bounded. Aggregation is limited to active/recent runs, the running list is naturally bounded by worker concurrency, and the queued detail list is capped at 20. Existing claim indexes and ordering are reused; an additional index is added only if query-plan verification demonstrates a need.

## Tests

Automated coverage will include:

- Python probe results for null, missing, empty, populated, unauthorized, risk-controlled, and network-failure responses.
- Java health-service classification showing that ambiguous results do not report expiration or trigger cooldown.
- Recent real success as positive health evidence.
- One cookie value reused for parse, media, and cover requests within an attempt, with lease release on every terminal path.
- Network retry, authentication cooldown, schema-error diagnostics, and deleted-work terminal handling.
- Stable camel-case runtime and download API keys under PostgreSQL-style identifier folding.
- Correct task/run aggregates and exact queue claim ordering.
- Template sanity checks for task aggregates, running downloads, queued downloads, totals, and stale states.
- The complete Maven and Python test suites.

## Acceptance Criteria

- The production `collects_list=null` response no longer creates a Python traceback.
- The cookie page reports that response as indeterminate unless an explicit authentication signal is also present.
- Successful author fetches and downloads provide positive health evidence for the selected cookie.
- A download attempt uses one cookie for metadata refresh, visual media, and cover download.
- Queue waiting time cannot make a stored media URL expire because URLs are resolved only after the item is claimed.
- The admin home page shows real task names and identifiers instead of `#0 background task` placeholders.
- Task-level download progress, every running item, and the first 20 waiting/retry items are visible simultaneously.
- Waiting items appear in the same order in which the worker is eligible to claim them.
- Desktop and narrow viewport screenshots show no text overlap, nested cards, or layout jumps during refresh.
- The built Docker image contains the updated Python scripts and Java classes.
