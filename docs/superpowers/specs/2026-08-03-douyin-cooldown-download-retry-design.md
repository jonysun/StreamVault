# Douyin Cooldown and Download Retry Design

## Context

Production logs from 2026-08-02 through 2026-08-03 show three different failure classes:

- transient CDN failures such as HTTP/2 `CANCEL`, timeout, connection refusal, and an empty F2 work response;
- deterministic HTTP 431 responses while downloading media;
- download items permanently failed with `Douyin cookie is not configured` immediately after a global Douyin risk cooldown started.

Most transient CDN failures recovered through the existing persistent retry queue. The cooldown-related failures did not recover because the current code returns an empty cookie while the global cooldown is active, then interprets that empty value as a missing configuration and records a non-retryable validation failure.

The confirmed behavior is that a global Douyin cooldown applies to both author fetching and work downloading. Affected work must be deferred until the cooldown expires without consuming retry attempts.

## Goals

- Stop Douyin fetch and download activity during the process-wide global cooldown.
- Defer a claimed download until the cooldown deadline without consuming an attempt.
- Distinguish a configured cookie that is temporarily unavailable from a genuinely missing cookie configuration.
- Recover HTTP 431 media requests with a bounded request-header fallback.
- Preserve the existing persistent retry behavior for transient network failures.
- Keep the change compatible with both SQLite and PostgreSQL.

## Non-Goals

- No database schema or migration changes.
- No cross-container cooldown coordination.
- No change to the configured cooldown duration or frontend controls.
- No broad rewrite of the downloader or queue framework.
- No unlimited in-process retry loop for CDN failures.

## Selected Approach

Use defense in depth at the worker, adapter, service, and HTTP request boundaries.

A worker-only check is insufficient because a fetch worker can start the global cooldown after a download item has been claimed. An exception-only solution is correct but would keep claiming and returning items during the cooldown. Combining the checks avoids queue churn while still closing the concurrency race.

## Detailed Design

### Download worker gating

`CollectDownloadWorker` will depend on `PlatformCookieService`.

Before stale recovery and before each queue claim, the worker will stop its current batch when the Douyin global cooldown is active. After a claim, it will check again. If the cooldown started between the pre-claim check and the claim, it will return the item to `RETRY_WAIT` with an `available_at` value equal to the cooldown deadline plus the existing safety buffer.

The deferral must decrement the attempt count that was incremented by `claimNext`, matching the fetch queue cooldown behavior.

### Cooldown-aware cookie acquisition

The Douyin adapter must distinguish these states:

1. A configured cookie is available: continue normally.
2. A configured cookie exists but the global cooldown is active: raise a dedicated cooldown signal.
3. No cookie is configured: retain the current `Douyin cookie is not configured` validation error.

The dedicated signal prevents the adapter from converting a temporary process state into a permanent configuration failure.

### Race-safe service deferral

`CollectDownloadService` will handle the dedicated cooldown signal separately from normal download classification. It will call a cooldown-specific transaction method that:

- moves the claimed item from `RUNNING` to `RETRY_WAIT`;
- restores the consumed attempt count;
- sets `available_at` to the global cooldown retry time;
- clears the lease;
- records a cooldown-specific error code and message for diagnostics.

This path covers the race where the cooldown begins after the worker's post-claim check but before parsing or downloading obtains a cookie.

Other `WorkMetadataValidationException` instances remain non-retryable unless already classified otherwise. A genuinely absent Cookie therefore continues to fail clearly instead of retrying forever.

### HTTP 431 fallback

`HttpMediaDownloader` will keep its existing first request behavior. If that request returns HTTP 431, it will close the response and retry the same URL once without the `Cookie` header while preserving the media-specific `Referer`, `User-Agent`, and other request headers.

No other HTTP status receives this fallback. A second failure is returned to the existing persistent retry classifier. This keeps the workaround narrow and avoids hiding authentication failures.

### Existing network failures

HTTP/2 stream resets, timeouts, connection refusals, and empty F2 work responses remain `NETWORK_IO` failures. They continue to use the current item retry schedule and maximum-attempt rules. The fix will not add nested unbounded retries or change queue limits.

## State Flow

Normal download:

`QUEUED/RETRY_WAIT -> RUNNING -> COMPLETED/SKIPPED_EXISTING`

Cooldown detected after claim:

`QUEUED/RETRY_WAIT -> RUNNING -> RETRY_WAIT`

The cooldown transition restores the attempt count and schedules the item after the cooldown deadline.

Genuine missing Cookie:

`QUEUED/RETRY_WAIT -> RUNNING -> FAILED`

HTTP 431:

`request with Cookie -> HTTP 431 -> one request without Cookie -> normal success or NETWORK_IO handling`

## Testing

Add focused tests for:

- a download worker does not recover or claim while the global cooldown is active;
- a cooldown that starts after claim defers the item and skips download processing;
- a cooldown signal raised during processing uses the cooldown transaction instead of `fail` or ordinary `retryOrFail`;
- cooldown deferral restores the attempt count and schedules the supplied deadline on both supported database dialects through the existing JDBC transaction tests;
- a genuinely missing Cookie still reports the current permanent configuration failure;
- HTTP 431 causes exactly one retry without Cookie and preserves the other headers;
- non-431 HTTP failures do not use the header fallback;
- existing network retry and successful completion tests remain green.

## Rollout and Recovery

No database migration is required. Build and deploy a new image after the fix is merged.

After deployment and after confirming that a Douyin Cookie is configured, manually requeue the production items known to have reached a terminal state:

- `242513`
- `242595`
- `242647`

Check whether `242486` is `FAILED`; requeue it only if it did not subsequently complete.

Re-run collection task `98`, which previously exhausted its fetch attempts with `UPSTREAM_SCHEMA_ERROR`.

Monitor for these conditions:

- no `WORK_VALIDATION_FAILED: Douyin cookie is not configured` while cooldown is active;
- cooldown-deferred download items retain their retry budget;
- HTTP 431 either succeeds on the header fallback or remains a bounded, visible failure;
- no PostgreSQL constraint, generated-key, or transaction errors.

## Acceptance Criteria

- Global Douyin cooldown blocks both fetching and downloading in the current application process.
- A download item affected by cooldown is retried after the deadline without consuming an attempt.
- A real missing Cookie configuration remains distinguishable and terminal.
- HTTP 431 receives one safe fallback without the Cookie header.
- No schema change is introduced and focused tests pass on the repository's supported database profiles.
