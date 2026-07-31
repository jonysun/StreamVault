# 进程、数据库与 PostgreSQL 一次迁移实施计划

## 目标

按已确认设计分五个可验证阶段修复外部进程阻塞、启动竞态、SQLite 完整性保护、跨库 SQL 和队列并发，并把停机后一致性 SQLite 快照一次迁移到同一 Docker Compose 内的 PostgreSQL。同步到开发目录的 `db/spirit.db` 始终只读。

## 成功标准

- F2、yt-dlp 和 ffmpeg 均有超时、有限输出和进程树回收，worker 不会永久阻塞。
- 应用、schema、运行控制和数据库检查全部完成前，任何 worker 都不能领取任务。
- SQLite 完整性失败时后台任务 fail-closed；离线工具不覆盖源库。
- SQLite 与 PostgreSQL 均通过队列幂等、事件序号、运行控制和收藏明细契约测试。
- PostgreSQL 从空库执行 Flyway 并通过 Hibernate validate；生产 Compose 内部网络和持久 volume 可用。
- 迁移器对同步副本完成 audit、dry-run、load、verify，活跃状态和业务键零未分类差异。
- 正式瘦身只清理目标库中 `jsonData == videoinfo` 的精确冗余，不修改源 SQLite。

## 任务 1：受控进程与启动门禁

文件：

- 新增 `backstage/src/main/java/com/flower/spirit/process/*`
- 修改 `CommandUtil`、`YtDlpUtil`、`HlsTranscodeService`、`Mp4FaststartService` 及相关 ffmpeg 调用
- 新增 `ApplicationReadinessGate`，修改 `TaskService`、收藏/下载/作者补全/HLS worker
- 修改 Docker/dev 配置和对应测试

步骤：

1. 先增加受控进程正常、非零、超时、中断、输出上限和后代进程清理测试。
2. 用一个小型执行器统一 stdout/stderr 回收、退出结果和超时终止语义。
3. 迁移生产 F2、yt-dlp、ffmpeg 路径，不改业务命令构造和成功判断。
4. 增加 `STARTING/CHECKING_DATABASE/READY/BLOCKED` 门禁；调度入口与 worker claim 双重检查。
5. 验证现有 Douyin 结构化错误和全局冷却测试保持通过。

## 任务 2：SQLite 完整性与离线工具

文件：

- 新增 `database/integrity` 与独立 CLI 入口
- 修改 `SqliteRuntimeVerifier`、管理状态接口和 readiness gate
- 新增临时 SQLite 集成测试和只读脚本测试

步骤：

1. 启动时异步执行 `quick_check(1)`，通过后才 `READY`，失败进入 `BLOCKED`。
2. 实现 `audit`、`snapshot`、`reindex-copy`、`verify-copy`，所有写操作只接受不同于源路径的候选文件。
3. snapshot 保存 WAL/SHM 存在性、SHA-256、schema 指纹和关键表基线。
4. 区分索引错误与 table B-tree 错误，后者禁止自动 `REINDEX` 宣称修复。
5. 对当前同步副本仅运行只读 audit。

## 任务 3：可移植 SQL 与队列正确性

文件：

- 修改 schema initializer、`RuntimeControlTransaction`、`AuthorEnrichmentTransaction`、收藏队列事务
- 增加数据库方言小边界、唯一性预检和并发测试
- 修改 application 配置

步骤：

1. 移除 `INSERT OR IGNORE`、标量 `MIN`、`PRAGMA table_info` 和 SQLite `datetime()` 等公共业务 SQL。
2. SQLite initializer 只在 SQLite 装配；公共 schema 检测使用 `DatabaseMetaData`。
3. 用冲突安全插入返回已有 active run/job，避免并发唯一键异常泄漏。
4. run 行维护下一 event sequence；收藏明细唯一约束先审计、冲突即阻断。
5. 显式关闭 open-in-view，修正资源目录告警。

## 任务 4：PostgreSQL 运行路径

文件：

- 修改 `backstage/pom.xml`
- 新增 `application-postgresql.properties` 和 `db/migration/postgresql/*.sql`
- 修改数据库专属 transaction/claim 实现
- 更新生产 Docker Compose 示例和 Testcontainers 测试

步骤：

1. 加入 PostgreSQL JDBC、Flyway 和测试依赖；PostgreSQL 使用 `ddl-auto=validate`。
2. V001 完整创建与当前实体兼容的表、约束和索引。
3. PostgreSQL claim 使用 `FOR UPDATE SKIP LOCKED`；只重试 SQLSTATE `40001/40P01`。
4. 同一 Compose 增加 `postgres:16-alpine`、内部网络、named volume 和 healthcheck，不发布数据库端口。
5. 从空库验证 Flyway、Hibernate、运行控制、收藏队列、作者补全和并发 claim。

## 任务 5：迁移、瘦身与生产手册

文件：

- 新增显式迁移 CLI/one-shot Compose profile
- 新增迁移报告模型、校验测试和生产 runbook

步骤：

1. 实现 `audit/dry-run/load/verify`，按主键分页、保留 ID、批量写入并校准 sequence。
2. 对所有表比较行数，对作品/作者/队列比较业务键，对活跃记录比较状态、attempt 和 available time。
3. 精确相等的 `biz_video.jsonData/videoinfo` 只在目标 PostgreSQL 置空冗余列；其他行零改写。
4. 对当前同步副本执行完整 dry-run；再用两次生产停机快照演练。
5. 固化 120 分钟正式切换、smoke、观察和 SQLite 回滚步骤。

## 验证与提交

每个任务独立提交。阶段内先运行聚焦测试，再运行：

```text
mvn -f backstage/pom.xml test
python -m unittest discover -s backstage/src/test/python -p "test_*.py" -v
docker compose config
git diff --check
```

PostgreSQL/Testcontainers 在 Docker 可用时必须实际运行；若本机 Docker 不可用，不能用 mock 结果替代，必须在 CI 或生产演练前补跑。任何数据库校验失败都停止后续阶段，不压缩验证或直接切换。
