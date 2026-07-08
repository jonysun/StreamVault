# Admin Index Mixed Media Feed Design

## Goal

`/admin/index` should default to a mixed media feed that includes both downloaded videos and graphic/image-text works. Graphic works should play inside the same full-screen media homepage as a Douyin-like slideshow, while existing video playback behavior, HLS/MP4 fallback, author filtering, ordering, and desktop Docker web scope remain intact.

This design is limited to the Docker/web admin media homepage. It does not change uniapp, Android native playback, downloader storage format, or mobile startup behavior.

## Current Context

Video feed items currently come from `/admin/api/findVideoDataList` and are rendered by `backstage/src/main/resources/templates/admin/index.html`. Feed mode uses full-screen `scroll-snap`, `feedAllItems`, and a small pool of video elements mounted into the current/nearby feed items.

Graphic works already exist in `biz_graphic_content` through `GraphicContentEntity`. A graphic item has `images`, `title`, `content`, `author`, `platform`, `publishtime`, `createtime`, `sourceurl`, and `markroute`. Existing `graphicContent.html` parses `images` with `JSON.parse(item.images)` and identifies image/video media from file extensions, so the mixed feed can reuse the stored media list without changing downloader output.

## Recommended Approach

Add a unified admin media feed API for `/admin/index`, instead of trying to force graphic content into the existing video DTO.

The new endpoint should return a normal paged response whose `record.content` contains unified items:

```json
{
  "type": "video",
  "id": 123,
  "mediaKey": "video:123",
  "platform": "抖音",
  "author": "作者",
  "title": "作品名",
  "desc": "摘要",
  "publishTime": "2026-06-28 11:17:55",
  "createTime": "...",
  "cover": "...",
  "playurl": "/hls/.../index.m3u8",
  "fallbackUrl": "/video.mp4",
  "hlsstatus": "已完成"
}
```

```json
{
  "type": "graphic",
  "id": 456,
  "mediaKey": "graphic:456",
  "platform": "抖音",
  "author": "作者",
  "title": "图文标题",
  "desc": "正文摘要",
  "publishTime": "2026-06-28 11:17:55",
  "createTime": "...",
  "cover": "/first-slide.jpeg",
  "sourceurl": "https://...",
  "slides": [
    { "type": "image", "url": "/a.jpeg" },
    { "type": "video", "url": "/b.mp4" }
  ]
}
```

Video and graphic items should be merged by effective time: prefer publish time, then create time, then id as a stable tie-breaker. The first implementation can fetch a bounded page from each source, merge in Java, and return one mixed page. This keeps implementation risk low and is acceptable because `/admin/index` page sizes are modest. A later optimization can move merge pagination into SQL if needed.

## Backend Components

Add a DTO such as `AdminMediaFeedItem` and nested `AdminMediaSlide`.

Add a service method such as `MediaFeedService.findPage(MediaFeedQuery)` or place it near existing admin media services if the codebase prefers fewer services. It should:

- Query lightweight video rows without `jsonData` and `videoinfo`, reusing the previous `/admin/index` lite behavior.
- Query lightweight graphic rows without `jsonData`; include `images` because it is needed to build slides.
- Parse `GraphicContentEntity.images` as a JSON array of strings.
- Classify slide media by extension: image for jpg/jpeg/png/webp/gif, video for mp4/webm/mov/m4v. Unknown values should be ignored or treated as image only if they look renderable.
- Drop graphic items with no valid slides from the mixed feed, or return them with an empty state only if diagnostics need it. Recommended: drop from feed and keep them visible in the existing graphic list page.
- Keep existing video endpoint behavior unchanged for other pages.

Add admin controller endpoint:

`POST /admin/api/findMediaFeedList`

It should accept the same feed filters where reasonable: `pageNo`, `pageSize`, `sortField`, `sortOrder`, `randomMode`, `randomSeed`, and author filter. Author filter should match video `videoauthor` and graphic `author`.

## Frontend Components

Update `/admin/index` feed mode to request `/admin/api/findMediaFeedList` by default. Grid mode can remain video-only for the first phase unless explicitly expanded later.

`buildFeedItemHtml(item, isPrivate)` should branch by `item.type`:

- `video`: render the current `.feed-video-host` path and use the existing video pool, HLS fallback, controls, debug panel, and source badge.
- `graphic`: render `.feed-graphic-host` with slide children or lazy slide data. It should show one slide at a time, use `object-fit: contain`, and keep the same overlay style for title, platform, time, and author.

Graphic slideshow behavior:

- Show progress dots or segmented bars for slides.
- Auto-advance images after a fixed interval, recommended 4 seconds.
- For video slides, use a local video element and advance when `ended` fires.
- Pause slideshow/video when the feed item is not current.
- Resume the current slide when the item becomes current.
- Left/right click or horizontal swipe changes slides inside the graphic item.
- Vertical scroll/swipe continues to move between feed items.
- When the last slide finishes and feed auto-next is enabled, call the existing next-item path.

The existing four pooled feed video elements should remain for normal video feed items. Graphic item videos should either use a small per-item video element only while current, or a separate lightweight graphic-video helper; they should not be inserted into the existing HLS video pool unless HLS support for graphic videos is later required.

## Interaction And UI

Graphic items should feel like Douyin image-text posts:

- Full black background.
- Media centered and contained, without cropping important image content.
- Overlay title/description/author at the bottom, matching current video feed overlays.
- A small `图文` badge or image-stack icon near the source badge area.
- Slide progress displayed unobtrusively at top or bottom.
- Manual slide controls should be discoverable but not text-heavy.

The mixed feed should not add a required mode switch. It may later add a compact filter for `全部 / 视频 / 图文`, but default behavior is mixed `全部`.

## Performance

The first mixed endpoint must stay lightweight:

- Do not return video `jsonData` or `videoinfo`.
- Do not return graphic `jsonData`.
- Return only parsed slide URLs and basic metadata.
- Keep page size bounded.
- Preload only current and nearby item covers/slides.
- Avoid loading every image in a graphic set before it becomes current.

The existing index initializer should include graphic indexes useful for mixed feed ordering and filtering, such as `platform, videoid`, `publishtime, id`, `createtime, id`, and `author` if not already present.

## Error Handling

- Malformed `images` JSON should not break the page. Log the item id server-side and skip the item or return an empty slide list.
- Missing media files should show an in-item fallback state and allow feed navigation to continue.
- A failing video slide inside a graphic item should advance to the next slide if available.
- Existing video playback errors and HLS fallback behavior should remain unchanged.

## Testing

Backend tests:

- Video and graphic rows merge in descending time order.
- Graphic `images` strings become image/video slides with correct type detection.
- Malformed or empty `images` does not fail the whole endpoint.
- Unified DTO excludes large fields such as video `jsonData`, video `videoinfo`, and graphic `jsonData`.
- Author filter applies to both videos and graphics.

Frontend checks:

- Existing video feed item still plays.
- Graphic item renders first slide and metadata.
- Image slide auto-advances.
- Video slide advances on ended.
- Leaving a graphic item pauses timers and video playback.
- Mixed feed does not break keyboard, wheel, playlist, and author drawer basics.

## Phasing

Phase 1: Add mixed feed API and render graphic slideshow items in `/admin/index` feed mode.

Phase 2: Add optional `全部 / 视频 / 图文` filter if useful.

Phase 3: Replace outer scroll-snap with a Douyin-like transform slide track so dragging reveals adjacent items during movement.
