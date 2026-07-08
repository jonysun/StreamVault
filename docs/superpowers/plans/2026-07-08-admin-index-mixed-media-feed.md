# Admin Index Mixed Media Feed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/admin/index` feed mode default to a mixed feed of videos and graphic/image-text works, with graphic works rendered as Douyin-like slideshows.

**Architecture:** Add a lightweight unified media feed API that maps videos and graphic content into a shared DTO, then update only the `/admin/index` feed renderer to branch between video items and graphic slideshow items. Existing video endpoints, grid mode, downloader storage, mobile/uniapp, and Android native code stay unchanged.

**Tech Stack:** Spring Boot 3.5, Spring Data JPA, SQLite, Thymeleaf admin template, jQuery, vanilla JavaScript, existing HLS.js feed helpers.

---

## File Structure

- Create `backstage/src/main/java/com/flower/spirit/dto/AdminMediaSlide.java`: a single graphic slide with `type` and `url`.
- Create `backstage/src/main/java/com/flower/spirit/dto/AdminMediaFeedItem.java`: unified feed item for both videos and graphics.
- Create `backstage/src/main/java/com/flower/spirit/service/MediaFeedService.java`: parses graphic `images`, maps video/graphic rows, merges and pages mixed items.
- Create `backstage/src/test/java/com/flower/spirit/service/MediaFeedServiceTest.java`: parser and mapper tests.
- Modify `backstage/src/main/java/com/flower/spirit/web/admin/AdminController.java`: add `POST /admin/api/findMediaFeedList`.
- Modify `backstage/src/main/java/com/flower/spirit/config/DatabaseIndexInitializer.java`: add graphic feed indexes.
- Modify `backstage/src/main/resources/templates/admin/index.html`: request mixed feed and render graphic slideshows.

---

### Task 1: Add Unified Media Feed DTOs

**Files:**
- Create: `backstage/src/main/java/com/flower/spirit/dto/AdminMediaSlide.java`
- Create: `backstage/src/main/java/com/flower/spirit/dto/AdminMediaFeedItem.java`
- Test: `backstage/src/test/java/com/flower/spirit/dto/AdminMediaFeedItemTest.java`

- [ ] **Step 1: Write DTO surface test**

Create `AdminMediaFeedItemTest`:

```java
package com.flower.spirit.dto;

import static org.assertj.core.api.Assertions.assertThat;
import java.beans.Introspector;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AdminMediaFeedItemTest {
    @Test
    void dtoContainsMixedFeedFieldsAndExcludesLargeFields() throws Exception {
        AdminMediaFeedItem item = new AdminMediaFeedItem();
        item.setType("graphic");
        item.setId(7);
        item.setMediaKey("graphic:7");
        item.setTitle("title");
        item.setSlides(List.of(new AdminMediaSlide("image", "/cover.jpeg")));

        Set<String> props = Arrays.stream(Introspector.getBeanInfo(AdminMediaFeedItem.class).getPropertyDescriptors())
                .map(descriptor -> descriptor.getName())
                .collect(Collectors.toSet());

        assertThat(item.getMediaKey()).isEqualTo("graphic:7");
        assertThat(item.getSlides()).extracting(AdminMediaSlide::getType).containsExactly("image");
        assertThat(props).contains("type", "mediaKey", "title", "desc", "author", "slides", "playurl", "fallbackUrl");
        assertThat(props).doesNotContain("jsonData", "videoinfo", "videoaddr");
    }
}
```

- [ ] **Step 2: Add DTO classes**

Create `AdminMediaSlide`:

```java
package com.flower.spirit.dto;

public class AdminMediaSlide {
    private String type;
    private String url;
    public AdminMediaSlide() {}
    public AdminMediaSlide(String type, String url) { this.type = type; this.url = url; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
```

Create `AdminMediaFeedItem` with fields and getters/setters:

```java
private String type;
private Integer id;
private String mediaKey;
private String videoid;
private String platform;
private String author;
private String authoruid;
private String authorusername;
private String title;
private String desc;
private String publishTime;
private Date createTime;
private String cover;
private String playurl;
private String fallbackUrl;
private String hlsstatus;
private String sourceurl;
private String originaladdress;
private String favorite;
private String privacy;
private List<AdminMediaSlide> slides = new ArrayList<>();
```

Use normal JavaBean getters/setters for every field. In `setSlides`, convert `null` to `new ArrayList<>()`.

- [ ] **Step 3: Verify DTO test**

Run:

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; tools\apache-maven-3.9.9\bin\mvn.cmd -f backstage\pom.xml "-Dtest=AdminMediaFeedItemTest" test
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 4: Commit**

```powershell
git -c safe.directory=F:/opencode/Project/streamV add -- backstage/src/main/java/com/flower/spirit/dto/AdminMediaSlide.java backstage/src/main/java/com/flower/spirit/dto/AdminMediaFeedItem.java backstage/src/test/java/com/flower/spirit/dto/AdminMediaFeedItemTest.java
git -c safe.directory=F:/opencode/Project/streamV commit -m "Add admin mixed media feed DTOs"
```

---

### Task 2: Add Backend Mixed Feed Service

**Files:**
- Create: `backstage/src/main/java/com/flower/spirit/service/MediaFeedService.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/MediaFeedServiceTest.java`

- [ ] **Step 1: Write parser and mapper tests**

Create tests for these exact behaviors:

```java
@Test
void parseGraphicSlidesDetectsImageAndVideoExtensions() {
    List<AdminMediaSlide> slides = service.parseGraphicSlidesForTest("[\"/a.jpeg\",\"/b.mp4\",\"/c.webp\"]");
    assertThat(slides).extracting(AdminMediaSlide::getType).containsExactly("image", "video", "image");
}

@Test
void parseGraphicSlidesReturnsEmptyListForMalformedJson() {
    assertThat(service.parseGraphicSlidesForTest("not-json")).isEmpty();
}

@Test
void graphicWithSlidesMapsToFeedItem() {
    GraphicContentEntity graphic = new GraphicContentEntity();
    graphic.setId(9);
    graphic.setTitle("graphic title");
    graphic.setAuthor("author");
    graphic.setImages("[\"/first.jpeg\",\"/second.mp4\"]");
    AdminMediaFeedItem item = service.toGraphicFeedItemForTest(graphic);
    assertThat(item.getType()).isEqualTo("graphic");
    assertThat(item.getMediaKey()).isEqualTo("graphic:9");
    assertThat(item.getCover()).isEqualTo("/first.jpeg");
    assertThat(item.getSlides()).hasSize(2);
}
```

- [ ] **Step 2: Implement service**

`MediaFeedService` should expose:

```java
public AjaxEntity findPage(VideoDataEntity query)
List<AdminMediaSlide> parseGraphicSlidesForTest(String rawImages)
AdminMediaFeedItem toGraphicFeedItemForTest(GraphicContentEntity graphic)
AdminMediaFeedItem toVideoFeedItemForTest(VideoDataEntity video)
```

Implementation rules:

- Use `videoDataService.findPage(videoQuery, true)` for lightweight videos.
- Use `graphicContentService.findPage(graphicQuery)` for graphics, but map only metadata and parsed `images`; never return `jsonData`.
- Convert `AdminVideoListItem` into `AdminMediaFeedItem` with `type="video"` and `mediaKey="video:" + id`.
- Convert `GraphicContentEntity` into `AdminMediaFeedItem` with `type="graphic"` and `mediaKey="graphic:" + id`.
- Parse `images` via `JSON.parseArray(rawImages)`.
- Detect image extensions: `.jpg`, `.jpeg`, `.png`, `.webp`, `.gif`.
- Detect video extensions: `.mp4`, `.webm`, `.mov`, `.m4v`.
- Sort merged results by `publishTime`, then `createTime`, then `id`; reverse unless `sortOrder=asc`.

- [ ] **Step 3: Verify service tests**

Run:

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; tools\apache-maven-3.9.9\bin\mvn.cmd -f backstage\pom.xml "-Dtest=MediaFeedServiceTest" test
```

Expected: all `MediaFeedServiceTest` tests pass.

- [ ] **Step 4: Commit**

```powershell
git -c safe.directory=F:/opencode/Project/streamV add -- backstage/src/main/java/com/flower/spirit/service/MediaFeedService.java backstage/src/test/java/com/flower/spirit/service/MediaFeedServiceTest.java
git -c safe.directory=F:/opencode/Project/streamV commit -m "Add mixed admin media feed service"
```

---

### Task 3: Add API Endpoint And Graphic Indexes

**Files:**
- Modify: `backstage/src/main/java/com/flower/spirit/web/admin/AdminController.java`
- Modify: `backstage/src/main/java/com/flower/spirit/config/DatabaseIndexInitializer.java`
- Test: `backstage/src/test/java/com/flower/spirit/config/DatabaseIndexInitializerTest.java`

- [ ] **Step 1: Add controller endpoint**

Add field:

```java
@Autowired
private MediaFeedService mediaFeedService;
```

Add endpoint near `findVideoDataList`:

```java
@PostMapping(value = "/findMediaFeedList")
public AjaxEntity findMediaFeedList(VideoDataEntity videoDataEntity, HttpServletRequest request) {
    return mediaFeedService.findPage(videoDataEntity);
}
```

- [ ] **Step 2: Add graphic indexes**

Ensure `defaultIndexSqlStatements()` includes:

```java
"CREATE INDEX IF NOT EXISTS idx_graphic_content_publishtime_id ON biz_graphic_content(publishtime, id)",
"CREATE INDEX IF NOT EXISTS idx_graphic_content_createtime_id ON biz_graphic_content(createtime, id)",
"CREATE INDEX IF NOT EXISTS idx_graphic_content_author ON biz_graphic_content(author)"
```

Keep existing `idx_graphic_content_platform_videoid` and do not duplicate it.

- [ ] **Step 3: Update index test**

Assert that the SQL list contains the three new graphic indexes and still starts every statement with `CREATE INDEX IF NOT EXISTS`.

- [ ] **Step 4: Verify backend tests**

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; tools\apache-maven-3.9.9\bin\mvn.cmd -f backstage\pom.xml "-Dtest=MediaFeedServiceTest,DatabaseIndexInitializerTest" test
```

Expected: all listed tests pass.

- [ ] **Step 5: Commit**

```powershell
git -c safe.directory=F:/opencode/Project/streamV add -- backstage/src/main/java/com/flower/spirit/web/admin/AdminController.java backstage/src/main/java/com/flower/spirit/config/DatabaseIndexInitializer.java backstage/src/test/java/com/flower/spirit/config/DatabaseIndexInitializerTest.java
git -c safe.directory=F:/opencode/Project/streamV commit -m "Add mixed admin media feed endpoint"
```

---

### Task 4: Switch `/admin/index` Feed To Mixed Endpoint

**Files:**
- Modify: `backstage/src/main/resources/templates/admin/index.html`

- [ ] **Step 1: Add endpoint helper**

```javascript
function getFeedListEndpoint() {
    return mediaHomeMode === 'feed' ? '/admin/api/findMediaFeedList' : '/admin/api/findVideoDataList';
}
```

- [ ] **Step 2: Update `findList(page)`**

Replace:

```javascript
$.post("/admin/api/findVideoDataList", option, function(data, status) {
```

with:

```javascript
$.post(getFeedListEndpoint(), option, function(data, status) {
```

Keep `option.lite = '1';`.

- [ ] **Step 3: Normalize feed metadata**

At the start of `buildFeedItemHtml(item, isPrivate)`, add:

```javascript
var itemType = item.type || 'video';
var titleValue = item.title || item.videoname || '未命名视频';
var platformValue = item.platform || item.videoplatform || '未知平台';
var authorValue = item.author || item.videoauthor || '未知作者';
var timeValue = item.publishTime || item.publishtime || item.createTime || item.createtime;
var safeTitle = isPrivate ? '******' : escapeHtml(titleValue);
var safePlatform = isPrivate ? '****' : escapeHtml(platformValue);
var safeAuthor = isPrivate ? '****' : escapeHtml(authorValue);
var timeText = formatTime(timeValue);
```

- [ ] **Step 4: Verify script syntax**

```powershell
node -e "const fs=require('fs'); const html=fs.readFileSync('backstage/src/main/resources/templates/admin/index.html','utf8'); const scripts=[...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)].map(m=>m[1]).filter(s=>s.trim()); for (const s of scripts) new Function(s); console.log('scripts ok', scripts.length);"
```

Expected: `scripts ok 1`.

- [ ] **Step 5: Commit**

```powershell
git -c safe.directory=F:/opencode/Project/streamV add -- backstage/src/main/resources/templates/admin/index.html
git -c safe.directory=F:/opencode/Project/streamV commit -m "Use mixed media feed on admin index"
```

---

### Task 5: Render Graphic Feed Items As Slideshows

**Files:**
- Modify: `backstage/src/main/resources/templates/admin/index.html`

- [ ] **Step 1: Add slideshow CSS**

Add CSS classes: `.feed-graphic-host`, `.feed-graphic-slide`, `.feed-graphic-slide.active`, `.feed-graphic-progress`, `.feed-graphic-badge`. Use full-screen black background, `object-fit: contain`, and small top progress bars.

- [ ] **Step 2: Add slideshow helpers**

Add functions:

```javascript
function normalizeGraphicSlides(item) {
    var slides = Array.isArray(item.slides) ? item.slides : [];
    return slides.filter(function(slide) {
        return slide && slide.url && (slide.type === 'image' || slide.type === 'video');
    });
}

function activateGraphicSlide(host, index) {
    var slides = Array.prototype.slice.call(host.querySelectorAll('.feed-graphic-slide'));
    if (!slides.length) return;
    var nextIndex = Math.max(0, Math.min(index, slides.length - 1));
    slides.forEach(function(slide, i) {
        var active = i === nextIndex;
        slide.classList.toggle('active', active);
        var video = slide.querySelector('video');
        if (video && !active) { try { video.pause(); } catch (e) {} }
    });
    Array.prototype.slice.call(host.querySelectorAll('.feed-graphic-progress span')).forEach(function(dot, i) {
        dot.classList.toggle('active', i === nextIndex);
    });
    host.setAttribute('data-current-slide', String(nextIndex));
}
```

- [ ] **Step 3: Branch `buildFeedItemHtml` for graphics**

If `itemType === 'graphic'`, return a `.feed-item.feed-graphic-item` with `.feed-graphic-host`, slide progress, slide DOM, existing overlay metadata, and `.feed-bottom-hover-zone`. Use existing video HTML for non-graphic items.

- [ ] **Step 4: Add slideshow lifecycle**

Add `startGraphicSlideshow(itemEl)`, `stopGraphicSlideshow(itemEl)`, and `bindGraphicSlideshowControls()`.

Required behavior:

- Image slides auto-advance after 4000 ms.
- Video slides call `play()` and advance on `ended`.
- Leaving the item clears timers and pauses slide videos.
- Clicking left/right half of the item moves previous/next slide.
- Last slide calls `jumpToFeedAbsIndex(absIndex + 1)` when `feedAutoNext` is true.

- [ ] **Step 5: Integrate with `initFeedAutoPlay()`**

Before current video handling in the IntersectionObserver callback:

```javascript
if (entry.target.getAttribute('data-feed-type') === 'graphic') {
    if (entry.isIntersecting && entry.intersectionRatio >= 0.5) {
        startGraphicSlideshow(entry.target);
    } else if (entry.intersectionRatio <= 0.15) {
        stopGraphicSlideshow(entry.target);
    }
    return;
}
```

Call `bindGraphicSlideshowControls();` after `bindFeedVideoControls();`.

- [ ] **Step 6: Verify script syntax and commit**

Run the Node syntax command from Task 4, then:

```powershell
git -c safe.directory=F:/opencode/Project/streamV add -- backstage/src/main/resources/templates/admin/index.html
git -c safe.directory=F:/opencode/Project/streamV commit -m "Render graphic slideshows in admin feed"
```

---

### Task 6: Preserve Existing Feed Controls

**Files:**
- Modify: `backstage/src/main/resources/templates/admin/index.html`

- [ ] **Step 1: Update author collection**

In `appendFeedAuthorOptions(list)`, use:

```javascript
var author = (list[i].author || list[i].videoauthor || '').trim();
```

- [ ] **Step 2: Update playlist rendering**

Use unified fields:

```javascript
var title = item.privacy === '1' || item.videoprivacy === '1' ? '******' : (item.title || item.videoname || '未命名视频');
var author = item.privacy === '1' || item.videoprivacy === '1' ? '****' : (item.author || item.videoauthor || '未知作者');
var typeLabel = item.type === 'graphic' ? '图文' : '视频';
```

- [ ] **Step 3: Update feed item key**

```javascript
return String(item.mediaKey || item.playurl || item.videounrealaddr || item.title || item.videoname || '');
```

- [ ] **Step 4: Verify and commit**

Run Node syntax check, then commit:

```powershell
git -c safe.directory=F:/opencode/Project/streamV add -- backstage/src/main/resources/templates/admin/index.html
git -c safe.directory=F:/opencode/Project/streamV commit -m "Keep admin feed controls working with graphics"
```

---

### Task 7: Full Verification And Push

- [ ] **Step 1: Run focused tests**

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; tools\apache-maven-3.9.9\bin\mvn.cmd -f backstage\pom.xml "-Dtest=AdminMediaFeedItemTest,MediaFeedServiceTest,DatabaseIndexInitializerTest,VideoDataServiceFindAllTest" test
```

Expected: all tests pass.

- [ ] **Step 2: Run full Maven test if disk space permits**

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; tools\apache-maven-3.9.9\bin\mvn.cmd -f backstage\pom.xml clean test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Run JS syntax check**

Run the Node syntax command from Task 4. Expected: `scripts ok 1`.

- [ ] **Step 4: Check scope**

```powershell
git -c safe.directory=F:/opencode/Project/streamV diff --name-only HEAD~6..HEAD
```

Expected: no `app/uniapp` or `app/android-native-plugin` files.

- [ ] **Step 5: Push**

```powershell
git -c safe.directory=F:/opencode/Project/streamV push origin HEAD:refs/heads/codex/admin-index-mixed-media-feed
```

---

## Self-Review

Spec coverage: mixed default feed is covered by Tasks 2-4; graphic slideshow is covered by Task 5; existing video behavior is preserved by branching in Task 4 and lifecycle guards in Task 5; controls are covered by Task 6; verification is covered by Task 7.

Marker scan: no unresolved markers or open-ended validation steps remain. Each task names files and includes exact commands and expected results.

Type consistency: the unified item fields are `type`, `mediaKey`, `platform`, `author`, `title`, `desc`, `publishTime`, `createTime`, `cover`, `playurl`, `fallbackUrl`, `hlsstatus`, `slides`, `privacy`, and `favorite`; these are defined in Task 1 and used consistently in backend and frontend tasks.
