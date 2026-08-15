# 抖音旧收藏队列快照恢复实施计划

## 目标

修复升级前收藏下载项没有 `metadata_snapshot` 时持续调用单作品详情 F2、反复续上全局冷却并阻塞作者列表抓取的问题。完成后，旧项通过现有分页 backfill 在有限轮内获得列表快照并下载；只有完整验证遍历后仍未出现的项才进入远端不存在终态。

## 成功标准

- 单作品 HTTP 200 空响应只启动详情接口软退避，不阻止作者列表抓取。
- 明确的认证、验证码、401/403/429 和限流仍启动全局强冷却。
- 有列表快照的收藏项在详情软退避期间仍可下载。
- 空快照收藏项不调用详情 F2、不消耗下载尝试次数，并自动请求去重的列表刷新。
- 作者列表再次发现作品时回填原活动下载项，不重复下载。
- `RUNNING` 项并发回填不丢锁，当前失败后自动使用新快照重排。
- 完整验证遍历后才将剩余等待项标记为 `SKIPPED_REMOTE_MISSING`。
- 本地作者、作品详情、历史记录和媒体文件不被删除。

## 约束

- 不新增数据库列或第三方依赖。
- 不修改单链接下载的详情 F2 策略。
- 不改变现有每轮数量、最大页数、空页保护、请求间隔及任务去重。
- 不提交 `.tools/`、`logs/` 或 Python 缓存目录。

## 任务 1：用测试锁定冷却作用域

涉及文件：

- `backstage/src/test/java/com/flower/spirit/service/PlatformCookieServiceTest.java`
- `backstage/src/test/java/com/flower/spirit/platform/adapter/DouyinPlatformAdapterTest.java`
- `backstage/src/test/java/com/flower/spirit/service/CollectJobWorkerTest.java`
- `backstage/src/test/java/com/flower/spirit/service/CollectDownloadWorkerTest.java`

步骤：

1. 增加详情软退避与全局强冷却相互独立的测试。
2. 验证详情软退避期间仍能选择作者列表和媒体下载所需 Cookie。
3. 验证详情适配器在详情软退避期间不调用 gateway。
4. 验证作者列表 worker 和收藏下载 worker 只因全局强冷却暂停。
5. 先运行上述测试并确认新断言在实现前失败。

## 任务 2：拆分全局强冷却与详情软退避

涉及文件：

- `backstage/src/main/java/com/flower/spirit/service/PlatformCookieService.java`
- `backstage/src/main/java/com/flower/spirit/platform/adapter/DouyinPlatformAdapter.java`
- `backstage/src/main/java/com/flower/spirit/service/CollectDataService.java`
- `backstage/src/main/java/com/flower/spirit/service/CollectJobWorker.java`
- `backstage/src/main/java/com/flower/spirit/service/CollectDownloadWorker.java`
- `backstage/src/main/java/com/flower/spirit/service/DouyinCookieHealthService.java`

步骤：

1. 为强冷却和详情软退避提供命名明确的查询、剩余时间和 retry-at 方法。
2. Cookie 选择只受强冷却影响。
3. 详情适配器同时检查强冷却与详情软退避；作者列表与快照媒体下载只检查强冷却。
4. 保持现有单容器 F2 串行协调器。
5. 日志增加 `scope=GLOBAL_RISK` 或 `scope=DETAIL_API`。
6. 保持健康状态 API 向后兼容，并增加详情软退避的独立字段。
7. 运行任务 1 的测试直至通过。

## 任务 3：用事务测试锁定旧队列恢复

涉及文件：

- `backstage/src/test/java/com/flower/spirit/service/transaction/CollectDownloadTransactionTest.java`
- `backstage/src/test/java/com/flower/spirit/service/transaction/CollectQueueTransactionTest.java`
- `backstage/src/test/java/com/flower/spirit/service/CollectPipelineIntegrationTest.java`

测试场景：

1. claim 优先选择非空快照项。
2. 空快照 claim 转为 `LIST_SNAPSHOT_PENDING`，撤销本次尝试计数。
3. 同收藏任务的其他非运行空快照活动项被合并标记为等待。
4. 等待状态启动从游标 0 开始的验证周期，但不越过任务停用状态。
5. 新抓取快照回填旧 `QUEUED`/`RETRY_WAIT` 项，清理旧错误、恢复重试预算并立即可 claim。
6. 回填 `RUNNING` 项时不修改锁；失败事务检测到新快照后重新排队。
7. 已有快照不被覆盖。
8. 完整验证遍历前不得终结未出现项。
9. 完整验证遍历或作者账号终态后，剩余等待项进入 `SKIPPED_REMOTE_MISSING`。
10. 列表明确屏蔽的作品进入 `SKIPPED_BLOCKED`。

## 任务 4：实现空快照等待与刷新请求

涉及文件：

- `backstage/src/main/java/com/flower/spirit/service/CollectDownloadService.java`
- `backstage/src/main/java/com/flower/spirit/service/transaction/CollectDownloadTransaction.java`
- `backstage/src/main/java/com/flower/spirit/service/CollectEnqueueService.java`
- 需要时增加一个小型结果 record，放在 `service` 包中

步骤：

1. 在调用 `WorkIngestService` 前识别收藏队列空快照项。
2. 事务性撤销 claim 尝试、标记同任务等待项、重置 backfill 验证游标并返回是否需要刷新。
3. 事务结束后通过现有 job 去重键创建或复用刷新 job。
4. 任务停用或全局暂停时仅保留等待状态，不强行启动抓取。
5. 等待属于预期状态，只输出结构化 WARN/INFO，不打印异常堆栈。
6. 下载 claim 排序优先合法快照项。

## 任务 5：实现列表快照回填与竞态恢复

涉及文件：

- `backstage/src/main/java/com/flower/spirit/service/transaction/CollectQueueTransaction.java`
- `backstage/src/main/java/com/flower/spirit/service/transaction/CollectDownloadTransaction.java`

步骤：

1. 保存抓取计划前查找相同平台与作品的活动下载项。
2. 非空快照只写入当前为空的活动项。
3. 恢复非运行活动项的队列状态、available-at、错误字段及因旧根因消耗的重试预算。
4. 对 `RUNNING` 项只附加快照；失败处理时若发现数据库已有快照，则无损重排。
5. 当前抓取运行记录 `EXISTING_ACTIVE_DOWNLOAD`/快照回填审计，不产生第二个 claimable 项。
6. 对列表明确屏蔽项终结匹配的等待队列。
7. 记录 `LIST_SNAPSHOT_HYDRATED` 事件与数量。

## 任务 6：实现完整遍历终态

涉及文件：

- `backstage/src/main/java/com/flower/spirit/service/transaction/CollectQueueTransaction.java`
- `backstage/src/test/java/com/flower/spirit/service/transaction/CollectQueueTransactionTest.java`

步骤：

1. 只使用 `CollectBackfillProgress.complete()` 的现有两次干净验证结果作为完整遍历依据。
2. 完整验证完成后，终结同任务中仍为 `LIST_SNAPSHOT_PENDING` 且快照为空的活动项。
3. 作者被删除、封禁或作品列表永久不可用时，也终结对应等待项，避免永久悬挂。
4. 写入 `SKIPPED_REMOTE_MISSING`、可读原因和 finished-at，清理锁及 available-at。
5. 不修改收藏详情、本地媒体或作者元数据表。

## 任务 7：定向回归与边界修正

先运行：

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'
& 'F:\opencode\Project\streamV\.tmp\maven\apache-maven-3.9.9\bin\mvn.cmd' `
  '-Dtest=PlatformCookieServiceTest,DouyinPlatformAdapterTest,CollectJobWorkerTest,CollectDownloadWorkerTest,CollectDownloadServiceTest,CollectDownloadTransactionTest,CollectQueueTransactionTest,CollectPipelineIntegrationTest' test
```

然后运行 Python F2 测试：

```powershell
python -m unittest backstage/src/test/python/test_douyin_incremental.py
```

仅修正与本设计直接相关的失败，不顺带重构或格式化无关代码。

## 任务 8：全量验证

运行：

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'
& 'F:\opencode\Project\streamV\.tmp\maven\apache-maven-3.9.9\bin\mvn.cmd' test
& 'F:\opencode\Project\streamV\.tmp\maven\apache-maven-3.9.9\bin\mvn.cmd' -DskipTests compile
git diff --check
```

额外检查：

- 搜索新增快照字段中是否包含 Cookie、X-Bogus、msToken 或完整签名 URL。
- 确认无 `.tools/`、`logs/`、`__pycache__/` 或生成产物进入暂存区。
- 查看最终 diff，确认没有无关格式化和编码变化。

## 提交策略

1. 设计文档提交已存在：`docs: design legacy Douyin snapshot recovery`。
2. 实施计划单独提交：`docs: plan legacy Douyin snapshot recovery`。
3. 代码与测试在全部验证通过后提交：`fix: recover legacy Douyin download snapshots`。
4. 未经用户明确要求，不自动 push、创建 PR 或合并。
