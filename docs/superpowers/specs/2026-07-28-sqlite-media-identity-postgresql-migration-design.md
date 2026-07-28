# SQLite 媒体主键修复、数据库瘦身与 PostgreSQL 迁移设计

日期：2026-07-28  
状态：总体方案已确认，待书面规格复核

## 1. 背景

收藏下载已经使用短事务重试，但生产 Docker 日志仍稳定出现
`SQLITE_BUSY_SNAPSHOT`。失败 SQL 是向 `biz_video` 插入新作品。

根因不是等待时间不足，而是同一业务事务内的确定性冲突：

1. `WorkPersistenceService` 先执行查重查询，SQLite 建立读快照。
2. `VideoDataEntity` 和 `GraphicContentEntity` 使用 Hibernate
   `GenerationType.TABLE`。
3. Hibernate 通过隔离连接更新并提交 `seq_common` 以取得 ID。
4. 外层连接随后尝试写媒体表，但其读快照已经落后于 WAL，无法升级为写事务。
5. 每个全新重试事务都重复上述顺序，因此增加等待或重试次数不能解决问题。

项目同时存在三项数据库需求：

- 消除当前收藏下载持久化故障。
- 清理可证明安全的重复大字段和历史数据，控制 SQLite 体积。
- 建立可演练、可验证、可回滚的 PostgreSQL 迁移路径，并提升队列与查询性能。

这三项工作组成一个数据库工程，但不能放入一个不可独立回滚的大发布包。

## 2. 目标

1. 新视频和新图文在 SQLite WAL 模式下能够一次提交成功。
2. 当前修复不在业务服务中引入 SQLite 或 PostgreSQL 条件分支。
3. SQLite 数据清理必须先审计、后预览、再显式执行，并可分批续跑。
4. 只自动清理内容可证明等价的数据；有歧义的数据只报告，不自动删除。
5. PostgreSQL 使用 Flyway 管理规范化 schema，Hibernate 只执行 `validate`。
6. SQLite 到 PostgreSQL 的迁移支持 audit、dry-run、initial-load、final-delta 和 verify。
7. PostgreSQL 切换前必须通过数据契约、查询计划、队列并发和回滚演练。
8. 每个阶段独立提交、测试、发布和观察。

## 3. 非目标

- 不在当前故障修复中迁移全部旧实体的主键策略。
- 不在应用启动时自动重建生产 SQLite 表。
- 不根据标题、作者昵称或模糊相似度自动删除作品。
- 不在线执行 `VACUUM` 或 `VACUUM INTO`。
- PostgreSQL 初次切换不引入 Redis、对象存储或多机分布式 worker。
- 不在 SQLite 与 PostgreSQL 之间长期双写。

## 4. 总体发布结构

整个工程拆成五个可独立验收的阶段：

| 阶段 | 目标 | 主要结果 |
|---|---|---|
| A | 修复 SQLite 媒体持久化 | 媒体实体原生 identity、schema preflight、真实 WAL 回归测试 |
| B | SQLite 审计与瘦身 | 重复分类、preview/apply 清理、保留策略、离线压缩手册 |
| C | PostgreSQL 基础设施 | 驱动、Flyway、profile、规范化 schema、数据库专属适配层 |
| D | 迁移与切换 | 独立迁移器、dry-run、全量与增量、校验报告、回滚演练 |
| E | PostgreSQL 性能扩展 | `SKIP LOCKED`、worker 并发、查询计划与索引调优 |

阶段 A 必须先独立发布，不能等待 B-D 完成后才恢复收藏下载。

## 5. 阶段 A：SQLite 媒体 identity 修复

### 5.1 实体映射

只修改以下两个当前故障路径中的实体：

- `VideoDataEntity.id`
- `GraphicContentEntity.id`

二者从 `GenerationType.TABLE` 改为 `GenerationType.IDENTITY`，删除对应
`@TableGenerator`。当前 Java ID 类型继续使用 `Integer`，不改 API、DAO 和现有外键语义。

SQLite 使用 `id INTEGER PRIMARY KEY` 原生生成 ID。媒体插入不再访问
`seq_common`，从根源上消除“外层读快照 + 隔离连接写序列表”的冲突。

`seq_common` 表及其媒体序列行本阶段不删除。遗留值不影响正确性，保留它们可降低回滚风险。

### 5.2 SQLite schema preflight

将 `biz_video` 和 `biz_graphic_content` 加入 `SqliteSchemaPreflight` 的 identity
验证集合。对于已经存在的表，启动时要求：

- 存在 `id` 列。
- 声明类型严格为 `INTEGER`。
- `id` 是唯一的单列主键。

不兼容时应用明确失败，并报告表名和期望结构；不得自动重建表或修改生产数据。
空库仍由当前 Hibernate 初始化流程创建表，preflight 只验证结果。

### 5.3 阶段 A 测试

新增真实文件 SQLite + WAL + Hibernate 集成测试，测试必须使用实际媒体实体映射：

1. 在事务中先查询 `biz_video` 建立读快照，再持久化新视频并 flush。
2. 在事务中先查询 `biz_graphic_content`，再持久化新图文并 flush。
3. 验证两个实体都得到非空 ID，数据库各新增一行。
4. 在 `seq_common` 放置哨兵值，验证媒体持久化不会修改它。
5. 验证 preflight 接受 `INTEGER PRIMARY KEY`，拒绝 `BIGINT PRIMARY KEY` 和复合主键。
6. 保留并运行现有重复作品、持久化短重试和收藏下载回归测试。

阶段 A 验收标准：

- 当前测试在旧 `TABLE` 映射下能够复现失败，在新映射下通过。
- Docker 启动日志显示两张媒体表 identity preflight 通过。
- 首条新视频和首条新图文入库成功。
- 一个完整收藏调度周期内，媒体插入不再出现 `SQLITE_BUSY_SNAPSHOT`。
- PostgreSQL 的 `DirectDatabaseWriteExecutor` 行为没有变化。

## 6. 阶段 B：SQLite 审计与瘦身

### 6.1 已有基础

项目已经具备以下能力，后续应扩展而不是重写：

- 新 raw payload 只写 `jsonData`，`videoinfo` 只读兼容。
- `DatabaseAuditService` 统计 `jsonData/videoinfo` 使用量和内容差异。
- `DatabaseMaintenanceService` 使用签名 preview token、数据库 fingerprint、全局暂停和分批进度。
- `CLEAR_EXACT_DUPLICATE_VIDEOINFO` 只清理两列内容完全相同的行。
- `PURGE_EXPIRED_RUN_ITEMS` 已提供有限的历史运行记录保留策略。
- Feed 使用轻量 JDBC 投影、keyset 游标和有针对性的索引。

### 6.2 审计分类

扩展审计报告，但不返回完整 payload、cookie 或敏感数据：

1. `jsonData = videoinfo` 的完全重复大字段。
2. 两列均存在但内容不同的行，只输出 ID、长度和 SHA-256。
3. 仅 `videoinfo` 存在的旧行，确认 fallback 读取仍可用。
4. 作品业务键候选重复：平台规范键、外部作品 ID、媒体类型和来源地址。
5. 平台别名或空 `platformkey` 导致的历史重复候选。
6. 同一业务键对应多个不同本地媒体路径的冲突候选。
7. 失去父记录的运行项、作者历史、队列项和媒体附属数据。
8. 已终态且超过保留期限的 run、run item、event 和 job。
9. 表、索引、raw payload、snapshot 和 freelist 的空间占用。

作品候选重复不能直接按 `videoid` 删除。只有规范平台键、外部作品 ID、媒体类型、
本地媒体引用和保留规则全部明确后，才能形成可执行合并计划。

### 6.3 清理规则

- 自动清理仅允许内容完全相同的 `videoinfo` 副本和明确过期的运行历史。
- 不同 payload 永不由自动规则删除。
- 重复作品必须生成逐组 preview：主记录、候选记录、字段完整度、媒体路径和引用关系。
- apply 必须要求全局任务暂停、有效 preview token、未变化的数据库 fingerprint 和显式启用开关。
- 每批独立事务，使用主键游标记录断点；失败后可从上次成功 ID 继续。
- 清理数据库行不自动删除媒体文件。文件清理由独立的媒体维护流程处理。
- 新唯一约束只在审计结果为零冲突且清理报告已经人工确认后创建。

### 6.4 物理压缩

在线清理只产生 SQLite 可复用页，不保证数据库文件立即缩小。物理压缩采用停服流程：

1. 暂停全部后台任务并停止应用。
2. 对 `spirit.db`、WAL 和 SHM 完成 checkpoint 与冷备份，并记录 hash。
3. 对备份执行 `quick_check`。
4. 使用 `VACUUM INTO` 写入新文件，不覆盖原文件。
5. 对新文件再次执行完整性、行数和业务键校验。
6. 原子切换数据库文件，原文件保留到观察期结束。

应用不提供在线一键 VACUUM。

## 7. 阶段 C：PostgreSQL 基础设施和规范化 schema

### 7.1 运行配置

引入：

- PostgreSQL JDBC driver。
- Flyway core 和 PostgreSQL 扩展。
- Testcontainers PostgreSQL 测试依赖。
- `application-postgresql.properties`。
- `db/migration/postgresql/V001__baseline.sql` 及后续版本迁移。

PostgreSQL profile 使用 `spring.jpa.hibernate.ddl-auto=validate`。SQLite 在迁移观察期继续使用
现有 profile，但 SQLite 初始化器、preflight、runtime verifier 和写串行化组件都必须通过
`streamvault.database.kind=sqlite` 条件装配。

### 7.2 PostgreSQL 目标模型

PostgreSQL 不原样复制两张宽媒体表，而采用已有路线图中的规范化模型：

- `media_work BIGINT IDENTITY`：统一作品业务字段和唯一键
  `(platform_key, external_work_id)`。
- `media_video`：视频本地路径、播放和编码信息。
- `media_graphic_slide`：图文每个图片或小视频独立成行。
- `work_raw_payload`：raw payload、SHA-256、捕获时间和可选过期时间。
- `author_profile` 和 `author_name_history`：规范作者身份和名称历史。
- 收藏 run、item、event 和 job 保持当前状态语义，ID 使用 identity，时间使用
  `TIMESTAMPTZ`。

阶段 A 的 `Integer IDENTITY` 是 SQLite 当前模型的正确修复；阶段 C 的
`BIGINT IDENTITY` 是 PostgreSQL 新模型。二者通过迁移映射衔接，不要求当前 API 在阶段 A
提前改成 `Long`。

### 7.3 数据库专属边界

业务服务继续依赖数据库无关接口。只有确实不同的 SQL 放入基础设施实现：

- SQLite：单写协调、PRAGMA/schema preflight、SQLite 日期和空间审计。
- PostgreSQL：`FOR UPDATE SKIP LOCKED` 任务领取、catalog/schema 审计、PostgreSQL 保留策略 SQL。
- Flyway 管理 PostgreSQL schema；SQLite 的历史初始化器不得在 PostgreSQL profile 装配。

优先扩展现有 `DatabaseWriteExecutor` 和事务/DAO 边界。只有存在两个真实实现时才新增小接口，
不建立通用 SQL 方言框架。

### 7.4 PostgreSQL 契约测试

Testcontainers 测试至少覆盖：

- Flyway 从空库迁移成功，Hibernate validate 通过。
- media、author、run 和 job identity 正常生成。
- 作品和作者唯一键阻止重复写入。
- 四个并发 worker 使用 `SKIP LOCKED` 不会重复领取同一 job。
- 失败、重试、过期锁恢复和状态条件更新保持当前语义。
- Feed keyset 在相同时间和跨媒体类型时无重复、无遗漏。
- raw payload 不进入 Feed 轻量查询。

## 8. 阶段 D：迁移器、切换和回滚

### 8.1 独立迁移器

迁移器是独立命令或 Maven module，不随应用启动自动执行，支持：

- `audit`：只读检查 SQLite 源库。
- `dry-run`：迁入临时 PostgreSQL schema，验证后删除临时 schema。
- `initial-load`：生产全量初始加载。
- `final-delta`：停写后复制初次加载后的变化。
- `verify`：只比较两端，不写数据。

### 8.2 ID 与数据映射

SQLite 的 `biz_video.id` 和 `biz_graphic_content.id` 可能重叠，迁入统一
`media_work` 时不能都原样作为主键。迁移器维护稳定映射：

```text
(video, old_id)   -> media_work.work_id
(graphic, old_id) -> media_work.work_id
```

依赖媒体 ID 的关联表通过此映射转换。未合并且主键空间不冲突的业务表可以保留原 ID，导入完成后
将 PostgreSQL identity sequence 调整到 `MAX(id) + 1`。

图文 `images` JSON 数组按原顺序拆成 `media_graphic_slide`，每个 slide 保存类型、序号、远程地址和
本地路径。无法解析的行进入错误报告，不静默丢弃。

### 8.3 校验合同

每批和最终报告必须断言：

- 源作品业务键集合等于目标作品业务键集合。
- 目标重复作品业务键为零。
- 源规范作者键集合等于目标作者键集合。
- 视频、图文、混合内容数量一致。
- 媒体文件引用集合一致，迁移器不复制实际媒体文件。
- raw payload SHA-256 一致；无法分类的 payload 差异为零。
- 收藏任务定义、run 终态和未完成 job 数量符合迁移规则。
- 固定样本和随机样本字段 hash 一致。
- 发布时间范围、作者作品 top N 和收藏计数一致。

### 8.4 切换

1. 冷备份 SQLite 并记录 hash。
2. 运行 initial-load 和 verify，期间应用仍使用 SQLite。
3. 预约维护窗口，暂停全部任务并等待运行事务结束。
4. 停止应用，完成 WAL checkpoint，运行 final-delta 和最终 verify。
5. 使用 PostgreSQL profile 启动只读 smoke 实例。
6. 验证登录、首页、作者、Feed、播放、任务列表和维护审计。
7. 启动主实例并保持任务暂停，手工执行小规模收藏、下载和 HLS。
8. 验证后恢复调度，并在初期限制 worker 数量。
9. SQLite 冷备份进入只读保留，不再双写。

### 8.5 回滚

出现数据校验失败、核心 API 明显回归、任务重复领取或媒体路径大面积不可用时：

1. 立即暂停全部任务。
2. 停止 PostgreSQL 应用并保存切换期间变更审计。
3. 不执行自动反向同步。
4. 恢复切换前 SQLite 冷备份并启动 SQLite profile。
5. 切换期间 PostgreSQL 新增作品按业务键生成单独补录报告。

## 9. 阶段 E：性能提升

性能调整以测量为依据，不以数据库更换本身作为完成标准。

### 9.1 查询

- SQLite 保存 `EXPLAIN QUERY PLAN`，PostgreSQL 保存
  `EXPLAIN (ANALYZE, BUFFERS)` 基线。
- Feed、作者作品、任务领取和运行详情的扫描行数应接近 page/batch size。
- Feed 不读取 raw payload，不做全表 offset 深分页，不做无界全量排序。
- 只为实际查询建立复合或部分索引，移除确认无使用价值的重复索引。

### 9.2 队列与并发

- SQLite 继续保持单数据库写 worker；网络抓取和媒体处理可在事务外并行。
- PostgreSQL 使用 `FOR UPDATE SKIP LOCKED` 允许多个 worker 原子领取不同任务。
- 初次切换保持当前 worker 数，确认锁等待、错误率和平台风控稳定后逐步增加。
- 唯一约束和条件状态更新仍是最终正确性保障，不能只依赖进程内锁。

### 9.3 PostgreSQL 运维

- 监控连接池等待、慢查询、dead tuple、autovacuum 延迟和磁盘增长。
- 备份必须包含实际恢复演练；条件允许时启用 WAL/PITR。
- 首次迁移不引入 Redis。只有 PostgreSQL durable queue 稳定并存在实际通知延迟需求后，
  才评估 Redis 作为唤醒和缓存层。

## 10. 错误处理和安全规则

- 当前 `SQLITE_BUSY_SNAPSHOT` 保持 retryable 分类，但阶段 A 后媒体 identity 路径不应再触发它。
- schema 不兼容必须启动失败，不自动重建生产表。
- 审计默认只读；apply 默认关闭，且要求暂停、签名 token 和 fingerprint 一致。
- 迁移器遇到未知枚举、无法解析图文、重复业务键或缺失必要引用时失败并报告，不跳过。
- 所有清理和迁移报告不得包含完整 raw payload、cookie、token 或请求头。
- 任何物理压缩、正式迁移和切换都以可验证冷备份为前置条件。

## 11. 分阶段验收

### 阶段 A

- 新视频和新图文 SQLite WAL 持久化测试通过。
- 媒体插入不修改 `seq_common`。
- Docker 一个完整调度周期无媒体插入 `SQLITE_BUSY_SNAPSHOT`。

### 阶段 B

- 生产副本 audit 可重复生成相同 fingerprint。
- 自动清理只影响 preview 中确认的完全重复或过期行。
- 清理前后业务键、媒体引用和不同 payload 行保持一致。
- 离线压缩后 `quick_check` 和业务校验通过。

### 阶段 C

- Flyway、Hibernate validate 和 PostgreSQL Testcontainers 全部通过。
- SQLite profile 全量回归通过，数据库专属组件不会交叉装配。

### 阶段 D

- dry-run、initial-load、final-delta、verify 和回滚演练全部有报告。
- 最终校验合同无未分类差异。

### 阶段 E

- 查询计划不出现已定义关键路径的无界全表扫描或大字段回表。
- 多 worker 不重复领取任务，吞吐提升且错误率、锁等待和平台限流在阈值内。

## 12. 实施顺序

1. 先实现并发布阶段 A，恢复收藏下载正确性。
2. 观察至少一个完整收藏调度周期。
3. 实现阶段 B 的扩展审计，在生产数据库副本上只读运行并人工复核。
4. 清理操作和物理压缩分别发布、分别执行。
5. 实现阶段 C，并保持生产默认 SQLite。
6. 实现阶段 D，在生产副本上反复 dry-run 和回滚演练。
7. 在维护窗口正式切换 PostgreSQL。
8. 稳定观察后实施阶段 E 的并发和索引调优。

每个阶段使用独立 PR。阶段 A 不夹带数据清理或 PostgreSQL 依赖；阶段 B 不切换数据库；
阶段 C 不自动迁移生产数据；阶段 D 不顺便提高 worker 并发。
