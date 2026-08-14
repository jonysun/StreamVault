# Douyin F2 Evidence and Backoff Implementation Plan

## Success Criteria

- A single-work F2 failure emits only allowlisted transport evidence: request
  identity, query-key names, per-attempt status/body-size/content-type/error
  kind/exception/duration. No Cookie, query value, signature, URL, body, or
  media URL can cross the Python-to-Java boundary.
- Java logs and `biz_collect_run_item.error_detail` retain the safe diagnosis
  for a failed collection download.
- `F2_UPSTREAM_SOFT_BLOCK` opens a five-minute process-global Douyin backoff.
  The primary request is recorded as a failure; later unstarted work is deferred
  without consuming an attempt.
- 401/403/captcha/login/verification and 429 retain the configured strong
  risk cooldown. Timeout and transport failures do not activate either gate.
- Docker stdout and `/app/log/f2-error.log` contain the sanitized structured
  envelope for failed single-work commands, never raw metadata.

## Steps

1. In `douyin.py`, add a constant safe identity for the web work-detail endpoint
   and attach it to a bounded diagnostic object. Expand the instrumented crawler
   from one `last_request_evidence` field to a two-entry attempt history. Record
   duration around each actual HTTP call, including timeout and transport errors.
2. In `fetch_work_data`, add the safe request identity and attempt history to
   every failure envelope. Preserve `lastRequest` as the final history entry for
   existing incremental callers. Correct the soft-block message to say
   “single-work detail endpoint”. Add Python tests for safe identity,
   empty-then-403 evidence, repeated empty evidence, and redaction.
3. Add a small immutable `DouyinF2Diagnostics` Java value type that parses only
   approved fields and clamps strings, key count, attempt count, numeric ranges,
   and JSON text length. Add it to `DouyinWorkFetchException`. Update `DouUtil`
   to parse the structured error through this type and add a readable safe
   summary to warning messages. Test field filtering and no-secret retention.
4. Add a `reportSoftBlock` timestamp and five-minute duration to
   `PlatformCookieService`. Make `isDouyinGlobalCooldownActive`, remaining time,
   retry time, cookie selection, and status use the later of strong and soft
   deadlines. Strong risk reports remain configurable and do not change their
   semantics. Test both gates and their precedence.
5. In `DouyinPlatformAdapter`, when the typed single-work error is a soft block,
   report the soft block, retain the typed error as the cause, and raise a
   cooldown exception marked as an actual upstream failure. Strong F2 failures
   retain their current strong-risk behavior. Enhance `DouyinGlobalCooldownException`
   with an `actualUpstreamFailure` flag.
6. In `CollectDownloadService`, treat a cooldown exception caused by an actual
   failed request as `retryOrFail` so `error_code`, message, and the safe F2
   summary are stored. Treat gate-only exceptions exactly as today with
   `deferForCooldown` and no attempt decrement. For other typed F2 errors, append
   the safe diagnostics to `error_detail`.
7. In `CommandUtil` and `logback.xml`, log a sanitized nonzero
   `fetch_work_data` envelope at ERROR even while successful raw metadata remains
   suppressed. Route the F2 logger to stdout plus F2 error file at ERROR level.
8. Update fetch-risk classification so a fetch-side soft block reports the new
   short backoff before the worker checks the global gate. Ensure no code maps a
   network error into a risk cooldown.
9. Run focused Python and Maven tests, then Maven compilation/full test suite,
   inspect diff/secret patterns, and review working tree before committing.
