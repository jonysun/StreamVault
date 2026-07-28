# 收藏任务分批补全与数据库无关持久化重试设计

## 1. 背景

当前抖音作者 `post` 收藏任务已经拆分为独立的抓取队列和下载队列，但普通增量抓取采用“跨过上次发布时间水位，并连续观察到 20 个已知作品后停止”的策略。该策略适合只追踪首次窗口之后的新发布作品，却不会持续补齐首次窗口之前的历史作品。

已确认的目标行为是：每个作者只在原有 Quartz Cron 到期时运行一次；首次按首次数量建立基线，后续每轮最多选择配置数量的本地未知作品进入下载队列。新发布作品优先占用配额，剩余配额继续从新到旧补历史，直到服务器仍公开的作品全部被观察并处理。

生产日志还出现了下载完成后的 SQLite 持久化冲突：

```text
SQLITE_BUSY_SNAPSHOT
insert into biz_graphic_content
```

现有 SQLite 单写协调器可以减少进程内写竞争，但外部连接、未覆盖路径或 WAL 快照升级仍可能产生瞬时冲突。当前冲突会让下载项进入分钟级重试，并重复执行媒体下载。需要把短事务重试放到作品持久化边界，同时保持未来 PostgreSQL 迁移所需的标准并发事务能力。

本设计补充并替代 `2026-07-27-collect-fetch-download-pipeline-design.md` 中“普通增量只追踪最新边界、历史补全只由审计负责”的相关决策。抓取和下载双阶段流水线、单个作品失败隔离、暂停控制和队列状态模型保持不变。

## 2. 已确认决策

| 项目 | 决策 |
| --- | --- |
| 上游请求 | 抖音作品列表继续分页请求，每页 `count=20`，不是一次请求全量列表。 |
| 调度方式 | 只按每个作者原有 Cron 触发，不在本批下载完成后自动触发下一批。 |
| 首次配额 | 使用任务 `omaxcur`；未配置或非正数时默认 80。 |
| 后续配额 | 使用任务 `maxcur`；未配置或非正数时默认 80。 |
| 配额语义 | 每轮最多选择配额数量的本地未知作品。新发布和历史补全共用同一配额。 |
| 选择顺序 | 按上游从最新到最旧的返回顺序选择；不在 Java 端重新打乱。 |
| 普通失败项 | `FAILED` 视为已经观察过，不再占后续普通批次配额；由现有失败重试入口处理。 |
| 远端删除 | 不删除本地媒体，不占普通批次配额，也不据此判定本地记录失效。 |
| 下载并发 | 下载队列仍为单 worker 串行处理；本设计不增加 SQLite 下载并发。 |
| 数据库适配 | 业务层只依赖 `DatabaseWriteExecutor`；SQLite 与 PostgreSQL 使用条件化实现。 |

## 3. 目标与非目标

### 3.1 目标

1. 作者公开作品数量位于配置页数上限覆盖范围内时，在持续运行 Cron 的前提下，按批次逐步把所有本地未知作品加入下载流程。
2. 每轮新发布作品优先；若新作品不足配额，则用更老的未知作品补足。
3. 普通运行不因历史下载失败、已屏蔽作品或活动下载项反复消耗配额。
4. 避免重复扫描产生大量重复 `SKIPPED_EXISTING` run item。
5. SQLite 瞬时 `BUSY/BUSY_SNAPSHOT` 在当前媒体下载内以新事务重试持久化，不立即重新下载媒体。
6. PostgreSQL profile 不使用 SQLite 单写锁、PRAGMA 或 SQLite 错误文本判断。

### 3.2 非目标

- 不改变收藏任务 Cron。
- 不自动删除远端已经删除的本地作品。
- 不在本次实现 PostgreSQL 数据迁移或 PostgreSQL 队列并行领取 SQL。
- 不把网络请求、媒体下载、文件校验或退避 sleep 放进数据库事务。
- 不保证超过配置抓取硬上限的超大型作者在不调整配置的情况下完成全量补齐。
- 不改变 `like`、`fav-`、`recommend` 的有界兼容抓取语义。

## 4. 分页与批次算法

### 4.1 输入

普通 `post` 抓取请求使用：

```text
sec_user_id
known_work_ids
batch_limit
max_pages
mode = initial | incremental | audit
```

`batch_limit` 的选择规则：

```text
initial     -> positive(omaxcur, 80)
incremental -> positive(maxcur, 80)
audit       -> 不使用普通批次配额
```

普通抓取每轮从 `cursor=0` 开始。这样新发布、置顶变化和上游排序调整都先被观察，不依赖跨轮持久化的上游游标。

### 4.2 已观察集合

普通批次的 `known_work_ids` 必须包含：

- `biz_collect_data_detail` 中属于该收藏任务的所有非空作品 ID；
- 当前流水线所有已持久化 run item 的非空作品 ID，包括 `QUEUED`、`RUNNING`、`RETRY_WAIT`、`COMPLETED`、`FAILED` 和所有 `SKIPPED_*` 状态。

这样可以保证：

- 尚未下载完成的活动项不会重复入队；
- 最终失败项不会挤占普通批次；
- 已屏蔽或已存在作品不会每轮重新被当作未知作品；
- 手动失败重试继续复用原 item，不依赖普通抓取创建新 item。

审计模式仍可对已知作品执行 `needsAuditRequeue(...)`，生成 `AUDIT_REPAIR`，不受普通已观察集合的下载抑制影响。

### 4.3 分页停止条件

首次和普通后续运行都按页面顺序处理：

```python
selected = []
observed_count = 0
cursor = 0

for page in pages:
    response = fetch_user_post(cursor=cursor, count=20)
    for work in response.aweme_list:
        observed_count += 1
        if work.id not in known_work_ids and work.id not in selected_ids:
            selected.append(work)
            if len(selected) == batch_limit:
                return BATCH_LIMIT
    if response.has_more == 0:
        return NO_MORE
    cursor = response.max_cursor

return MAX_PAGE_GUARD
```

普通增量不再使用“连续 20 个已知作品”作为成功停止条件。发布时间水位继续保存，用于诊断、展示和异常顺序检测，但不阻止向历史深处寻找本轮未知作品。

分页结果增加或明确以下计数：

```text
selectedCount
observedCount
pagesFetched
lastCursor
outcome = BATCH_LIMIT | NO_MORE | MAX_PAGE_GUARD | existing abnormal outcomes
```

`NO_MORE` 且 `selectedCount < batch_limit` 表示本轮已经扫描到服务器当前列表末尾。该状态不永久关闭任务；下一次 Cron 仍从首页扫描，以发现新发布作品。

### 4.4 页数上限

每页固定 20 条。普通补全需要扫描已知前缀，因此不能继续固定使用 20 页上限。有效页数预算按已观察规模动态增长：

```text
required_pages = ceil((known_work_ids.size + batch_limit + safety_margin) / 20)
effective_max_pages = min(configured_backfill_max_pages,
                          max(configured_incremental_min_pages, required_pages))
```

建议默认：

```properties
streamvault.collect.incremental-min-pages=20
streamvault.collect.backfill-max-pages=500
```

`safety_margin` 至少为一页，用于容纳远端删除、重复项和上游短页。500 页约覆盖 10000 条当前可见作品。触达 `MAX_PAGE_GUARD` 时必须记录告警，不得标记历史补全完成；管理员可以提高硬上限后继续补齐。

### 4.5 示例

作者原有作品编号 `1..1000`，首次配额 80，后续配额 20：

```text
第 1 轮：1000..921
第 2 轮：920..901
第 3 轮前新增 1001..1005
第 3 轮：1005..1001 + 900..886
第 4 轮：885..866
...
```

每一轮最多产生 20 个普通下载候选。只要作者作品总数未超过配置硬上限，并且 Cron 持续运行，最终会观察完服务器仍公开的全部作品。

## 5. 抓取结果与 run item

重复从首页扫描会观察越来越长的已知前缀。不能把所有已知观察项在每轮都写入 `biz_collect_run_item`，否则一个 1000 作品作者每轮都会产生近千条重复记录。

普通模式只持久化以下项目：

1. 本轮选中的未知候选；
2. 候选经过 Java 本地规则后形成的 `NEW/QUEUED`、`BLOCKED/SKIPPED_BLOCKED` 等最终决策；
3. 为诊断必须保留的异常作品记录，数量必须有界。

已知前缀只进入本轮汇总和有界诊断，不重复创建 `SKIPPED_EXISTING` run item。run 的 `fetched_count` 表示 `observedCount`，`planned_count` 表示本轮进入下载计划的数量。

本轮候选的 `ordinal` 按候选选择顺序重新编号为 `1..batch_limit`。因此同一作者批次仍保持最新到最旧；多个作者之间继续使用现有 ordinal 公平轮转。

如果新候选在 Java 规划阶段命中屏蔽规则，该项目记录为 `SKIPPED_BLOCKED`。本轮实际下载数允许少于批次上限；系统不为了补回这一名额在同一轮重新发起第二次抓取命令。该作品在后续轮次已属于已观察集合，不会再次占用配额。

## 6. SQLite 持久化冲突修复

### 6.1 当前失败边界

媒体下载完成后，`WorkIngestService` 调用 `WorkPersistenceService.persist(...)`。后者在一个 JPA 写事务中执行去重查询、媒体记录写入和作者归一化。即使进程内写事务通常经过 SQLite 单写协调，以下情况仍可能产生 `SQLITE_BUSY_SNAPSHOT`：

- 外部进程或未纳入协调的连接写入同一 SQLite 文件；
- WAL 读快照在提交写入前被其他连接推进；
- 部署或兼容路径没有经过同一个协调入口。

SQLite 快照冲突不能在原事务中重试；失败事务必须完整回滚，并由新事务重新执行。

### 6.2 新的重试边界

`WorkIngestService` 在媒体下载和校验完成后，通过通用接口调用持久化：

```java
PersistenceResult persistence = databaseWriteExecutor.execute(
        "work-persistence",
        () -> persistenceService.persist(downloadedMetadata));
```

要求：

1. `persistenceService.persist(...)` 仍是 Spring 代理的独立事务入口。
2. 每次 executor 尝试都必须重新进入该代理，创建新的事务和 EntityManager。
3. executor 的退避发生在事务外。
4. 媒体文件保持在 staging；数据库成功后才执行 `mediaDownloadService.commit(...)`。
5. 所有短事务重试耗尽后，才回滚 staging 并让下载 item 进入原有分钟级 `RETRY_WAIT`。
6. 不在 executor 内重新执行解析、HTTP、详情刷新或媒体下载。

这样瞬时 SQLite 冲突通常只重做短数据库事务，不浪费已经完成的媒体传输。

### 6.3 幂等性

每次持久化重试都重新执行现有去重检查。数据库事务要么完整提交，要么完整回滚。若底层驱动返回结果不明确，重试也必须通过平台和作品 ID 唯一语义识别已经提交的记录，返回 `created=false`，不得创建重复媒体。

## 7. PostgreSQL 迁移边界

业务服务不得依赖 `SqliteWriteRetrier`、`SqliteWriteCoordinator` 或 SQLite 异常类型，只依赖：

```java
public interface DatabaseWriteExecutor {
    <T> T execute(String operation, Supplier<T> newTransactionCall);
}
```

条件化实现保持以下职责：

### SQLite

- 使用 `SqliteSerializingJpaTransactionManager` 协调进程内非只读事务；
- executor 识别 `SQLITE_BUSY`、`SQLITE_BUSY_SNAPSHOT` 和协调器超时；
- 以有限次数、指数退避和 jitter 重新调用完整事务入口；
- SQLite 锁、PRAGMA 和错误识别只存在于 SQLite 包或 SQLite 条件 Bean 中。

### PostgreSQL

- 使用标准 `JpaTransactionManager`，不获取 JVM 单写锁；
- 当前 `DirectDatabaseWriteExecutor` 直接执行 action，不人为串行化写事务；
- 后续如需重试，只按 PostgreSQL SQLSTATE，例如 serialization failure `40001` 或 deadlock `40P01`，实现独立 executor；
- 后续可将领取 SQL 替换为 `FOR UPDATE SKIP LOCKED` 并增加 worker 数量，而不改变本设计的业务配额和持久化调用边界。

分页、候选选择、run item 状态和 `WorkIngestService` 不包含数据库种类分支。数据库迁移时只替换基础设施 Bean 和数据库方言实现。

## 8. 错误处理

| 场景 | 处理 |
| --- | --- |
| 抖音正常到达末页 | `NO_MORE`；本轮成功，选中实际未知数量。 |
| 达到本轮未知配额 | `BATCH_LIMIT`；本轮成功。 |
| 达到页数硬上限 | `MAX_PAGE_GUARD`；本轮保存已选候选并记录告警，不标记历史补全。 |
| 空页且 `has_more=1` | 保持现有连续空页保护。 |
| 普通候选已被其他 run 活动下载 | `SKIPPED_EXISTING_ACTIVE_DOWNLOAD`，不创建第二个活动下载。 |
| 最终失败作品再次出现在列表 | 因 ID 已知而跳过；由手动失败重试处理。 |
| SQLite 持久化瞬时冲突 | 当前媒体下载内以新事务短重试。 |
| SQLite 重试耗尽 | 下载 item 进入现有 `RETRY_WAIT/FAILED` 状态机。 |
| PostgreSQL 普通执行 | 直接并发事务，不经过 SQLite 锁或 SQLite 错误分类。 |

## 9. 可观测性

每次抓取 run 至少记录：

```text
mode
batchLimit
selectedCount
observedCount
knownCountAtStart
pagesFetched
lastCursor
outcome
effectiveMaxPages
```

SQLite 持久化重试日志记录：

```text
operation=work-persistence
attempt/maxAttempts
delayMs
root error code
```

不得记录 Cookie、完整原始响应或未经截断的作品元数据。

## 10. 测试设计

### 10.1 Python 分页器

- 首次配额 80 时按 4 页收集 80 个未知作品。
- 后续配额 20 时，5 个新发布作品加 15 个历史未知作品后停止。
- 已知前缀跨多页时继续扫描，直到找到 20 个未知作品。
- `FAILED`、`SKIPPED_BLOCKED` 等 ID 位于 known 文件时不进入新候选。
- 同页和跨页重复 ID 不重复消耗配额。
- 远端删除导致已知 ID 缺席时仍按实际未知数量选择。
- 到达末页不足配额时返回 `NO_MORE`。
- 到达硬上限时返回 `MAX_PAGE_GUARD`，不误报补全。

### 10.2 Java 规划与队列

- `omaxcur` 控制首次配额，`maxcur` 控制后续配额。
- 普通 run 只持久化候选和有界异常，不重复写入全部已知前缀。
- 候选 ordinal 重新编号并保持最新到最旧。
- 多轮运行最终覆盖所有模拟公开作品；中途新增作品优先占用下一轮配额。
- `FAILED` 不通过普通抓取重新入队，手动重试仍可恢复原 item。
- 活动下载跨 run 去重继续生效。

### 10.3 数据库持久化

- 第一次 `persist(...)` 抛出 `SQLITE_BUSY_SNAPSHOT`、第二次成功时，只执行一次媒体下载。
- 每次重试使用新的事务调用；失败事务的 EntityManager 不被复用。
- SQLite executor 重试耗尽后，下载 item 进入现有重试状态。
- direct/PostgreSQL executor 只调用一次 action，不 sleep、不获取 SQLite 协调锁。
- 重试后去重检查返回已存在记录时不创建重复视频或图文。

### 10.4 回归

- 抓取 worker 与下载 worker 仍可阶段并行。
- SQLite 写事务内没有 HTTP、文件下载、FFmpeg 或 backoff sleep。
- 审计模式继续能够修复本地缺失媒体。
- `like/fav/recommend` 兼容路径不受普通 `post` 批次算法影响。
- 现有暂停、恢复、stale claim recovery 和 HLS 队列行为不变。

## 11. 验收标准

1. 首次配额 80、后续配额 20 的作者按 Cron 逐批补全；新发布作品和历史作品合计每轮不超过 20。
2. 多轮模拟中，服务器仍公开且未被屏蔽的作品最终全部进入完成、活动、失败或明确跳过状态，不因连续已知边界永久漏掉历史作品。
3. 普通运行不会反复创建大批 `SKIPPED_EXISTING` run item。
4. `FAILED` 项不占普通批次配额，也不被普通抓取创建重复 item。
5. SQLite 瞬时快照冲突不会立即重新下载媒体；短事务重试成功后当前 item 直接完成。
6. PostgreSQL profile 不使用 SQLite 单写锁、PRAGMA、SQLite 异常文本或强制单 writer 的持久化实现。
7. 数据库种类切换不改变分页配额、下载状态机或业务服务 API。
