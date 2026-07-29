# Douyin Process-Global Risk Cooldown Design

## Status

Approved for implementation on 2026-07-29.

## Problem

Douyin risk detection currently cools individual cookies for a hard-coded ten minutes. A single-cookie deployment can still have many due collection jobs. After one request reports upstream risk, later jobs are claimed one by one, observe `F2_COOKIE_COOLDOWN`, consume their final queue attempts, and produce repeated error logs.

The cooldown duration is not configurable in the admin UI. The fetch retry delay is also fixed at one hour even when the cookie will become available much sooner.

## Goals

- Make the Douyin risk cooldown duration configurable from the admin UI.
- Apply one process-global Douyin cooldown to every request path in one StreamVault JVM.
- Make configuration changes affect an active cooldown immediately.
- Prevent collection jobs from consuming attempts while no Douyin request is allowed.
- Retry at the actual end of the configured cooldown instead of after a fixed hour.
- Keep persistent configuration portable between SQLite and PostgreSQL.

## Non-Goals

- Sharing runtime cooldown state between containers or JVMs.
- Persisting the active cooldown across container restarts.
- Cancelling Douyin requests that were already in flight when risk was detected.
- Changing the collection download concurrency model.

## Configuration

Add nullable integer column `risk_cooldown_minutes` to `biz_tiktok_config` and expose it as `riskCooldownMinutes` in `TikTokConfigEntity`. The entity uses an explicit column mapping so naming does not depend on a database dialect or Hibernate naming strategy.

- Default: 10 minutes when the stored value is null.
- Accepted range: 1 through 1440 minutes.
- Invalid submitted values are rejected by the service instead of silently stored.
- The admin Douyin configuration card uses a numeric input with the same bounds.

The column uses a portable integer mapping. Existing deployments rely on `spring.jpa.hibernate.ddl-auto=update`, which can add the nullable column for SQLite and PostgreSQL. No SQLite-specific SQL is introduced.

## Runtime State

`PlatformCookieService` owns one thread-safe `douyinGlobalRiskStartedAtMs` value. It stores the latest time at which any Douyin request reported a risk signal.

The active cooldown deadline is calculated on every read:

```
deadline = latestRiskStartedAt + currentConfiguredCooldownDuration
remaining = max(0, deadline - now)
```

Calculating from the current setting makes UI changes real-time:

- Reducing the duration can immediately release an active cooldown.
- Increasing the duration extends the active cooldown from the last risk signal.
- A new concurrent risk signal moves the start time forward.
- A successful in-flight request never clears or shortens the cooldown.

The state remains process-local and resets when the JVM restarts.

## Request Gating

Every Douyin cookie selection checks the global cooldown before selecting from the cookie pool. While active, it returns no cookie and exposes the remaining cooldown duration to callers. Douyin no longer uses the existing per-cookie fixed-duration gate because it would prevent real-time configuration changes from taking effect. Per-cookie risk behavior for other platforms remains unchanged.

Service-managed Douyin paths use `PlatformCookieService`, covering collection fetches, direct parsing, metadata maintenance, health probes, and downloads. Implementation includes an active-call-site audit for convenience utilities that can still fall back to `Global.tiktokCookie`; active request paths must obtain their cookie through the service before calling those utilities. Requests already in flight may finish; only subsequent selections are blocked.

## Collection Queue Behavior

The collection worker checks the global Douyin cooldown before claiming work. The current persistent collection queue supports only Douyin, so an active gate can skip the claim entirely.

A second check remains in the fetch path to close the race between the pre-claim check and request execution. If the worker receives `F2_COOKIE_COOLDOWN` after claiming a job:

- Restore the current run from `FETCHING` to `QUEUED`.
- Move the same queue job to `RETRY_WAIT`.
- Set `available_at` to the calculated cooldown deadline plus a five-second safety buffer.
- Decrement the attempt count consumed by the claim.
- Clear worker locks.
- Record a `WARN` event and log entry, not a failed run or stack trace.

This operation is transactional and uses portable SQL expressions. It does not add a new table or queue state.

## Actual Upstream Risk

The request that receives `F2_UPSTREAM_RATE_LIMIT` remains a real failed fetch attempt. Reporting the error starts or refreshes the global cooldown. Its retry is scheduled for the current global cooldown deadline plus the safety buffer instead of a fixed one-hour delay.

`F2_COOKIE_OR_VERIFY_REQUIRED` also starts the global cooldown. It remains a real failed request because an upstream request was made.

`F2_COOKIE_COOLDOWN` is different: no upstream request was made, so it must not consume an attempt.

## Error Reporting

- Actual upstream risk: failed run with its structured error code and one error log.
- Global cooldown deferral: queued run, `WARN` event, concise warning log, no stack trace.
- The warning includes the remaining delay and next eligible time but never includes Cookie contents.

## Concurrency

The global risk start time is updated atomically with the latest observed timestamp. Concurrent readers calculate remaining time from the same atomic state and current configuration.

There is an unavoidable race where several requests select cookies immediately before the first request reports risk. Those already-started requests are not cancelled. Any later selection is blocked, and later success reports do not clear the cooldown.

## Verification

- Entity/service tests cover default, minimum, maximum, and invalid cooldown settings.
- `PlatformCookieService` tests cover process-global gating, latest-risk extension, and real-time duration changes.
- Active-call-site tests or searches verify that production Douyin request paths do not bypass the global gate through a `Global.tiktokCookie` convenience overload.
- Queue transaction tests prove cooldown deferral preserves the run, restores `RETRY_WAIT`, and does not consume attempts at `3/3`.
- Worker tests distinguish actual upstream risk failure from cooldown deferral.
- Template/API tests verify the setting is rendered and submitted.
- Existing Python incremental fetch and Java structured-error suites remain green.

## Rollout

After deployment, the new setting defaults to ten minutes. Existing in-memory risk state is naturally empty after restart. Operators can adjust the duration in the Douyin configuration card without another restart.
