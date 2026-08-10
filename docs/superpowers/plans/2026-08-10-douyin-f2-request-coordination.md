# Douyin F2 Request Coordination Implementation Plan

## Success criteria

- Collection-page fetches and single-work F2 metadata requests never overlap inside one application container.
- A waiting request rechecks global cooldown after entering the coordinator and does not launch F2 during cooldown.
- Single-work failures carry a safe structured error code from Python to the download retry decision.
- Only explicit rate-limit, authentication, or verification evidence starts global cooldown.
- Explicitly deleted or unavailable works stop automatic retry without deleting local records or media.
- No cookie, full upstream body, traceback, or signed media URL is logged or persisted by the new path.
- Python regression tests, targeted Java tests, the full Maven suite, compilation, and `git diff --check` pass.

## Implementation steps

1. Extend `douyin.py` single-work fetching to use the instrumented F2 crawler and `PostDetail`, preserving HTTP evidence already used by incremental fetches.
2. Reuse the structured error envelope for single-work failures, with explicit mappings for rate limit, authentication/verification, unavailable work, upstream response, timeout, network, and local runtime errors.
3. Add `DouyinWorkFetchException` so Java can carry the Python error code, fault domain, retryability, cooldown flag, upstream status, and exception type without exposing raw command output.
4. Update `DouUtil.fetchWorkDataJson` to parse the structured envelope first and retain a conservative, redacted fallback for older images or malformed output.
5. Add a fair singleton `DouyinF2RequestCoordinator` using `ReentrantLock(true)` and an interruptible `AutoCloseable` permit.
6. Wrap both incremental fetch and single-work metadata fetch in the coordinator. Recheck global cooldown after acquiring the permit and report explicit risk before releasing it.
7. Update collection download classification to preserve typed F2 error codes and retryability; keep actual media I/O under `NETWORK_IO`.
8. Add focused Python and Java tests for classification, redaction, cooldown behavior, coordinator exclusion/interruption, and download retry decisions.
9. Run targeted and full verification, review the diff for unrelated changes and secret leakage, then commit, push, open a ready PR, and merge to `main`.
