# 收藏任务分批补全与持久化重试实施计划

## 目标

按已确认规范实现以下行为：首次按 `omaxcur`、后续按 `maxcur` 从最新到最旧选择固定数量的本地未知作品；每轮只由原 Cron 触发；SQLite 瞬时持久化冲突在当前媒体下载内以新事务重试；PostgreSQL 路径保持标准并发事务。

## 成功标准

- 首次 80、后续 20 的模拟作者可以跨多轮补完历史，中途新增作品优先且每轮合计不超过 20。
- 普通抓取不会反复写入全部已知前缀，也不会重新排队 `FAILED`。
- `SQLITE_BUSY_SNAPSHOT` 第一次失败、第二次成功时媒体只下载一次。
- PostgreSQL/direct executor 不获取 SQLite 锁、不执行 SQLite 特定重试。
- 聚焦测试、完整 Maven 测试（除已记录的独立 fixture 问题）和 Python 测试通过。

## 任务 1：分页器改为未知作品批次配额

文件：

- 修改 `backstage/src/main/docker/buildx/script/douyin_incremental.py`
- 修改 `backstage/src/main/docker/buildx/script/douyin.py`
- 修改 `backstage/src/test/python/test_douyin_incremental.py`

步骤：

1. 先增加失败测试：增量模式跨多页跳过已知前缀，收集满 `batch_limit` 个未知 ID 后返回 `BATCH_LIMIT`。
2. 增加“5 个新发布 + 15 个历史未知”“不足配额到达末页”“重复 ID 不消耗配额”“触达页数保护”测试。
3. 将 `max_items` 明确为普通模式的未知候选配额；首次模式同样按未知候选计数。
4. 删除普通增量 `known_streak/last_seen_publish_time` 成功停止条件，保留水位用于诊断。
5. 保持异常响应、空页、Cookie/验证和 schema 分类不变。
6. 运行 Python 测试。

## 任务 2：Java 请求参数和动态页数预算

文件：

- 修改 `backstage/src/main/java/com/flower/spirit/service/CollectDataService.java`
- 修改 `backstage/src/main/java/com/flower/spirit/service/DouyinFetchRequest.java`（如需重命名字段）
- 修改 `backstage/src/main/java/com/flower/spirit/utils/CommandUtil.java`
- 修改 `backstage/src/main/resources/application-dev.properties`
- 修改 `backstage/src/main/resources/application-docker.properties`
- 修改对应 Java 单元测试

步骤：

1. 增加 `incremental-min-pages` 和 `backfill-max-pages` 配置。
2. 首次配额取正数 `omaxcur`，默认 80；后续配额取正数 `maxcur`，默认 80。
3. 按已知 ID 数量、批次配额和一页安全余量计算有效页数，并限制在硬上限内。
4. 将批次配额传给 Python 命令，不让普通增量传 0。
5. 对 `MAX_PAGE_GUARD` 保留告警语义，不误报历史补全。
6. 增加配置和请求构造测试。

## 任务 3：已观察集合与精简抓取计划

文件：

- 修改 `backstage/src/main/java/com/flower/spirit/service/CollectRunQueryService.java`
- 修改 `backstage/src/main/java/com/flower/spirit/service/CollectDataService.java`
- 修改 `backstage/src/main/java/com/flower/spirit/service/transaction/CollectQueueTransaction.java`
- 修改相关测试

步骤：

1. 先增加测试，证明 `FAILED`、`SKIPPED_BLOCKED`、`SKIPPED_EXISTING` 等历史 item ID 会进入 known 集合。
2. 普通模式只把 Python 选中的候选交给 `buildFetchPlan`，不把全部已知前缀持久化为新 run item。
3. 候选按选择顺序重新编号 ordinal。
4. 分开记录 `observedCount` 与候选 item 数；必要时扩展 fetch envelope 或 store API，不引入无界快照。
5. 保持 active download 跨 run 去重；屏蔽候选记录一次后进入已观察集合。
6. 保持审计模式现有的完整观察与 `AUDIT_REPAIR` 能力。
7. 增加多轮补全测试：首次 80、后续 20、中途新增 5，验证每轮候选和最终覆盖。

## 任务 4：数据库无关的作品持久化重试

文件：

- 修改 `backstage/src/main/java/com/flower/spirit/service/WorkIngestService.java`
- 修改 `backstage/src/test/java/com/flower/spirit/service/WorkIngestServiceTest.java`
- 视测试需要修改 `backstage/src/test/java/com/flower/spirit/service/CollectDownloadServiceTest.java`
- 视覆盖需要增加 SQLite 集成测试

步骤：

1. 注入通用 `DatabaseWriteExecutor`。
2. 仅用 executor 包裹 `persistenceService.persist(downloadedMetadata)`；解析、详情刷新、媒体下载、文件校验和 post-processing 保持在事务/退避之外。
3. 确保 supplier 每次重新调用 Spring 事务代理，失败的 EntityManager 不复用。
4. 成功持久化后才 commit staging；所有短重试耗尽后才 rollback staging 并向上抛出。
5. 增加测试：第一次 SQLite busy、第二次成功，下载调用一次、persist 两次、commit 一次、rollback 零次。
6. 增加 direct executor 测试：只执行一次，不引入 SQLite 逻辑。
7. 验证现有调用 `new WorkIngestService(...)` 的测试构造器并逐一更新。

## 任务 5：SQLite 和 PostgreSQL 边界回归

文件：

- 修改或增加 `backstage/src/test/java/com/flower/spirit/database/sqlite/*Test.java`
- 修改或增加 `backstage/src/test/java/com/flower/spirit/database/postgresql/*Test.java`
- 修改 `backstage/src/test/java/com/flower/spirit/service/CollectPipelineIntegrationTest.java`

步骤：

1. 用临时 SQLite WAL 数据库制造一次事务冲突，验证新事务重试后成功。
2. 验证 SQLite 写锁等待和 busy 重试指标仍正确。
3. 验证 PostgreSQL 条件下使用标准 transaction manager 和 direct executor，不加载 `SqliteWriteCoordinator`。
4. 保持下载 claim、过期锁恢复、失败退避和旧 generation 隔离测试通过。

## 任务 6：验证与交付检查

命令：

```bash
python -m unittest discover -s backstage/src/test/python -p "test_douyin_incremental.py" -v
mvn -f backstage/pom.xml -Dtest=WorkIngestServiceTest,CollectDownloadServiceTest,CollectPipelineIntegrationTest test
mvn -f backstage/pom.xml test
git diff --check
```

检查：

1. 搜索普通增量是否仍以 `known_streak` 作为停止条件。
2. 搜索业务包是否新增对 `SqliteWriteRetrier`、SQLite JDBC 异常或 PRAGMA 的依赖。
3. 搜索写事务内是否包含下载、HTTP、FFmpeg 或 sleep。
4. 检查配置文案与 `maxcur/omaxcur` 的实际语义一致。
5. 检查工作树只包含本任务文件。
