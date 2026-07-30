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
7. 较早同步到开发项目的 SQLite 副本曾在 `biz_collect_run` 上报告 B-tree 行号乱序和多个索引条目数错误；2026-07-30 13:01 再次同步的 2.38 GB 副本已没有 WAL/SHM，完整 `PRAGMA integrity_check` 返回 `ok`。前一次结果按复制不一致处理，但正式迁移仍必须在生产停机后重新复核，不能用开发副本的结果替代生产校验。

本设计把即时可靠性修复、SQLite 数据保护、PostgreSQL 运行基础设施和正式迁移拆成可独立提交、验证和回滚的阶段。

## 2. 已确认约束

- 初次 PostgreSQL 部署为一个应用实例加一个独立 PostgreSQL 容器。
- 不引入 Redis，不支持多个应用容器，不长期双写 SQLite 与 PostgreSQL。
- 正式切换允许 30 至 120 分钟或更长的维护停机窗口。
- PostgreSQL 首版保持与现有 JPA 实体和业务表兼容的 schema，不在首次迁移时规范化媒体宽表。
- 媒体表规范化、JSONB 改造和多应用实例属于 PostgreSQL 稳定后的独立工程。
- 数据清理必须先报告、后确认、再执行；应用启动不得静默删除或重建生产数据。
- Douyin 实际上游风控失败继续消耗当前 run 的一次尝试并触发进程级全局冷却；冷却期间未发送请求的任务只延后且不消耗尝试。已启用的作者任务在 run 达到上限后仍按现有调度创建后续 retry run，因此不会因单个 run 失败永久停止增量抓取。

本设计覆盖 `2026-07-28-sqlite-media-identity-postgresql-migration-design.md` 中“首次 PostgreSQL 迁移同时规范化媒体表”的安排。其 SQLite identity、审计、预览清理和冷备份原则继续有效。Douyin 冷却以 `2026-07-29-douyin-global-cooldown-design.md` 为准。

### 2.1 开发副本与生产端操作边界

| 操作 | 开发目录同步副本 | 生产端停机快照 | 生产原库 |
| --- | --- | --- | --- |
| 日志、schema、行数、重复、孤儿、状态只读审计 | 执行 | 执行 | 只在维护窗口只读执行 |
| 迁移器开发、dry-run、性能基线、恢复演练 | 执行 | 最终演练必须使用 | 不执行 |
| `REINDEX`、重建、去重、冗余字段清理、`VACUUM` | 只对另建候选副本执行 | 只对另建候选副本执行 | 禁止 |
| 正式 PostgreSQL `load` | 禁止作为权威源 | 唯一允许的权威源 | 先生成快照，不直接读取在线原库 |
| 正式切换、回滚、容器配置和凭据 | 不执行 | 在生产端执行 | 在生产端执行 |

开发目录中的 `db/spirit.db` 是证据和演练输入，不是生产权威源。正式迁移、最终瘦身和上线都在生产端维护窗口内完成；开发目录只提前验证相同工具、相同 schema 和相同校验规则。

### 2.2 当前同步副本基线

2026-07-30 只读审计得到以下基线，正式迁移报告必须用停机快照重新生成，不能硬编码这些数字：

- `biz_video=18,992`，`biz_graphic_content=6,833`，作品业务键重复组均为 0。
- `biz_collect_run=5,138`：`COMPLETED=2,941`、`DB_FAILED=1,097`、`FETCH_FAILED=945`、`QUEUED=148`、`INTERRUPTED=7`。
- `biz_collect_run_item` 中 `PENDING=2,870`、`FAILED=408`、`COMPLETED=4,569`；待处理状态不是清理候选，必须完整迁移。
- `biz_job_queue` 中 `QUEUED=20`、`RETRY_WAIT=128`；run、item、event 和明细孤儿数均为 0，已检查的重复组均为 0。
- `biz_video.jsonData = biz_video.videoinfo` 的精确重复行有 13,174 条，逻辑字符数 762,175,487。该项是可验证的瘦身候选，不在源 SQLite 上原位删除。
- 当前完整 `PRAGMA quick_check` 和 `PRAGMA integrity_check` 均为 `ok`；这只证明当前同步文件可读，不证明正式停机时的生产快照已经通过门槛。

### 2.3 同步日志基线

同步日志中 74 条 ERROR 由 73 条 `F2_UPSTREAM_RATE_LIMIT` 和 1 条 `UPSTREAM_SCHEMA_ERROR` 组成，没有新的数据库异常或下载进程超时证据。另有两次 `open-in-view` 和两次静态资源尾斜杠启动告警、一次 Bilibili Cookie 为空告警及少量未登录/`favicon.ico` 告警。

限流从 00:09 持续到 12:58，约每 10 分钟触发一次，说明结构化错误和全局冷却已经生效，但也说明上游凭据或风控环境未恢复。单次 `UPSTREAM_SCHEMA_ERROR` 必须在 C1 中保留有限 F2 stderr 摘要和响应 schema 版本，避免把 profile 非零退出误判为数据库错误。`spring.jpa.open-in-view=false` 和资源目录规范化纳入 C3 配置清理；Bilibili Cookie 为空仅在启用 Bilibili 任务时阻断上线。

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

### 10.1 生产使用同一 Docker Compose

生产不单独 `docker run` 一个临时 PostgreSQL 容器，而是在现有 Compose 中增加 PostgreSQL 服务，与 `stream-vault` 使用同一默认网络。现有应用挂载保持不变：

```yaml
version: "3.8"

services:
  postgres:
    image: postgres:16-alpine
    container_name: stream-vault-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${POSTGRES_DB:?set POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER:?set POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD}
      TZ: Asia/Shanghai
    volumes:
      - stream-vault-postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
      interval: 5s
      timeout: 5s
      retries: 30
    networks: [stream-vault]

  stream-vault:
    image: ${STREAMVAULT_IMAGE:-jonysun/stream-vault:v5.7.1}
    container_name: my-stream-vault
    restart: unless-stopped
    ports:
      - "28088:28081"
    volumes:
      - "./app:/app"
      - "./tmp:/tmp"
      - "/home/admin_sun/Video/Downloads/stream_vault:/app/resources"
    environment:
      TZ: Asia/Shanghai
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-docker}
      STREAMVAULT_DATABASE_KIND: ${STREAMVAULT_DATABASE_KIND:-sqlite}
      STREAMVAULT_DB_URL: ${STREAMVAULT_DB_URL:-jdbc:postgresql://postgres:5432/streamvault}
      STREAMVAULT_DB_USERNAME: ${POSTGRES_USER:?set POSTGRES_USER}
      STREAMVAULT_DB_PASSWORD: ${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD}
    depends_on:
      postgres:
        condition: service_healthy
    networks: [stream-vault]

volumes:
  stream-vault-postgres-data:

networks:
  stream-vault:
    driver: bridge
```

上面是目标结构，不是立即覆盖生产的文件。C4 实施时会将环境变量名称与 `application-postgresql.properties` 固定一致，并加入 `SPRING_PROFILES_ACTIVE=docker,postgresql` 的正式 `.env` 示例。PostgreSQL 不发布宿主机端口，只允许同一 Compose 网络访问；`stream-vault` 仍保留 `/app/db/spirit.db` 挂载，以便回滚到 SQLite，但 PostgreSQL profile 不得读取它。

迁移命令使用同一 Compose 的显式 one-shot `migration` profile（或等价的 `docker compose run --rm`），只在应用停止、PostgreSQL healthcheck 通过且目标库为空时执行 `audit/dry-run/load/verify`。默认 `docker compose up -d` 不运行迁移器，也不允许应用在 `load/verify` 前切换到 PostgreSQL；正式启动前必须通过 C5 的 readiness marker 和数据校验。

## 11. C5：迁移器

迁移器是显式运行的独立命令，支持：

- `audit`：验证 SQLite 源、重复数据和必要 schema。
- `dry-run`：迁移到临时 PostgreSQL schema，生成报告后删除临时 schema。
- `load`：从停机后最终 SQLite 快照分批导入正式 PostgreSQL。
- `verify`：只读比较两端，不写入。

迁移器按主键稳定分页，保留兼容表的原 ID，并在导入后把 PostgreSQL sequence 调整到 `MAX(id)+1`。未知枚举、无法读取页面、重复业务键、孤儿引用或字段转换失败都会停止阶段并写入有限报告，不能跳过。

瘦身不单独改写生产 SQLite。C5 在导入 PostgreSQL 候选库时只允许执行已经版本化且可逆验证的转换。首批仅处理 `biz_video.jsonData` 与 `videoinfo` 字节级完全相同的冗余副本：应用兼容代码和迁移测试证明读取语义不变后，目标 PostgreSQL 的冗余列置空；不相同、任一为空或无法解析的行保持原样。报告记录候选行数、源/目标逻辑字节数和字段 hash，不记录正文。物理空间回收由 PostgreSQL 新库天然完成，不对 SQLite 原库运行 `VACUUM`。

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

### 12.2 正式切换前置门槛

以下条件必须在维护窗口前全部满足，任何一项失败都推迟上线：

1. C1-C5 已合并、打包并固定镜像 digest，SQLite 生产已稳定运行 C1-C3 至少一个完整调度周期。
2. 同一版本迁移器已对最近生产一致性快照完成两次独立 dry-run；第二次迁移和回滚演练均无未分类差异。
3. PostgreSQL 已加入生产现有的同一 Docker Compose，并配置独立持久 volume、内部网络、健康检查、容量预警、备份与一次恢复验证；密码只通过生产 `.env`/secret 注入，不写入仓库和日志。
4. 已保存 SQLite 与 PostgreSQL 的表行数、业务键、活跃队列、抽样 hash、查询计划和性能基线。
5. 已确认抖音风控状态；风控未恢复不阻止数据库迁移，但上线后收藏调度继续保持暂停，不能用数据库切换掩盖上游问题。
6. 已确认回滚负责人、维护窗口、停机公告、生产磁盘余量至少能同时容纳原 SQLite、不可变快照、PostgreSQL 数据与备份。

### 12.3 生产一致性快照

1. 在管理端开启 `pause.all`、`pause.collect`、`pause.download` 和 `pause.hls`，导出 runtime control、活跃 run/job/item 统计和当前外部进程列表。
2. 等待正在执行的 F2、yt-dlp 和 ffmpeg 正常结束；超过 C1 超时上限的进程由受控执行器终止并记录，不能直接进入复制阶段。
3. 停止应用容器，确认没有应用进程持有 SQLite 文件，也没有第二个应用容器。
4. 在生产端创建带时间戳的只读归档目录，原样保存 `.db` 以及当时实际存在的 `-wal`、`-shm`；缺失的伴随文件记录为“not present”，不能伪造空文件。
5. 对归档中的每个文件生成 SHA-256、长度、mtime、应用镜像 digest 和迁移器版本清单。归档随后只读，不再参与任何修复或瘦身。
6. C2 `snapshot` 工具从归档生成新的候选快照。候选执行完整 `integrity_check`、schema 指纹、关键表行数和业务键审计，全部通过才成为 C5 正式源。

如果完整性失败：停止正式切换。仅索引条目数错误可在候选副本上 `REINDEX` 后全量复检；任何 table B-tree、rowid 顺序、缺页或不可读页错误都不能只靠 `REINDEX`，必须优先从可靠备份恢复，或逐表导出到全新 SQLite 候选库。重建候选需要通过完整性、行数、业务键、孤儿、活跃队列和抽样 hash 的全部校验后，再重新做两次 PostgreSQL 演练。原始归档始终保留。

### 12.4 分钟级正式切换 runbook

下面以 120 分钟窗口为基线；数据量或校验耗时更长时直接延长，不跳过步骤：

| 时间 | 位置 | 操作与门槛 |
| --- | --- | --- |
| `T-30` 至 `T0` | 生产 | 公告维护；确认镜像 digest、PostgreSQL 备份、磁盘、凭据和回滚包；记录四个 pause 状态。 |
| `T0` 至 `T+10` | 生产 | 暂停全部后台任务，等待 worker/外部进程排空，停止应用容器并确认单实例已退出。 |
| `T+10` 至 `T+25` | 生产 | 归档 SQLite/WAL/SHM，生成 hash 清单和一致性候选快照；完整性失败立即宣布回滚，不启动旧/新应用写入。 |
| `T+25` 至 `T+35` | 生产 | 通过同一份 Compose 只启动空 `postgres` 服务，验证版本、locale/timezone、volume、内部网络和 healthcheck，再由 one-shot migration profile 运行 Flyway；目标库非空或版本不符即停止。 |
| `T+35` 至 `T+75` | 生产 | C5 `load` 按主键分批导入，应用已批准的精确冗余清理，校准全部 sequence；任一批次错误停止且不跳行。 |
| `T+75` 至 `T+90` | 生产 | C5 `verify` 对账表行数、业务键、活跃状态、孤儿、固定及随机样本 hash、冗余转换报告和 sequence。零未分类差异才继续。 |
| `T+90` 至 `T+100` | 生产 | 更新同一 Compose 的生产 `.env`，以 `docker,postgresql` profile 启动固定 digest 的单应用容器；readiness 通过前所有 worker fail-closed，四个 pause 保持开启。 |
| `T+100` 至 `T+110` | 生产 | 只读 smoke：登录、首页、作者、Feed、作品详情/播放、收藏任务列表、run 详情、数据库状态；执行一组可回滚的小写入测试。 |
| `T+110` 至 `T+120` | 生产 | 先解除 `pause.all` 但保持 collect/download/hls 暂停，确认普通写入；随后按 download、hls、collect 顺序逐项恢复并观察。抖音仍被风控时保持 collect 暂停。 |
| `T+120` 后 | 生产 | 连续观察 2 小时，再观察 24 小时和 7 天；SQLite 归档只读保留至少 30 天，PostgreSQL 每日备份并验证。 |

恢复调度后先保持一个抓取 worker 和一个下载 worker。观察指标包括 ERROR/WARN 结构化错误码、队列 oldest age、claim 重复、锁等待、deadlock/serialization failure、连接池饱和、慢查询、数据库/volume 增长、外部进程数和僵尸进程。PostgreSQL 稳定 7 天后才单独评估增加 worker；媒体表规范化和 JSONB 改造另开工程。

### 12.5 回滚

`T+100` 前失败时 PostgreSQL 应用不得启动，清理或隔离失败目标库后直接用原 SQLite 配置恢复旧镜像。`T+100` 后出现核心功能回归时，立即开启四个 pause、停止 PostgreSQL 应用并保存 PostgreSQL 备份与变更审计；然后以切换前 SQLite 快照和旧镜像恢复。初次切换不自动反向同步，PostgreSQL 启动后产生的新作品、收藏明细和配置变更按业务键生成补录报告，由人工确认后补入 SQLite；因此 smoke 阶段只允许少量可追踪写入。回滚完成后重新执行队列状态和媒体业务键核对，不能在同一窗口再次强行切换。

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
- F2 profile 非零退出保留脱敏且有界的 stderr/schema 摘要，并稳定映射为结构化错误码。
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
- `open-in-view` 已显式关闭，资源目录配置不再产生启动告警。

### C4

- Testcontainers PostgreSQL 从空库 Flyway 成功，Hibernate validate 通过。
- runtime control、作者补全、收藏 run/job/item、审计和保留策略契约通过。
- 并发 worker 使用 `SKIP LOCKED` 不重复领取。
- SQLite 与 PostgreSQL profile 不交叉装配专属组件。

### C5

- dry-run、重复执行、故障中断、sequence 校准和校验失败测试。
- `QUEUED`、`RETRY_WAIT`、`PENDING`、attempt、available time 和 runtime control 在迁移前后逐项一致。
- 精确重复 `jsonData` 清理前后作品读取、详情、播放、编辑和再次抓取回归通过；非精确重复行零改写。
- Java 和 Python 全套测试通过。
- 两次生产快照迁移演练报告无未分类差异。

## 15. 完成标准

- 任一生产外部进程都不能无限阻塞 worker。
- 后台任务在应用与数据库 ready 前不能领取。
- SQLite 完整性失败时应用 fail-closed，且存在不覆盖原库的离线诊断路径。
- PostgreSQL profile 能从空库启动并通过真实数据库契约测试。
- 迁移器可以从一致性 SQLite 快照生成零未分类差异的 PostgreSQL 数据库。
- 正式切换有可执行时间点、校验门槛和已演练回滚路径。
