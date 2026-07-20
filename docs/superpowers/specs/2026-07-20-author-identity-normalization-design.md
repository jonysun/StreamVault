# Author Identity Normalization Design

Date: 2026-07-20

## Goal

Eliminate author identity field mixing across ingestion, persistence, lightweight APIs, profile aggregation, web clients, uniapp payloads, Android native playback, metadata generation, and legacy-data repair.

The implementation must correct runtime behavior without directly modifying the checked-in production database copy. Production data changes occur only through explicit application repair operations.

## Canonical Field Contract

For Douyin:

| Structured field | Meaning |
| --- | --- |
| `authoruid` | Canonical stable author identity. Only a nonblank `sec_uid` beginning with `MS4` is valid. |
| `secuid` | Compatibility copy of the canonical `authoruid`. |
| `authorusername` | Current public `unique_id`, which may change. |
| `uniqueid` | Compatibility copy of `authorusername`. |
| Work `author` / `videoauthor` | Display-name snapshot captured with that work. |
| Author profile `displayname` | Current known display name. |
| Author name history | Previously observed display names for the canonical author. |
| Numeric Douyin `uid` | Raw upstream metadata only. It remains in `jsonData` and is never used for structured identity, profile aggregation, homepage construction, or an on-screen UID. |

For other platforms, `authoruid` remains the platform's stable author identifier and `authorusername` remains its public handle. A platform may expose the same value for both, but the fields retain distinct meanings.

## Normalization Boundary

Introduce one backend author-identity utility responsible for:

- platform detection;
- canonical UID selection from `authoruid` and `secuid`;
- username selection from `authorusername` and `uniqueid`;
- Douyin `MS4` validation;
- canonical Douyin author homepage generation;
- rejection of numeric Douyin UIDs at read and write boundaries.

Collectors and maintenance services use the same utility rather than implementing local fallback order. Existing successful ingestion behavior is preserved: valid `sec_uid` is written to both UID compatibility fields and `unique_id` is written to both username compatibility fields.

## API And Aggregation

### Lightweight Media Data

Video lightweight projections and DTOs must include `secuid` and `uniqueid`. Video and graphic feed mapping normalizes identity before emitting API data:

- `authoruid` and `profileAuthorUid` never contain a numeric Douyin UID;
- `secuid` contains the same canonical UID when available;
- `authorusername` and `uniqueid` expose the public handle;
- current profile display name may replace the displayed author label, while the work snapshot remains stored unchanged.

### Author Profiles

Profile summary and works queries normalize incoming identity first. A numeric Douyin `authoruid` is treated as absent, allowing lookup to continue by `authorusername`, current display name, and name history. UID-based aggregation remains preferred whenever a canonical `MS4` value exists.

Profile summaries sanitize stored legacy data:

- invalid numeric Douyin UIDs are not returned as `authoruid`;
- invalid numeric Douyin homepages are ignored;
- a homepage is generated only from a canonical UID;
- missing signatures remain explicitly missing until enrichment succeeds; the display name is not presented as a signature substitute.

## Links And Clients

All Douyin `/user/{uid}` construction requires a canonical UID.

- `DouyinSourceUrlUtil.graphic` rejects non-`MS4` author IDs.
- The admin media profile and graphic-content link builders apply the same validation.
- The author list displays only sanitized homepages.
- NFO profile metadata uses canonical `sec_uid`, never numeric `uid`.
- uniapp feed payloads prefer canonical `authoruid/secuid` and keep username separate.
- The Android native item parser must not fall back from author ID to `uniqueid`.
- Generated uniapp cache/build output is not edited directly.

## Non-Destructive Legacy Repair

Replace the destructive author rebuild flow with an idempotent repair-and-merge operation.

1. Scan Douyin works without deleting author profiles.
2. Resolve identity from existing structured fields and stored `jsonData` first.
3. Call hybrid/profile APIs only for unresolved or incomplete identities.
4. Update a work only after a canonical UID is known; synchronize both UID fields and both username fields.
5. Upsert the canonical author profile, including current display name, avatar, homepage, username, and signature when available.
6. Merge old profile display names and name-history rows into the canonical profile.
7. Delete a numeric-UID duplicate profile only after its canonical replacement and history merge succeed.
8. Preserve unresolved legacy profiles and report them as unresolved; runtime APIs still suppress their invalid UID/homepage.

The repair operation is explicit and does not run during application startup. Its result reports scanned, repaired, merged, unresolved, locally resolved, API-resolved, and API-failed counts.

## Performance And Failure Behavior

- Identity normalization is in-memory string validation and adds no network request to normal feed/profile reads.
- Repair groups or reuses resolved identities to avoid one upstream request per work where possible.
- API failure never clears an existing work or author field.
- Missing canonical identity disables canonical profile/homepage actions rather than constructing a guessed URL.
- Other platform behavior remains unchanged.

## Validation

- Unit tests for platform-aware UID and username normalization.
- Tests proving numeric Douyin UIDs are rejected and `MS4` values are retained.
- Lightweight video/graphic mapping tests for `secuid` and `uniqueid`.
- Profile tests for invalid-UID fallback to username/name history and homepage sanitization.
- Source URL and NFO tests preventing numeric `/user/` links.
- Non-destructive repair tests covering success, unresolved API failure, history merge, and preservation of existing rows.
- Admin index inline JavaScript syntax and existing admin feed module tests.
- Source-level uniapp payload tests where available and Android module compilation/tests where available.
- Full relevant Maven test suite and `git diff --check`.

## Acceptance Criteria

- No structured Douyin author UID exposed by application code is numeric.
- No Douyin homepage or graphic-work URL uses a numeric UID.
- Profile lookup does not let an invalid UID block username/name-history fallback.
- Current display names and historical names remain associated with the canonical author.
- Existing works retain their captured nickname snapshots.
- Repair does not delete an author profile before a canonical replacement is safely persisted.
- Mobile and native payloads do not treat `unique_id` as a stable author UID.
- Existing fixed-origin-button work in `admin/index.html` remains intact.
