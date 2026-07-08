# Admin Index Web Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Docker `/admin/index` load lighter video list data, add safe database indexes, and add web-only playback timing diagnostics without changing mobile or media files.

**Architecture:** Add a lightweight DTO path behind `lite=1` for `/admin/api/findVideoDataList`, keeping the original full entity response available. Add a small startup index initializer for SQLite-compatible idempotent indexes. Update only `admin/index.html` to request lightweight data and emit playback timing diagnostics for MP4/HLS analysis.

**Tech Stack:** Spring Boot 3.5, Spring Data JPA, SQLite JDBC, Thymeleaf admin templates, jQuery, HLS.js, JUnit 5, Mockito, AssertJ.

---

## File Structure

- Create `backstage/src/main/java/com/flower/spirit/dto/AdminVideoListItem.java`
  - Lightweight DTO for `/admin/index` video list rows.
- Modify `backstage/src/main/java/com/flower/spirit/service/VideoDataService.java`
  - Add `findPage(VideoDataEntity, boolean lite)` and DTO mapping while keeping current `findPage(VideoDataEntity)` behavior.
- Modify `backstage/src/main/java/com/flower/spirit/web/admin/AdminController.java`
  - Read `lite` request parameter for `/admin/api/findVideoDataList`.
- Create `backstage/src/main/java/com/flower/spirit/service/DatabaseIndexInitializer.java`
  - Run idempotent `CREATE INDEX IF NOT EXISTS` SQL after application startup.
- Modify `backstage/src/main/resources/templates/admin/index.html`
  - Send `lite=1` for `/admin/index` list requests.
  - Add web-only list and playback timing diagnostics.
- Modify `backstage/src/test/java/com/flower/spirit/service/VideoDataServiceFindAllTest.java`
  - Add tests for lightweight DTO shape and full response compatibility.
- Create `backstage/src/test/java/com/flower/spirit/service/DatabaseIndexInitializerTest.java`
  - Verify index SQL is idempotent and complete.

---

### Task 1: Lightweight Admin Video DTO And Service Path

**Files:**
- Create: `backstage/src/main/java/com/flower/spirit/dto/AdminVideoListItem.java`
- Modify: `backstage/src/main/java/com/flower/spirit/service/VideoDataService.java`
- Test: `backstage/src/test/java/com/flower/spirit/service/VideoDataServiceFindAllTest.java`

- [ ] **Step 1: Write the failing DTO serialization test**

Append this test to `VideoDataServiceFindAllTest`:

```java
@Test
void findPageLiteReturnsAdminListItemsWithoutRawJsonFields() throws Exception {
	boolean previousHlsEnable = Global.hlsEnable;
	try {
		Global.hlsEnable = false;
		VideoDataEntity request = new VideoDataEntity();
		request.setPageNo(0);
		request.setPageSize(10);

		VideoDataEntity video = video(42, "/cos/douyin/a.mp4");
		video.setVideoid("aweme-42");
		video.setVideoname("work title");
		video.setVideodesc("short summary");
		video.setVideoplatform("抖音");
		video.setVideocover("/cos/douyin/a.jpg");
		video.setVideoprivacy("0");
		video.setVideotag("tag");
		video.setVideoauthor("author name");
		video.setAuthoruid("MS4-author");
		video.setAuthorusername("author_user");
		video.setPublishtime("2026-06-28 10:00:00");
		video.setSourceurl("https://www.douyin.com/video/aweme-42");
		video.setFavorite("1");
		video.setJsonData("{\"heavy\":true}");
		video.setVideoinfo("{\"duplicate\":true}");

		when(videoDataDao.findAll(any(Specification.class), any(Pageable.class)))
				.thenAnswer(invocation -> new PageImpl<>(List.of(video), invocation.getArgument(1), 1));
		when(hlsTranscodeService.queuedIdsSnapshot()).thenReturn(new HashSet<>());

		AjaxEntity response = videoDataService.findPage(request, true);

		assertThat(response.getResCode()).isEqualTo(Global.ajax_success);
		assertThat(response.getRecord()).isInstanceOf(org.springframework.data.domain.Page.class);
		org.springframework.data.domain.Page<?> page = (org.springframework.data.domain.Page<?>) response.getRecord();
		assertThat(page.getContent()).hasSize(1);
		Object item = page.getContent().get(0);
		assertThat(item).isInstanceOf(com.flower.spirit.dto.AdminVideoListItem.class);
		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		String json = mapper.writeValueAsString(item);
		assertThat(json).contains("work title", "short summary", "author name", "2026-06-28");
		assertThat(json).doesNotContain("jsonData", "videoinfo", "heavy", "duplicate");
	} finally {
		Global.hlsEnable = previousHlsEnable;
	}
}

@Test
void findPageWithoutLiteKeepsFullVideoEntityResponse() {
	VideoDataEntity request = new VideoDataEntity();
	request.setPageNo(0);
	request.setPageSize(10);
	VideoDataEntity video = video(43, "/cos/douyin/full.mp4");
	video.setJsonData("{\"heavy\":true}");
	video.setVideoinfo("{\"duplicate\":true}");

	when(videoDataDao.findAll(any(Specification.class), any(Pageable.class)))
			.thenAnswer(invocation -> new PageImpl<>(List.of(video), invocation.getArgument(1), 1));
	when(hlsTranscodeService.queuedIdsSnapshot()).thenReturn(new HashSet<>());

	AjaxEntity response = videoDataService.findPage(request);

	org.springframework.data.domain.Page<?> page = (org.springframework.data.domain.Page<?>) response.getRecord();
	assertThat(page.getContent().get(0)).isInstanceOf(VideoDataEntity.class);
	assertThat(((VideoDataEntity) page.getContent().get(0)).getJsonData()).contains("heavy");
	assertThat(((VideoDataEntity) page.getContent().get(0)).getVideoinfo()).contains("duplicate");
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\tools\apache-maven-3.9.9\bin\mvn.cmd -f backstage\pom.xml "-Dtest=VideoDataServiceFindAllTest" test
```

Expected: FAIL because `AdminVideoListItem` and `VideoDataService.findPage(VideoDataEntity, boolean)` do not exist.

- [ ] **Step 3: Create the lightweight DTO**

Create `backstage/src/main/java/com/flower/spirit/dto/AdminVideoListItem.java`:

```java
package com.flower.spirit.dto;

import java.util.Date;

import com.flower.spirit.entity.VideoDataEntity;

public record AdminVideoListItem(
		Integer id,
		String videoid,
		String videoname,
		String videodesc,
		String videoplatform,
		String videocover,
		String videounrealaddr,
		String playurl,
		String videoprivacy,
		String videotag,
		String videoauthor,
		String authoruid,
		String authorusername,
		String publishtime,
		Date createtime,
		String hlsstatus,
		String sourceurl,
		String favorite) {

	public static AdminVideoListItem from(VideoDataEntity item) {
		if (item == null) {
			return null;
		}
		return new AdminVideoListItem(
				item.getId(),
				item.getVideoid(),
				item.getVideoname(),
				item.getVideodesc(),
				item.getVideoplatform(),
				item.getVideocover(),
				item.getVideounrealaddr(),
				item.getPlayurl(),
				item.getVideoprivacy(),
				item.getVideotag(),
				item.getVideoauthor(),
				item.getAuthoruid(),
				item.getAuthorusername(),
				item.getPublishtime(),
				item.getCreatetime(),
				item.getHlsstatus(),
				item.getSourceurl(),
				item.getFavorite());
	}
}
```

- [ ] **Step 4: Add the lite service overload**

Modify `VideoDataService.java`:

Add import:

```java
import com.flower.spirit.dto.AdminVideoListItem;
```

Replace the current `findPage(VideoDataEntity res)` method body with:

```java
public AjaxEntity findPage(VideoDataEntity res) {
	return findPage(res, false);
}

public AjaxEntity findPage(VideoDataEntity res, boolean lite) {
	int pageNo = res == null ? 0 : Math.max(0, res.getPageNo());
	int pageSize = res == null ? 25 : Math.max(1, res.getPageSize());
	PageRequest of = PageRequest.of(pageNo, pageSize);
	boolean randomMode = res != null && "1".equals(String.valueOf(res.getRandomMode()));
	String randomSeed = res == null ? null : res.getRandomSeed();
	Specification<VideoDataEntity> specification = buildFindSpecification(res, randomMode);

	Page<VideoDataEntity> findAll;
	if (randomMode) {
		List<VideoDataEntity> all = videoDataDao.findAll(specification);
		stabilizeRandomSourceOrder(all);
		long seed = randomSeed == null ? System.nanoTime() : randomSeed.hashCode();
		java.util.Random random = new java.util.Random(seed);
		java.util.Collections.shuffle(all, random);
		int from = Math.min(pageNo * pageSize, all.size());
		int to = Math.min(from + pageSize, all.size());
		List<VideoDataEntity> pageList = from >= to ? new ArrayList<>() : all.subList(from, to);
		findAll = new PageImpl<>(pageList, of, all.size());
	} else {
		findAll = videoDataDao.findAll(specification, of);
	}
	if (findAll != null && findAll.getContent() != null) {
		enrichVideoItems(findAll.getContent());
	}
	if (lite) {
		Page<AdminVideoListItem> litePage = findAll.map(AdminVideoListItem::from);
		return new AjaxEntity(Global.ajax_success, "数据获取成功", litePage);
	}
	return new AjaxEntity(Global.ajax_success, "数据获取成功", findAll);
}
```

- [ ] **Step 5: Run the focused test and verify it passes**

Run:

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\tools\apache-maven-3.9.9\bin\mvn.cmd -f backstage\pom.xml "-Dtest=VideoDataServiceFindAllTest" test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

Run:

```powershell
git add backstage/src/main/java/com/flower/spirit/dto/AdminVideoListItem.java backstage/src/main/java/com/flower/spirit/service/VideoDataService.java backstage/src/test/java/com/flower/spirit/service/VideoDataServiceFindAllTest.java
git commit -m "Add lightweight admin video list DTO"
```

---

### Task 2: Wire `lite=1` Through Admin Controller And `/admin/index`

**Files:**
- Modify: `backstage/src/main/java/com/flower/spirit/web/admin/AdminController.java`
- Modify: `backstage/src/main/resources/templates/admin/index.html`

- [ ] **Step 1: Update the admin controller signature**

In `AdminController.java`, add import:

```java
import org.springframework.web.bind.annotation.RequestParam;
```

Replace the `/findVideoDataList` method with:

```java
@PostMapping(value = "/findVideoDataList")
public AjaxEntity findVideoDataList(VideoDataEntity videoDataEntity,
		@RequestParam(name = "lite", defaultValue = "0") String lite,
		HttpServletRequest request) {
	boolean liteMode = "1".equals(lite) || "true".equalsIgnoreCase(lite);
	return videoDataService.findPage(videoDataEntity, liteMode);
}
```

- [ ] **Step 2: Add `lite=1` to `/admin/index` list requests**

In `backstage/src/main/resources/templates/admin/index.html`, find the `findList(page)` function and replace the option construction with:

```javascript
var option = mediaHomeMode === 'feed'
	? buildFeedRequestOption(page, feedPageSize)
	: { pageNo: page, pageSize: 25, sortField: 'publishtime', sortOrder: 'desc' };
option.lite = '1';
```

- [ ] **Step 3: Add list request timing logs**

In the same `findList(page)` function, immediately before `$.post("/admin/api/findVideoDataList", option, function(data, status) {`, add:

```javascript
var listRequestStart = window.performance && performance.now ? performance.now() : Date.now();
```

At the start of the `$.post` success callback, add:

```javascript
var listRequestEnd = window.performance && performance.now ? performance.now() : Date.now();
var responseCount = data && data.record && data.record.content ? data.record.content.length : 0;
console.log('[AdminIndexPerf] list response', {
	mode: mediaHomeMode,
	page: page,
	count: responseCount,
	durationMs: Math.round(listRequestEnd - listRequestStart),
	lite: option.lite
});
```

- [ ] **Step 4: Run backend compilation tests**

Run:

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\tools\apache-maven-3.9.9\bin\mvn.cmd -f backstage\pom.xml "-Dtest=VideoDataServiceFindAllTest" test
```

Expected: PASS.

- [ ] **Step 5: Verify the template contains `lite=1`**

Run:

```powershell
rg -n "option\\.lite = '1'|\\[AdminIndexPerf\\] list response" backstage/src/main/resources/templates/admin/index.html
```

Expected: both patterns are found.

- [ ] **Step 6: Commit Task 2**

Run:

```powershell
git add backstage/src/main/java/com/flower/spirit/web/admin/AdminController.java backstage/src/main/resources/templates/admin/index.html
git commit -m "Use lightweight video list on admin index"
```

---

### Task 3: Add Idempotent Database Index Initializer

**Files:**
- Create: `backstage/src/main/java/com/flower/spirit/service/DatabaseIndexInitializer.java`
- Create: `backstage/src/test/java/com/flower/spirit/service/DatabaseIndexInitializerTest.java`

- [ ] **Step 1: Write the failing index initializer test**

Create `backstage/src/test/java/com/flower/spirit/service/DatabaseIndexInitializerTest.java`:

```java
package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseIndexInitializerTest {

	@Test
	void indexSqlContainsExpectedHotPathIndexes() {
		assertThat(DatabaseIndexInitializer.INDEX_SQL)
				.anySatisfy(sql -> assertThat(sql).contains("idx_biz_video_publishtime_id"))
				.anySatisfy(sql -> assertThat(sql).contains("idx_biz_video_createtime_id"))
				.anySatisfy(sql -> assertThat(sql).contains("idx_biz_video_videoauthor"))
				.anySatisfy(sql -> assertThat(sql).contains("idx_biz_video_videoplatform"))
				.anySatisfy(sql -> assertThat(sql).contains("idx_biz_video_videoid"))
				.anySatisfy(sql -> assertThat(sql).contains("idx_biz_video_platform_videoid"))
				.anySatisfy(sql -> assertThat(sql).contains("idx_collect_detail_dataid_videoid"))
				.anySatisfy(sql -> assertThat(sql).contains("idx_collect_detail_dataid_status"))
				.anySatisfy(sql -> assertThat(sql).contains("idx_collect_detail_dataid_mediatype_status"))
				.anySatisfy(sql -> assertThat(sql).contains("idx_author_profile_platform_authoruid"))
				.anySatisfy(sql -> assertThat(sql).contains("idx_graphic_content_platform_videoid"));
		assertThat(DatabaseIndexInitializer.INDEX_SQL).allSatisfy(sql ->
				assertThat(sql).contains("CREATE INDEX IF NOT EXISTS"));
	}

	@Test
	void ensureIndexesExecutesEveryStatement() {
		JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		DatabaseIndexInitializer initializer = new DatabaseIndexInitializer(jdbcTemplate);

		initializer.ensureIndexes();

		verify(jdbcTemplate, times(DatabaseIndexInitializer.INDEX_SQL.size())).execute(contains("CREATE INDEX IF NOT EXISTS"));
	}
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\tools\apache-maven-3.9.9\bin\mvn.cmd -f backstage\pom.xml "-Dtest=DatabaseIndexInitializerTest" test
```

Expected: FAIL because `DatabaseIndexInitializer` does not exist.

- [ ] **Step 3: Create the initializer**

Create `backstage/src/main/java/com/flower/spirit/service/DatabaseIndexInitializer.java`:

```java
package com.flower.spirit.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseIndexInitializer {

	static final List<String> INDEX_SQL = List.of(
			"CREATE INDEX IF NOT EXISTS idx_biz_video_publishtime_id ON biz_video(publishtime, id)",
			"CREATE INDEX IF NOT EXISTS idx_biz_video_createtime_id ON biz_video(createtime, id)",
			"CREATE INDEX IF NOT EXISTS idx_biz_video_videoauthor ON biz_video(videoauthor)",
			"CREATE INDEX IF NOT EXISTS idx_biz_video_videoplatform ON biz_video(videoplatform)",
			"CREATE INDEX IF NOT EXISTS idx_biz_video_videoid ON biz_video(videoid)",
			"CREATE INDEX IF NOT EXISTS idx_biz_video_platform_videoid ON biz_video(videoplatform, videoid)",
			"CREATE INDEX IF NOT EXISTS idx_collect_detail_dataid_videoid ON biz_collect_data_detail(dataid, videoid)",
			"CREATE INDEX IF NOT EXISTS idx_collect_detail_dataid_status ON biz_collect_data_detail(dataid, status)",
			"CREATE INDEX IF NOT EXISTS idx_collect_detail_dataid_mediatype_status ON biz_collect_data_detail(dataid, mediatype, status)",
			"CREATE INDEX IF NOT EXISTS idx_author_profile_platform_authoruid ON biz_author_profile(platform, authoruid)",
			"CREATE INDEX IF NOT EXISTS idx_graphic_content_platform_videoid ON biz_graphic_content(platform, videoid)");

	private static final Logger logger = LoggerFactory.getLogger(DatabaseIndexInitializer.class);

	private final JdbcTemplate jdbcTemplate;

	public DatabaseIndexInitializer(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void ensureIndexes() {
		for (String sql : INDEX_SQL) {
			jdbcTemplate.execute(sql);
		}
		logger.info("[DB] ensured {} application indexes", INDEX_SQL.size());
	}
}
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run:

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\tools\apache-maven-3.9.9\bin\mvn.cmd -f backstage\pom.xml "-Dtest=DatabaseIndexInitializerTest" test
```

Expected: PASS.

- [ ] **Step 5: Commit Task 3**

Run:

```powershell
git add backstage/src/main/java/com/flower/spirit/service/DatabaseIndexInitializer.java backstage/src/test/java/com/flower/spirit/service/DatabaseIndexInitializerTest.java
git commit -m "Add startup database index initializer"
```

---

### Task 4: Add Web Playback Timing Diagnostics

**Files:**
- Modify: `backstage/src/main/resources/templates/admin/index.html`

- [ ] **Step 1: Add timing helper functions**

In `admin/index.html`, near the existing feed metric variables or before `setupVideoSource`, add:

```javascript
function nowMs() {
	return window.performance && performance.now ? performance.now() : Date.now();
}

function markVideoPerf(videoEl, eventName, extra) {
	if (!videoEl) return;
	if (!videoEl._svPerf) {
		videoEl._svPerf = {
			startMs: nowMs(),
			sourceType: videoEl.getAttribute('data-source-type') || 'unknown',
			src: videoEl.currentSrc || videoEl.src || '',
			fallbackSrc: '',
			didFallback: false
		};
	}
	var elapsed = Math.round(nowMs() - videoEl._svPerf.startMs);
	var payload = Object.assign({
		event: eventName,
		elapsedMs: elapsed,
		sourceType: videoEl.getAttribute('data-source-type') || videoEl._svPerf.sourceType,
		src: videoEl.currentSrc || videoEl.src || videoEl._svPerf.src,
		fallbackSrc: videoEl._svPerf.fallbackSrc,
		didFallback: videoEl._svPerf.didFallback,
		readyState: videoEl.readyState,
		networkState: videoEl.networkState
	}, extra || {});
	console.log('[AdminIndexPerf] playback', payload);
	var host = videoEl.parentNode;
	if (host) {
		var panel = host.querySelector('.feed-debug-panel');
		if (panel) {
			panel.textContent = payload.sourceType + ' ' + eventName + ' ' + elapsed + 'ms';
		}
	}
}

function bindVideoPerfEvents(videoEl) {
	if (!videoEl || videoEl._svPerfBound) return;
	videoEl._svPerfBound = true;
	['loadstart', 'loadedmetadata', 'loadeddata', 'canplay', 'playing', 'waiting', 'stalled', 'error'].forEach(function(name) {
		videoEl.addEventListener(name, function() {
			markVideoPerf(videoEl, name);
		});
	});
}
```

- [ ] **Step 2: Bind diagnostics when a video source is set**

At the start of `setupVideoSource(videoEl, src, autoPlayWhenReady)`, immediately after the null guard, add:

```javascript
bindVideoPerfEvents(videoEl);
videoEl._svPerf = {
	startMs: nowMs(),
	sourceType: 'resolving',
	src: src,
	fallbackSrc: '',
	didFallback: false
};
markVideoPerf(videoEl, 'source-resolve-start', { requestedSrc: src });
```

- [ ] **Step 3: Mark resolved source and fallback**

After `var fallback = sourcePack.fallback;`, add:

```javascript
videoEl._svPerf.sourceType = sourceType;
videoEl._svPerf.src = src;
videoEl._svPerf.fallbackSrc = fallback || '';
markVideoPerf(videoEl, 'source-resolved');
```

Inside `fallbackToMp4(reason)`, before `videoEl.src = fallback;`, add:

```javascript
if (videoEl._svPerf) {
	videoEl._svPerf.didFallback = true;
	videoEl._svPerf.fallbackSrc = fallback;
}
markVideoPerf(videoEl, 'fallback-to-mp4', { reason: reason });
```

- [ ] **Step 4: Mark play attempts**

At the start of `playWithSync(videoEl)`, after the null guard, add:

```javascript
markVideoPerf(videoEl, 'play-attempt');
```

- [ ] **Step 5: Verify the template contains diagnostics**

Run:

```powershell
rg -n "AdminIndexPerf|markVideoPerf|bindVideoPerfEvents|source-resolved|fallback-to-mp4|play-attempt" backstage/src/main/resources/templates/admin/index.html
```

Expected: all patterns are found.

- [ ] **Step 6: Commit Task 4**

Run:

```powershell
git add backstage/src/main/resources/templates/admin/index.html
git commit -m "Add admin index playback timing diagnostics"
```

---

### Task 5: Verification

**Files:**
- Verify all modified files from Tasks 1-4.

- [ ] **Step 1: Run focused backend tests**

Run:

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\tools\apache-maven-3.9.9\bin\mvn.cmd -f backstage\pom.xml "-Dtest=VideoDataServiceFindAllTest,DatabaseIndexInitializerTest" test
```

Expected: PASS with zero failures.

- [ ] **Step 2: Run service regression tests**

Run:

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\tools\apache-maven-3.9.9\bin\mvn.cmd -f backstage\pom.xml "-Dtest=AuthorProfileServiceTest,CollectDataServiceStatusTest,DouyinWorkMaintenanceServiceTest,VideoDataServiceFindAllTest,PlatformCookieServiceTest,DatabaseIndexInitializerTest" test
```

Expected: PASS with zero failures.

- [ ] **Step 3: Check staged diff for accidental mobile changes**

Run:

```powershell
git diff --name-only HEAD
```

Expected modified paths are limited to:

```text
backstage/src/main/java/com/flower/spirit/dto/AdminVideoListItem.java
backstage/src/main/java/com/flower/spirit/service/VideoDataService.java
backstage/src/main/java/com/flower/spirit/web/admin/AdminController.java
backstage/src/main/java/com/flower/spirit/service/DatabaseIndexInitializer.java
backstage/src/main/resources/templates/admin/index.html
backstage/src/test/java/com/flower/spirit/service/VideoDataServiceFindAllTest.java
backstage/src/test/java/com/flower/spirit/service/DatabaseIndexInitializerTest.java
```

- [ ] **Step 4: Manually verify Docker web behavior**

Open `/admin/index` in the Docker web app and verify:

```text
Grid mode shows title, summary, platform, cover, and time.
Feed mode shows title, author, platform, time, and playable source.
Network request to /admin/api/findVideoDataList includes lite=1.
Network response omits jsonData and videoinfo.
Console logs include [AdminIndexPerf] list response.
Playing a video logs [AdminIndexPerf] playback milestones.
MP4/HLS source type is visible in playback logs.
```

- [ ] **Step 5: Commit verification fixes if needed**

If verification requires small fixes, commit them with:

```powershell
git add backstage/src/main/java/com/flower/spirit/dto/AdminVideoListItem.java backstage/src/main/java/com/flower/spirit/service/VideoDataService.java backstage/src/main/java/com/flower/spirit/web/admin/AdminController.java backstage/src/main/java/com/flower/spirit/service/DatabaseIndexInitializer.java backstage/src/main/resources/templates/admin/index.html backstage/src/test/java/com/flower/spirit/service/VideoDataServiceFindAllTest.java backstage/src/test/java/com/flower/spirit/service/DatabaseIndexInitializerTest.java
git commit -m "Fix admin index web performance verification issues"
```

If no fixes are needed, do not create an empty commit.
