# 进程可靠性、SQLite 完整性与 PostgreSQL 迁移设计

日期：2026-07-30  
状态：设计已确认，待实施计划

## 1. 背景

当前生产代码已经具备持久化收藏队列、SQLite 单写协调、Douyin 增量抓取、全局风控冷却和数据库维护能力，但审查与 Docker 日志确认了以下问题：

1. 收藏、点赞和推荐的 legacy F2 路径没有进程超时，可能永久占用唯一抓取 worker。
2. yt-dlp 元数据和下载路径没有超时或可靠的子进程树回收，可能永久占用唯一下载 worker。
3. HLS、MP4 faststart 和 Bilibili ffmpeg 路径没有统一超时与回收策略。
4. 定时 worker 可以在 `ApplicationReadyEvent` 初始化器完成前领取任务，持久化暂停状态可能在启动窗口内被绕过。
5. PostgreSQL 条件实现已经存在，但运行 SQL、schema initializer、驱动和 profile 尚未形成可启动的 PostgreSQL 路径。
6. 并发入队、run event 序号和收藏明细唯一性仍依赖 SQLite 单写语义。
7. 同步到开发项目的 SQLite 副本在 `biz_collect_run` 上报告 B-tree 行号乱序和多个索引条目数错误。该结果必须在生产原库的一致性快照上复核，不能直接对生产库执行自动修复。

本设计把即时可靠性修复、SQLite 数据保护、PostgreSQL 运行基础设施和正式迁移拆成可独立提交、验证和回滚的阶段。

## 2. 已确认约束

- 初次 PostgreSQL 部署为一个应用实例加一个独立 PostgreSQL 容器。
- 不引入 Redis，不支持多个应用容器，不长期双写 SQLite 与 PostgreSQL。
- 正式切换允许 30 至 120 分钟或更长的维护停机窗口。
- PostgreSQL 首版保持与现有 JPA 实体和业务表兼容的 schema，不在首次迁移时规范化媒体宽表。
- 媒体表规范化、JSONB 改造和多应用实例属于 PostgreSQL 稳定后的独立工程。
- 数据清理必须先报告、后确认、再执行；应用启动不得静默删除或重建生产数据。
- Douyin 实际上游风控失败继续消耗一次尝试并触发进程级全局冷却；冷却期间未发送请求的任务只延后且不消耗尝试。

本设计覆盖 `2026-07-28-sqlite-media-identity-postgresql-migration-design.md` 中“首次 PostgreSQL 迁移同时规范化媒体表”的安排。其 SQLite identity、审计、预览清理和冷备份原则继续有效。Douyin 冷却以 `2026-07-29-douyin-global-cooldown-design.md` 为准。

## 3. 非目标

- 不直接修改或迁移当前生产数据库文件。
- 不在应用启动时自动执行 `REINDEX`、表重建、`VACUUM` 或数据删除。
- 不用增加 worker 数量掩盖阻塞进程问题。
- 不用 PostgreSQL 迁移替代当前 SQLite 正确性修复。
- 不在本阶段修改收藏分页、每轮下载数量、最新优先顺序或全局风控业务语义。
- 不在首次 PostgreSQL 切换时启用多应用实例或 Redis 通知。

## 4. 提交与发布单元

| 提交 | 内容 | 可独立发布 | 数据变更 |
| --- | --- | --- | --- |
| C0 | 本设计文档 | 是 | 无 |
| C1 | 受控进程执行器与 application-ready 门禁 | 是 | 无 |
| C2 | SQLite 完整性状态、离线诊断和一致性备份工具 | 是 | 无自动修改 |
| C3 | SQL 可移植性、并发幂等与数据库专属装配 | 是 | 可向后兼容列/索引迁移 |
| C4 | PostgreSQL 驱动、Flyway、profile、Docker 和契约测试 | 是，生产仍默认 SQLite | 只创建 PostgreSQL schema |
| C5 | SQLite 到 PostgreSQL 独立迁移器与运行手册 | 是 | 只在显式命令下迁移 |

每个提交必须有独立测试和回滚说明。C1 至 C3 可以先部署到现有 SQLite 生产环境；C4 和 C5 合并不会自动切换生产数据库。

## 5. C1：受控外部进程

### 5.1 统一执行边界

新增一个小型受控进程执行器，替换生产路径中的重复 `ProcessBuilder`、无限 `readLine` 和无限 `waitFor`。调用方提供命令、超时、输出策略和操作名称；执行器负责：

- 同时读取 stdout 和 stderr，避免管道写满造成死锁。
- 对诊断输出设置上限，媒体输出仍由子进程直接写入目标文件。
- 正常退出时返回 exit code 和有限输出。
- 超时时先调用 `destroy()`，等待 2 秒，再终止所有仍存活的后代进程并 `destroyForcibly()`。
- Java 线程中断时恢复中断标记，并执行相同的进程树回收。
- 回收后有界等待 reader 线程结束，不允许后台 reader 永久存活。
- 日志只输出脱敏命令摘要，不输出 Cookie、token、请求头或完整上游 payload。

### 5.2 默认超时

| 操作 | 默认超时 | 配置边界 |
| --- | --- | --- |
| legacy F2 列表、详情和健康检查 | 15 分钟 | 1 至 60 分钟 |
| yt-dlp 元数据 | 2 分钟 | 30 秒至 30 分钟 |
| yt-dlp 媒体下载 | 2 小时 | 5 分钟至 12 小时 |
| ffmpeg merge/concat/faststart | 2 小时 | 5 分钟至 12 小时 |
| ffmpeg HLS | 6 小时 | 30 分钟至 24 小时 |

现有 Douyin 增量 F2 的动态分页超时和结构化错误协议保持不变，但底层回收逻辑复用同一执行边界。

### 5.3 失败语义

- F2 超时：`F2_PROCESS_TIMEOUT`，进入现有抓取重试。
- yt-dlp 超时：`YTDLP_TIMEOUT`，释放下载 claim 并进入现有下载重试。
- ffmpeg 超时：`FFMPEG_TIMEOUT`，删除 staging 输出；HLS 保留源媒体并按现有队列失败策略处理。
- 无法终止的子进程记为 ERROR，并包含 PID 和操作名，不包含敏感参数。

## 6. C1：应用启动门禁

新增进程内 `ApplicationReadinessGate`。它在 Spring 最低优先级的 `ApplicationReadyEvent` 监听器完成后才结束 `STARTING`；SQLite 随后进入 `CHECKING_DATABASE`，完整性检查通过后才进入 `READY`，PostgreSQL 在 Flyway、Hibernate validate 和运行控制加载成功后进入 `READY`。门禁包含状态和失败原因，不只是一个布尔值：

- `STARTING`：Spring 或 schema 初始化尚未完成。
- `CHECKING_DATABASE`：SQLite 完整性检查进行中。
- `READY`：后台任务可以领取。
- `BLOCKED`：初始化或完整性检查失败，后台任务保持禁止。

`TaskService` 的抓取、下载、作者补全、历史维护和 HLS 调度在入口统一检查门禁。worker 自身在 claim 前再次检查，防止手工 wake-up 绕过调度入口。运行控制服务在完成数据库加载前按 fail-closed 处理。任何 ready 监听器都不得直接把 SQLite 状态越过 `CHECKING_DATABASE` 置为 `READY`。

Web 管理页面可以在 `STARTING`、`CHECKING_DATABASE` 和 `BLOCKED` 状态访问数据库状态接口，以便显示原因；任何后台写任务都不得因此提前启动。

## 7. C2：SQLite 完整性和离线恢复

### 7.1 启动检查

SQLite profile 在应用 ready 后异步执行 `PRAGMA quick_check(1)`。检查完成前门禁保持 `CHECKING_DATABASE`。结果为 `ok` 才进入 `READY`；任何错误或执行异常都进入 `BLOCKED`。

检查状态记录开始时间、结束时间、结果摘要和数据库文件基本信息，但不记录数据内容。管理员可以显式重新执行检查。PostgreSQL profile 不装配该 SQLite checker。

### 7.2 离线工具

提供独立命令，而不是管理页面一键修复：

- `audit`：只读运行 quick check、目标表 integrity check、行数和索引清单。
- `snapshot`：要求应用已停止，确认 WAL/SHM 状态后生成一致性副本和 SHA-256。
- `reindex-copy`：只对副本执行 `REINDEX`，随后完整复检，不覆盖源文件。
- `verify-copy`：比较源与候选副本的关键行数、业务键和摘要。

若 integrity check 只报告索引缺失或条目数错误，可以在副本上尝试 `REINDEX`。若报告 table B-tree 行号乱序、缺页、重复 rowid 或无法读取的页面，工具必须停止并要求从可靠备份恢复，或通过受校验的逐表导出重建；不得宣称单纯 `REINDEX` 已修复。

### 7.3 生产数据保护

正式修复或迁移前停止容器，并保存数据库、WAL、SHM 和 hash。原始副本只读保留。任何候选修复文件必须通过 integrity check、关键表行数、业务键和抽样 hash 后才能参与迁移。

## 8. C3：数据库可移植性

### 8.1 通用 SQL

- `INSERT OR IGNORE` 改为 SQLite 3.41 和 PostgreSQL 都支持的 `ON CONFLICT DO NOTHING`。
- SQLite 标量 `MIN(priority, ?)` 改为标准 `CASE` 表达式。
- schema 检查通过 JDBC `DatabaseMetaData` 获取表和列，不使用 `PRAGMA table_info`。
- 通用列定义使用 `TEXT`、`TIMESTAMP`、`INTEGER` 和 `VARCHAR`，不使用 `CLOB` 或 `datetime`。
- retention 查询使用绑定的 Java `Timestamp` 截止值，不拼接 SQLite `datetime()`。

### 8.2 数据库专属组件

只在存在真实差异时使用小接口或条件 bean：

- SQLite：单写协调、busy 重试、PRAGMA、文件空间和完整性检查、checkpoint。
- PostgreSQL：SQLSTATE 重试、catalog/空间统计、`SKIP LOCKED` 领取。
- 公共业务服务不依赖 `Sqlite*` 类型，也不判断 SQLite 错误文本。

SQLite 历史 schema initializer 只在 SQLite profile 装配。PostgreSQL schema 全部由 Flyway 管理。

## 9. C3：队列和数据幂等

### 9.1 入队

收藏 run 和 job 创建使用冲突安全插入。并发手工与定时请求命中同一 active task 时，失败方等待冲突事务完成并查询已有 run/job，返回 `inserted=false`，不把唯一键异常暴露给 API。

### 9.2 领取

SQLite 保持单写事务和条件更新。PostgreSQL 使用单事务 `FOR UPDATE SKIP LOCKED` 选择并更新不同 job/item。状态、attempt、lock token 和 available time 继续作为最终正确性条件。

### 9.3 run event 序号

在 run 行上维护下一个 event sequence。分配序号时锁定或原子更新所属 run，使同一 run 的多个事件写入不会再竞争 `MAX(sequence)+1`。迁移时根据已有最大 sequence 初始化下一值。

### 9.4 收藏明细唯一性

为 `(dataid, videoid)` 建立唯一约束前先生成重复组报告。每组保留最早有效主记录，按明确字段规则合并最新完成状态、错误信息和时间；有冲突的媒体类型或来源地址只报告并阻止约束创建。清理只能由显式 maintenance apply 执行。

## 10. C4：PostgreSQL 运行环境

引入 PostgreSQL JDBC、Flyway core、Flyway PostgreSQL 扩展和 Testcontainers 测试依赖。新增：

- `application-postgresql.properties`，连接信息只从环境变量读取。
- PostgreSQL Docker Compose 示例，包含持久 volume、healthcheck 和 secret 环境变量。
- `db/migration/postgresql` Flyway 迁移。

PostgreSQL 使用 `spring.jpa.hibernate.ddl-auto=validate`。V001 创建与现有实体兼容的业务表、队列表和必要索引，ID 类型先保持当前 Java 映射可安全承载的范围。大文本使用 `TEXT`，时间使用与实体映射兼容的 timestamp 类型。首次迁移不拆分 `biz_video`、`biz_graphic_content` 或 raw payload 表。

初次切换保持一个抓取 worker 和一个下载 worker。`SKIP LOCKED` 的并发正确性由测试覆盖，但生产 worker 数只在 PostgreSQL 稳定观察后独立调整。

## 11. C5：迁移器

迁移器是显式运行的独立命令，支持：

- `audit`：验证 SQLite 源、重复数据和必要 schema。
- `dry-run`：迁移到临时 PostgreSQL schema，生成报告后删除临时 schema。
- `load`：从停机后最终 SQLite 快照分批导入正式 PostgreSQL。
- `verify`：只读比较两端，不写入。

迁移器按主键稳定分页，保留兼容表的原 ID，并在导入后把 PostgreSQL sequence 调整到 `MAX(id)+1`。未知枚举、无法读取页面、重复业务键、孤儿引用或字段转换失败都会停止阶段并写入有限报告，不能跳过。

最终校验至少包括：

- 每张表源/目标行数及允许的去重差异。
- 作品、作者、收藏任务和队列业务键集合。
- 未完成 run/job/item 的状态、attempt 和 available time。
- 外键和孤儿计数。
- 固定样本与随机样本字段摘要。
- raw payload 长度和 SHA-256，不在报告中输出正文。
- identity/sequence 下一值。

## 12. 正式切换和回滚

### 12.1 时间点

以 C5 合并到 main 的时间为 `T0`：

- `T0+1 天`：部署 C1-C3，继续使用 SQLite，观察完整调度周期。
- `T0+2 天`：停止应用，生成并验证一致性 SQLite 快照。
- `T0+3 天`：执行第一次 PostgreSQL dry-run 和完整对账。
- `T0+4 天`：修正差异，执行第二次迁移与恢复演练。
- `T0+5 天` 之后：只有全部门槛通过才安排 30 至 120 分钟或更长的正式维护窗口。

若 C5 在 2026-07-31 前合并，最早正式切换日期为 2026-08-05。任何失败都会顺延，不压缩验证时间。

### 12.2 切换步骤

1. 在前端暂停全部后台任务并记录状态。
2. 等待当前事务和外部进程结束，停止应用容器。
3. 保存 SQLite、WAL、SHM 和 hash，生成最终一致性快照。
4. 创建空 PostgreSQL 数据库并运行 Flyway。
5. 执行最终 `load` 和 `verify`。
6. 使用 PostgreSQL profile 启动单实例，保持后台任务暂停。
7. 验证登录、首页、作者、Feed、播放、收藏任务、运行详情、数据库审计和小规模手工任务。
8. 恢复调度并观察错误率、锁等待、连接池和队列积压。
9. SQLite 原始快照只读保留至少 30 天。

### 12.3 回滚

若最终校验失败，PostgreSQL 应用不得启动。若切换后出现核心功能回归：立即暂停任务、停止 PostgreSQL 应用、保存 PostgreSQL 变更审计并恢复切换前 SQLite 快照。初次切换不自动反向同步，切换期间新增数据生成业务键补录报告。

## 13. 错误与日志级别

- 实际 Douyin 风控：失败 run、结构化错误码和一次错误日志；若仍可重试则 worker 摘要为 WARN。
- 全局冷却延后：WARN，无堆栈，不消耗尝试。
- 外部进程超时：结构化错误码和有限输出；无法清理子进程为 ERROR。
- SQLite integrity 失败：ERROR，门禁进入 `BLOCKED`。
- PostgreSQL 只重试 SQLSTATE `40001` 和 `40P01`；其他 SQLSTATE 原样失败并记录。
- 日志和迁移报告不得包含 Cookie、token、完整 raw payload 或请求头。

## 14. 验证门槛

### C1

- F2、yt-dlp 和 ffmpeg 正常退出、非零退出、超时、中断、输出上限和进程树清理测试。
- 初始化完成前所有 worker 均不能 claim。
- 现有 Douyin 增量错误协议和全局冷却测试保持通过。

### C2

- 正常 SQLite、索引错误和 table B-tree 错误的门禁状态测试。
- 离线工具绝不覆盖源文件，失败候选不会被标记为可用。

### C3

- SQLite 全套回归通过。
- 并发重复入队返回同一 active run。
- event sequence 并发唯一且连续。
- 明细重复报告和冲突阻断测试。

### C4

- Testcontainers PostgreSQL 从空库 Flyway 成功，Hibernate validate 通过。
- runtime control、作者补全、收藏 run/job/item、审计和保留策略契约通过。
- 并发 worker 使用 `SKIP LOCKED` 不重复领取。
- SQLite 与 PostgreSQL profile 不交叉装配专属组件。

### C5

- dry-run、重复执行、故障中断、sequence 校准和校验失败测试。
- Java 和 Python 全套测试通过。
- 两次生产快照迁移演练报告无未分类差异。

## 15. 完成标准

- 任一生产外部进程都不能无限阻塞 worker。
- 后台任务在应用与数据库 ready 前不能领取。
- SQLite 完整性失败时应用 fail-closed，且存在不覆盖原库的离线诊断路径。
- PostgreSQL profile 能从空库启动并通过真实数据库契约测试。
- 迁移器可以从一致性 SQLite 快照生成零未分类差异的 PostgreSQL 数据库。
- 正式切换有可执行时间点、校验门槛和已演练回滚路径。
