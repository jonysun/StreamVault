# Douyin F2 Evidence and Backoff Design

## Status

Proposed after a controlled local replay on 2026-08-14. Implementation must wait
for review of this document.

## Evidence and Problem

The persistent collection download path refreshes every Douyin work through F2
before it downloads media:

```text
CollectDownloadWorker
  -> CollectDownloadService
  -> WorkIngestService
  -> DouyinPlatformAdapter
  -> DouUtil.fetchWorkDataJson
  -> CommandUtil.f2cmd(fetch_work_data)
  -> douyin.py / InstrumentedDouyinCrawler
  -> GET https://www.douyin.com/aweme/v1/web/aweme/detail/
```

F2 0.0.1.7 creates query parameters from the `PostDetail(aweme_id)` request
model and signs them with `X-Bogus`. The request carries the configured Douyin
Cookie, a fixed Chromium/Edge user agent, and a Douyin referer. It uses a ten
second HTTP timeout and no configured HTTP/HTTPS proxy. The generated endpoint
contains short-lived/signature-like values and must never be logged in full.

The fixed F2 request model has these non-sensitive query key names:

```text
device_platform, aid, channel, pc_client_type, publish_video_strategy_type,
pc_libra_divert, version_code, version_name, cookie_enabled, screen_width,
screen_height, browser_language, browser_platform, browser_name,
browser_version, browser_online, engine_name, engine_version, os_name,
os_version, cpu_core_num, device_memory, platform, downlink, effective_type,
round_trip_time, msToken, aweme_id, X-Bogus
```

`msToken`, `X-Bogus`, Cookie values, complete query strings, media URLs, and
response bodies are sensitive and excluded from all diagnostics.

The controlled local replay used the synchronized SQLite snapshot only as a
historical-cookie experiment, not as proof about the current PostgreSQL
production Cookie. It requested a production-failing work ID via the exact F2
detail path. The sequence was: one empty response, then an HTTP 403 response
with `Content-Type: text/plain` and a 45-byte body. F2 therefore classified the
result as `F2_COOKIE_OR_VERIFY_REQUIRED`. This proves a remote rejection for
that Cookie/fingerprint/egress combination; it is neither a SQLite/queue error
nor a DNS, transport, or timeout failure. It does not by itself distinguish an
expired Cookie from an IP, fingerprint, or verification risk-control decision.

Production logs show the same sequence at scale: after the thirty-minute
cooldown expires, many single-work downloads report `F2_UPSTREAM_SOFT_BLOCK`
about 12-20 seconds apart, followed by a separate request that reports
`F2_COOKIE_OR_VERIFY_REQUIRED` and restores the thirty-minute global cooldown.
The current implementation knows the F2 HTTP evidence but drops it during Java
exception construction. It also does not activate a cooldown after a soft block,
so it continues consuming sequential queue entries until a stronger response is
seen.

## Goals

- Make every F2 failure attributable to one of `REMOTE_API`, `NETWORK`,
  `APPLICATION`, or `TASK_CONFIGURATION` with evidence sufficient to verify the
  classification.
- Log the static detail endpoint identity and safe request/response evidence
  without disclosing credentials, signatures, media URLs, or upstream bodies.
- Propagate the safe structured diagnosis from Python to Java exception, logs,
  and persistent collection item error details.
- Stop future Douyin fetch and download requests promptly after a repeated empty
  response, without treating that ambiguous signal as a proven Cookie expiry.
- Preserve the existing thirty-minute configurable global cooldown only for
  unambiguous rate-limit or authentication/verification evidence.
- Keep runtime and persistence behavior portable across the deployed PostgreSQL
  database and existing SQLite test profiles.

## Non-Goals

- Logging or retaining raw remote response bodies.
- Recording a Cookie, `msToken`, `X-Bogus`, complete endpoint query, or signed
  media address.
- Bypassing Douyin risk control, rotating identities automatically, or using a
  proxy.
- Sharing cooldown state between containers or persisting it across a restart.
- Changing the durable download ordering or retry-attempt policy unrelated to an
  active global gate.

## Options Considered

### A. Evidence-only diagnostics

Keep the current retry behavior and only expose the lost F2 evidence. This
improves diagnosis but still allows a soft-block sequence to issue many requests
before a 401/403/429 response starts the global cooldown.

### B. Evidence plus a short global soft-block backoff (recommended)

Record the evidence and start a distinct five-minute process-global Douyin
backoff on `F2_UPSTREAM_SOFT_BLOCK`. The normal thirty-minute configured risk
cooldown remains reserved for 401/403, 429, captcha, challenge, and explicit
login/verification signals. This stops the queue early while preserving the
important distinction between an ambiguous blank response and proven
authentication/rate-limit rejection.

### C. Treat every soft block as the full risk cooldown

This minimizes requests but converts a potentially transient upstream blank
response into a long service interruption. It overstates the evidence and can
needlessly delay unrelated legitimate work.

Option B is selected.

## Safe Diagnostics Contract

`douyin.py` emits the existing `stream-vault-fetch-error=` JSON envelope. The
single-work diagnostic gains a bounded `request` object and a bounded ordered
`attempts` list. Each entry has only:

```json
{
  "requestMethod": "GET",
  "endpointClass": "DOUYIN_WEB_AWEME_DETAIL",
  "endpointOrigin": "https://www.douyin.com",
  "endpointPath": "/aweme/v1/web/aweme/detail/",
  "queryKeyNames": ["aid", "aweme_id", "X-Bogus"],
  "signedQueryPresent": true,
  "attempt": 2,
  "statusCode": 403,
  "bodyLength": 45,
  "bodyEmpty": false,
  "contentType": "text/plain",
  "errorKind": "HTTP_STATUS",
  "exceptionType": "HTTPStatusError",
  "durationMs": 187
}
```

The values are type-, range-, and length-bounded. The endpoint identity is
constant code-owned metadata, not derived from the generated signed URL. The
`workId` may remain in the current job-level context because it is already a
visible collection identifier, but it is not duplicated as a query value.

F2 must collect one evidence entry for every actual HTTP attempt up to the
single-work command's confirmation limit. `lastRequest` stays for backward
compatibility and equals the final entry. F2 must not parse or log a body to
infer its content; status, size, and content type are sufficient.

## Classification

| Evidence | Error code | Fault domain | Cooldown behavior |
| --- | --- | --- | --- |
| HTTP 401/403, explicit captcha/challenge/login/verification evidence | `F2_COOKIE_OR_VERIFY_REQUIRED` | `REMOTE_API` | Configured strong global cooldown, currently 30 minutes |
| HTTP 429 or explicit rate-limit evidence | `F2_UPSTREAM_RATE_LIMIT` | `REMOTE_API` | Configured strong global cooldown |
| Confirmed repeated empty HTTP bodies | `F2_UPSTREAM_SOFT_BLOCK` | `REMOTE_API` | Five-minute global soft backoff |
| HTTP 408 or a timeout | `F2_UPSTREAM_TIMEOUT` | `NETWORK` | Ordinary item retry; no risk cooldown |
| DNS, connection, proxy, TLS/request transport failure | `F2_NETWORK_ERROR` | `NETWORK` | Ordinary item retry; no risk cooldown |
| HTTP 503 | `F2_UPSTREAM_UNAVAILABLE` | `REMOTE_API` | Ordinary item retry; no risk cooldown |
| Non-JSON body or valid JSON without expected detail | `F2_UPSTREAM_RESPONSE_ERROR` | `REMOTE_API` | Ordinary item retry; no risk cooldown |
| F2/script/envelope parsing failure | `F2_RUNTIME_ERROR` or `F2_PROTOCOL_ERROR` | `APPLICATION` | Ordinary item retry or explicit non-retryable protocol failure |
| Single-work HTTP 404 or explicit deleted/unavailable evidence | `F2_WORK_UNAVAILABLE` | `REMOTE_API` | No automatic retry |

The product UI and logs must describe a soft block as “Douyin returned repeated
empty HTTP responses; remote soft restriction suspected”, never as “Cookie
expired”. The 403/401 category says “login or verification required / request
rejected”; it likewise must not claim the exact remote reason when Douyin does
not supply one.

## Java Propagation and Logging

`DouyinWorkFetchException` gains an immutable bounded safe-diagnostics value.
`DouUtil.parseF2WorkError` validates and normalizes the envelope rather than
passing arbitrary JSON through. It retains request identity, final request
evidence, and at most the two latest attempts. Any unexpected field is
discarded.

`CommandUtil` continues to suppress successful raw work metadata because it can
contain signed media addresses. For a non-zero `fetch_work_data` process it must
log the sanitized structured-error line at WARN/ERROR. The F2 logger must allow
these safe diagnostics to reach both Docker stdout and `/app/log/f2-error.log`;
it must still reject raw successful payload output.

`DouUtil`, `DouyinPlatformAdapter`, `CollectDownloadService`, and
`CollectJobWorker` write a compact stable diagnostic suffix, such as endpoint
class, last HTTP status, body length, content type, attempt count, fault domain,
and F2 exception type. The full bounded safe object is written to the existing
collection item `error_detail`, limited by its existing 10,000-character
portable-column write limit. Stack traces remain supplementary and never replace
the structured diagnosis. The current inaccurate phrase “author-work endpoint”
is replaced with “single-work detail endpoint”.

## Global Backoff Behavior

`PlatformCookieService` owns two process-local start timestamps:

- Existing strong-risk timestamp, calculated using the configured
  `riskCooldownMinutes`;
- New soft-block timestamp, calculated with a fixed five-minute duration.

The effective global gate is the later of those two deadlines. A strong risk
does not shorten a soft block, and a soft block does not refresh a stronger
existing cooldown. Multiple concurrent soft-block reports atomically move the
soft timestamp forward. Success reports never clear either state.

The gate applies before a collection download is claimed, after it is claimed,
and before all service-managed Douyin fetch paths select a Cookie. A claim
deferred solely because the gate is active retains its attempt count and moves
to `RETRY_WAIT` at the effective deadline plus the existing five-second safety
buffer. The actual request that first encounters a soft block remains a real
attempt and records its typed error and safe details.

On `F2_UPSTREAM_SOFT_BLOCK`, `DouyinPlatformAdapter` reports the soft block and
converts its result to a `DouyinGlobalCooldownException` so the currently
claimed download becomes a cooldown deferral after recording the primary failure
details. Fetch jobs use the same effective retry deadline. Later sequential
items do not make a request while the gate remains active. This reuses the
existing process-global coordination and does not add a database table or a
SQLite-specific query.

## Tests

Python tests:

- Exact F2 request identity is emitted without generated endpoint values.
- Empty body then 403 produces two safe attempts and the final
  `F2_COOKIE_OR_VERIFY_REQUIRED` classification.
- Repeated empty responses produce `F2_UPSTREAM_SOFT_BLOCK` with safe evidence.
- 429, 404, timeout, network failure, invalid JSON, and nonzero remote status
  retain the expected error code and fault domain.
- Every serialized diagnostic is checked to exclude a supplied Cookie fragment,
  `msToken` value, `X-Bogus` value, endpoint query value, and response body.

Java tests:

- Structured diagnostics survive `DouUtil` parsing and are bounded and
  allowlisted.
- Non-zero `fetch_work_data` logs only the sanitized error envelope; successful
  metadata remains suppressed.
- Download error details retain safe F2 evidence and no sensitive values.
- A soft block opens a five-minute effective global gate, defers later claims
  without consuming attempts, and does not turn into the configured strong
  cooldown.
- 401/403/429 retain the configured strong cooldown.
- Network and timeout errors do not activate either gate.
- Existing SQLite and PostgreSQL persistence tests pass without SQL dialect
  branches.

## Rollout and Operational Verification

After deployment, reproduce one failing single-work request through a normal
queue item. The expected Docker and F2 error log contains its typed error plus
the safe evidence. Operators compare only endpoint class, HTTP status, content
type, body length, timing, and fault domain. They must never request or collect
the generated URL, Cookie, signature, or upstream body.

For a repeated-empty response, all new Douyin traffic pauses for five minutes.
For a 401/403/429 response, it pauses for the configured strong duration. A
restart clears both in-memory gates, matching the existing documented cooldown
lifecycle.
