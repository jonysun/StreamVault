# Admin Index Web Performance Design

## Goal

Optimize the Docker-hosted admin web page at `/admin/index`.

The first phase focuses on two changes:

1. Make the video list response used by `/admin/index` lightweight.
2. Add database indexes for the hot read paths used by the admin home page and related collection workflows.

This design also adds web-only playback timing diagnostics so we can tell whether slow first playback is caused by the list API, MP4 loading, HLS loading, Range serving, browser decoding, or playback timing.

## Non-Goals

This phase does not change:

- UniApp mobile pages.
- Native Android video plugin behavior.
- Mobile cache behavior.
- Existing video files.
- HLS transcoding output.
- The damaged large database file directly.
- Physical database shrinking, `VACUUM`, or raw JSON deletion.
- The existing `jsonData` or `videoinfo` columns.

## Current Context

`/admin/index` is rendered by `backstage/src/main/resources/templates/admin/index.html`.

The page currently loads video data through:

```text
POST /admin/api/findVideoDataList
```

That endpoint calls `VideoDataService.findPage(...)`, which currently returns full `VideoDataEntity` objects. Those entities may include large `jsonData` and `videoinfo` fields. These fields are useful for debugging or detail views, but they are not needed for the `/admin/index` grid or feed list.

The page needs basic display and playback fields, including title, description, author, platform, cover, publish time, and playback URLs. These must not be removed.

## Lightweight Admin Video List

Add a lightweight mode to the existing admin list endpoint:

```text
POST /admin/api/findVideoDataList
lite=1
```

When `lite=1`, return a lightweight DTO instead of full `VideoDataEntity`.

The DTO must include:

```text
id
videoid
videoname
videodesc
videoplatform
videocover
videounrealaddr
playurl
videoprivacy
videotag
videoauthor
authoruid
authorusername
publishtime
createtime
hlsstatus
sourceurl
favorite
```

The DTO must exclude:

```text
jsonData
videoinfo
```

This preserves the information required by `/admin/index` while removing the largest payload fields from the home page list response.

## Admin Page Integration

Update only `/admin/index` to pass `lite=1` when calling `/admin/api/findVideoDataList`.

Do not change mobile pages or public UniApp-facing APIs in this phase.

The page should continue to support both modes:

- Grid mode.
- Feed mode.

Both modes should receive the same lightweight fields. The page should continue to display:

- Work title.
- Work summary or description.
- Author name.
- Platform.
- Cover.
- Publish or download time.
- Playback source.
- Privacy status.

## Database Indexes

Add indexes for the observed hot paths.

Recommended indexes:

```sql
CREATE INDEX IF NOT EXISTS idx_biz_video_publishtime_id
  ON biz_video(publishtime, id);

CREATE INDEX IF NOT EXISTS idx_biz_video_createtime_id
  ON biz_video(createtime, id);

CREATE INDEX IF NOT EXISTS idx_biz_video_videoauthor
  ON biz_video(videoauthor);

CREATE INDEX IF NOT EXISTS idx_biz_video_videoplatform
  ON biz_video(videoplatform);

CREATE INDEX IF NOT EXISTS idx_biz_video_videoid
  ON biz_video(videoid);

CREATE INDEX IF NOT EXISTS idx_biz_video_platform_videoid
  ON biz_video(videoplatform, videoid);

CREATE INDEX IF NOT EXISTS idx_collect_detail_dataid_videoid
  ON biz_collect_data_detail(dataid, videoid);

CREATE INDEX IF NOT EXISTS idx_collect_detail_dataid_status
  ON biz_collect_data_detail(dataid, status);

CREATE INDEX IF NOT EXISTS idx_collect_detail_dataid_mediatype_status
  ON biz_collect_data_detail(dataid, mediatype, status);

CREATE INDEX IF NOT EXISTS idx_author_profile_platform_authoruid
  ON biz_author_profile(platform, authoruid);

CREATE INDEX IF NOT EXISTS idx_graphic_content_platform_videoid
  ON biz_graphic_content(platform, videoid);
```

These indexes support:

- `/admin/index` sorting by publish time or create time.
- Author and platform filtering.
- Video deduplication by video id and platform.
- Collection task detail checks by `dataid` and `videoid`.
- Collection task status counts.
- Author profile lookup by platform and author uid.
- Graphic content lookup by platform and video id.

## Web Playback Diagnostics

Add web-only timing diagnostics to `/admin/index`.

Record these points for each playback attempt:

```text
listRequestStart
listRequestEnd
clickPlay
loadstart
loadedmetadata
loadeddata
canplay
playing
waiting
stalled
error
```

Also record:

```text
sourceType: MP4 or HLS
src
fallbackSrc
didFallback
readyState
networkState
```

The diagnostic output can initially go to `console.log` and the existing feed debug panel. It does not need a server-side metrics store in this phase.

## Playback Source Analysis

This phase should not blindly change HLS or MP4 behavior. It should gather evidence first.

Potential slow paths:

- List API is slow or returns too much data.
- MP4 metadata loads slowly because the file is not faststart-optimized.
- HLS loads slowly because the browser must fetch the playlist and the first media segment.
- HLS segment duration is too long for fast first frame.
- Static file serving does not handle Range requests efficiently.
- Browser waits on decode or ready state before `play()`.

The current default should remain conservative:

- Prefer MP4 where the configured source policy already does so.
- Keep HLS available as fallback or configured preference.
- Do not batch-transcode or rewrite existing media in this phase.

After diagnostics, a later phase can decide whether to:

- Add MP4 faststart checks.
- Rebuild selected MP4 files with `-movflags +faststart`.
- Tune HLS segment duration.
- Improve static file Range and cache headers.

## Error Handling

If lightweight DTO mapping fails for a record, the API should not fail the whole page. It should preserve the same behavior as current list loading where possible and log enough context to identify the record.

If `lite=1` is absent, the existing full entity response should remain available for compatibility.

If playback source diagnostics detect an HLS failure and MP4 fallback exists, the existing fallback behavior should continue.

## Testing

Backend tests:

- Verify `findVideoDataList` with `lite=1` returns required fields.
- Verify `jsonData` and `videoinfo` are absent in lightweight response.
- Verify existing full response remains unchanged without `lite=1`.
- Verify indexes are created idempotently.

Manual web checks:

- Open `/admin/index`.
- Confirm grid mode still shows title, summary, author, platform, cover, and time.
- Confirm feed mode still shows title, author, platform, time, and playable source.
- Confirm browser network response for `/admin/api/findVideoDataList` is smaller with `lite=1`.
- Play a video and confirm timing logs include source type and playback milestones.

Database checks:

- Confirm index creation is idempotent.
- Confirm no destructive database operation is performed.
- Confirm damaged large database recovery remains out of scope for this phase.

## Implementation Order

1. Add a lightweight DTO and service path for admin video list responses.
2. Add `lite=1` to `/admin/index` calls to `/admin/api/findVideoDataList`.
3. Add idempotent index creation.
4. Add web playback timing diagnostics to `/admin/index`.
5. Run backend tests.
6. Manually verify `/admin/index` in Docker web.

## Acceptance Criteria

- `/admin/index` uses lightweight video list data.
- The list still displays title, summary, author, platform, cover, time, privacy state, and playback source.
- `jsonData` and `videoinfo` are not included in the lightweight list response.
- The original non-lightweight endpoint behavior remains available.
- Hot-path indexes are created safely and idempotently.
- Web playback diagnostics can distinguish API delay from MP4/HLS playback delay.
- No mobile, UniApp, native plugin, media file, or physical database shrink behavior changes in this phase.
