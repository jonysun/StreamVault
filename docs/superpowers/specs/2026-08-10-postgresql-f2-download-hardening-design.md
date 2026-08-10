# PostgreSQL、F2 与抖音下载链路加固设计

## 背景

生产环境已经完成 PostgreSQL 迁移。本次诊断只以生产日志、PostgreSQL Flyway 版本和当前实体映射为依据，不读取 SQLite 数据库，也不修改任何 SQLite 迁移或兼容逻辑。

2026-08-10 的生产日志确认了三类需要修改的应用问题：

1. 多个 F2 Python 子进程共用相对目录 `./logs`，F2 导入时并发清理同一个历史日志文件，产生 `FileNotFoundError`。该错误当前被误判为不可重试的 `F2_PROTOCOL_ERROR`。
2. PostgreSQL `biz_video` 的外部 URL 和文件路径列仍有 `VARCHAR(255)` 限制。下载完成后写入长封面、头像或路径时会触发 SQLState `22001`。
3. 下载侧 HTTP 429 和 F2 非 JSON 风控响应没有可靠进入全局风控冷却，下载线程会继续请求，产生大量重复的 `NETWORK_IO`。

日志中的 `UserId不合法` 是抖音远端 HTTP 200 响应内明确返回的业务错误，当前 `INVALID_AUTHOR_ID`、`TASK_CONFIGURATION`、不可重试的分类保持不变。

## 目标与成功标准

- F2 并发进程不再互相删除日志文件。
- 即使第三方 F2 仍出现同类运行时故障，也作为可重试的应用运行时错误处理，不永久终止收藏任务。
- 下载侧识别 HTTP 429、认证或验证风控后，立即启用现有容器内抖音全局冷却；抓取和下载共同遵守同一冷却状态。
- 日志能区分远端限流、认证/验证、一般远端非 JSON 响应、本地 F2 进程故障和数据库写入失败，且不输出 Cookie 或完整敏感响应。
- PostgreSQL 能保存合理长度的外部 URL 和本地/公开媒体路径，不截断数据。
- 已执行的 Flyway `V001` 至 `V005` 保持不变，升级通过新的前向迁移完成。
- 现有作品、收藏任务、下载队列及历史记录不被删除或重建。

## 方案

### 1. 隔离 F2 进程日志

`douyin.py` 在导入 F2 前为当前 Python 进程创建独立临时目录，并仅在 F2 初始化期间将该目录作为工作目录。F2 完成日志处理器初始化后恢复原工作目录，临时目录对象保留到进程退出后自动清理。

每个 Java 启动的 Python 进程拥有独立目录，因此 F2 的 `clean_logs(99)` 不再跨进程竞争。传给增量抓取脚本的已知 ID 文件和结果文件都是绝对临时路径，不受工作目录切换影响。

Java 增量抓取层同时保留防御性识别：若非零退出输出仍包含 F2 日志清理 `FileNotFoundError` 特征，则转换为新的 `F2_RUNTIME_ERROR`。该错误属于 `APPLICATION`，允许队列按既有退避规则重试，但不触发抖音风控冷却。

### 2. 单作品 F2 响应诊断与全局冷却

单作品解析继续通过现有 F2 子进程执行，不引入新依赖或新的常驻服务。

当 F2 输出不是有效 JSON 时，Java 只提取安全诊断特征：

- HTTP 429 或 `Too Many Requests`：远端限流；
- HTTP 401/403、登录、验证、验证码、风控标记：认证或风险控制；
- 非零进程退出、Python traceback：F2 进程或依赖运行时故障；
- HTML 或其他非 JSON 内容：远端协议/响应异常；
- 无法进一步识别：记录退出码和响应长度，不记录原始正文。

`PlatformCookieService.isRiskSignal` 增加 429 及常见限流文本。适配器收到限流或认证/验证诊断后调用现有 `reportRisk`，启动容器内全局冷却并抛出 `DouyinGlobalCooldownException`。下载任务被延后至冷却结束，不消耗普通失败重试次数；抓取 worker 也会看到同一冷却状态。

普通网络超时、连接重置、404 和 DNS 故障仍归类为可重试 `NETWORK_IO`，不因为单个 CDN 节点故障启动全局风控冷却。

### 3. PostgreSQL 动态字段前向迁移

新增 Flyway `V006`，只执行无损的 `ALTER COLUMN ... TYPE TEXT`。不修改 `V001` 基线，避免已迁移生产库出现 Flyway checksum 不一致。

扩展范围限定为由远端或文件系统产生、长度不可由应用控制的字段：

- `biz_video`：`videoaddr`、`videocover`、`videounrealaddr`、`originaladdress`、`sourceurl`、`authoravatar`；
- `biz_graphic_content`：`markroute`、`originaladdress`、`sourceurl`、`authoravatar`。

相应 JPA 实体字段使用 `columnDefinition = "TEXT"`，保证 PostgreSQL 启动时的 Hibernate `validate` 与实际 schema 一致。作者 ID、平台键、状态和其他有明确语义边界的字段继续保持现有长度，不进行无关扩表。

迁移不会重写业务值、删除索引或改变主键。PostgreSQL 将 `varchar` 改为 `text` 不需要数据转换逻辑，部署时由 Flyway 在应用启动阶段执行一次。

### 4. 错误分类与恢复

- `F2_RUNTIME_ERROR`：应用/F2 运行时，可重试，不启动风控冷却。
- `F2_UPSTREAM_RATE_LIMIT` 或下载侧明确 429：远端 API，启动全局冷却并延期。
- 登录/验证拒绝：远端认证/风控，启动全局冷却并延期。
- `NETWORK_IO`：网络或 CDN，可重试，除非信息中明确包含风控证据。
- `DB_WRITE_FAILED`：数据库写入，可重试。V006 部署后，未耗尽重试的任务可自动恢复；已终止的项目由下载中心手动重试。
- `INVALID_AUTHOR_ID`：任务配置，不可重试，要求用户修正作者链接或 ID。

## 验证

1. Python/脚本契约测试确认 F2 导入前建立进程独立日志目录，并在初始化后恢复工作目录。
2. Java 单元测试覆盖日志清理竞态到 `F2_RUNTIME_ERROR` 的转换和 worker 的可重试分类。
3. Java 单元测试覆盖 429、认证/验证、HTML、traceback 与一般非 JSON 响应的安全诊断，确认不泄露 Cookie。
4. 适配器/Cookie 服务测试确认下载侧 429 启动全局冷却，而 404、超时不启动冷却。
5. PostgreSQL 部署契约测试确认 V006 只扩展指定字段、旧迁移未被修改、实体映射与 `TEXT` 一致。
6. 运行相关定向测试及完整 Maven 测试；若环境允许，再执行编译或打包验证。

## 部署与回滚

发布新镜像后，应用启动时 Flyway 自动执行 V006。迁移完成后再启动 worker，旧的可重试下载项会继续处理。

该迁移是字段放宽，应用回滚到旧镜像仍可读取 PostgreSQL `TEXT` 值，但旧实体验证可能不接受列类型。因此数据库迁移完成后的首选回滚方式是修复并重新发布应用，不将字段缩回 `VARCHAR(255)`，避免破坏已经写入的长值。

## 非目标

- 不修改 SQLite 数据库、SQLite schema 初始化器或旧 SQLite 迁移工具。
- 不重构 F2 为常驻服务。
- 不改变收藏分页、全量回填或下载排序逻辑。
- 不把普通 CDN 404、超时或连接重置误判为全局风控。
