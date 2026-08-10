# 抖音 F2 单作品错误与请求协调设计

## 背景与根因

生产日志显示，PostgreSQL `V006` 已成功执行，F2 共享日志目录引发的 `clean_logs` 并发异常也已消失。当前故障属于另一条链路：全局冷却结束后，收藏抓取线程与收藏下载线程会同时恢复 F2 请求。下载线程可以在抓取线程尚未报告 429 前连续处理多个旧重试项，形成瞬时请求高峰。

单作品命令 `fetch_work_data` 目前让 F2 异常直接退出 Python 进程。Java 只能看到退出码和一段被隐藏的 traceback，再把包装后的 `IOException` 统一归类为 `NETWORK_IO`。这会同时造成错误归属不准确、真实风控不能从下载链路立即触发冷却，以及日志无法说明远程 API 实际发生了什么。

## 目标

- 收藏抓取与单作品 F2 元数据请求在单容器内不并发执行。
- 单作品失败能够区分远程限流、认证或验证、普通上游响应异常、作品不可用、网络异常和本地 F2 运行时异常。
- 只有明确的限流、认证或验证证据触发抖音全局冷却。
- 被删除或明确不可用的作品停止自动重试，但保留本地作者、作品记录和已下载文件。
- 普通上游错误和网络错误继续有限重试，不遗漏暂时不可用的作品。
- 日志和持久化错误中不包含 Cookie、签名媒体 URL 或完整上游响应正文。

## 非目标

- 不修改 PostgreSQL 表结构或既有 Flyway 迁移。
- 不恢复 SQLite 兼容逻辑。
- 不改变媒体文件下载的并发模型；请求协调只覆盖 F2 抓取和单作品元数据进程。
- 不跨容器共享锁或冷却状态。
- 不升级或分叉 F2 依赖。

## 方案比较

### 方案 A：只增强日志文本

保留现有流程，从 traceback 中匹配关键词。改动最小，但 F2 可能丢失 HTTP 状态，文本和本地化版本也不稳定，不能解决抓取与下载同时恢复的问题。

### 方案 B：结构化单作品错误 + 容器内公平请求门控（采用）

Python 输出与增量抓取一致的安全结构化错误；Java 使用类型化异常传递错误码；抓取与单作品元数据调用通过一个公平的容器内门控串行执行。该方案不依赖 F2 内部 traceback 格式，可以同时解决诊断、分类和瞬时并发问题，改动范围仍限制在抖音 F2 边界。

### 方案 C：统一重写全部平台请求调度

建立通用限速器和跨平台队列。扩展性较高，但会扩大到 YouTube、直链和媒体 CDN 下载，不符合本次故障范围。

## 组件设计

### Python 单作品错误边界

`douyin.py` 的 `fetch_work_data` 捕获 F2 API、网络和本地运行时异常，输出一条带固定前缀的 JSON 错误信封，然后以非零状态退出。信封字段与增量抓取保持一致：

- `errorCode`
- `message`
- `diagnostics.exceptionType`
- `diagnostics.upstreamStatus`
- `diagnostics.faultDomain`
- `diagnostics.retryable`
- `diagnostics.cooldownApplied`

映射规则：

| 条件 | 错误码 | 归属 | 重试 | 冷却 |
| --- | --- | --- | --- | --- |
| HTTP 429 / F2 rate-limit 异常 | `F2_UPSTREAM_RATE_LIMIT` | `REMOTE_API` | 是 | 是 |
| HTTP 401/403、登录、验证、captcha | `F2_COOKIE_OR_VERIFY_REQUIRED` | `REMOTE_API` | 是 | 是 |
| 明确 404、作品不存在或不可见 | `F2_WORK_UNAVAILABLE` | `REMOTE_API` | 否 | 否 |
| F2 `APIResponseError` 且无更强证据 | `F2_UPSTREAM_RESPONSE_ERROR` | `REMOTE_API` | 是 | 否 |
| 连接、DNS、超时 | `F2_NETWORK_ERROR` / `F2_UPSTREAM_TIMEOUT` | `NETWORK` | 是 | 否 |
| traceback、导入、日志或进程初始化错误 | `F2_RUNTIME_ERROR` | `APPLICATION` | 是 | 否 |

错误消息只保留异常类别、HTTP 状态和有限长度的去敏摘要。成功路径仍只输出作品 JSON。

### Java 类型化错误

新增抖音单作品获取异常类型，保存错误码、故障域、可重试标记和是否要求冷却。`DouUtil` 负责解析 Python 错误信封；没有信封的旧式失败继续使用安全后备分类，但不再仅凭 `IOException` 断定为网络问题。

`DouyinPlatformAdapter` 对要求冷却的错误调用现有 `PlatformCookieService.reportRisk`，并抛出 `DouyinGlobalCooldownException`。其他类型化错误保持其错误码进入下载服务。

`CollectDownloadService` 优先识别类型化异常：

- `F2_WORK_UNAVAILABLE` 作为不可重试失败，不删除任何本地数据。
- 上游、网络和运行时错误按异常携带的重试属性处理。
- 仅真正的底层媒体网络异常继续使用 `NETWORK_IO`。

### 单容器请求门控

增加一个 Spring 单例、使用公平 `ReentrantLock` 的抖音 F2 请求协调器。锁只包围两类操作：

1. 一次收藏作者增量抓取 F2 进程。
2. 一次单作品 `fetch_work_data` F2 进程及其错误分类、冷却上报。

媒体 CDN 下载不持有该锁。公平锁避免抓取队列或下载队列长期独占。等待锁期间响应线程中断；获得锁后再次检查全局冷却，若冷却已经开始则不启动新的 F2 进程。

这保证一次明确 429 在释放请求权之前已经完成冷却上报，等待方随后只会进入 `RETRY_WAIT`，不会继续形成批量失败。

## 数据流

1. Worker 在开始抖音 F2 操作前申请协调器许可。
2. 协调器获得公平锁后检查全局冷却。
3. 若在冷却中，当前任务不消耗尝试次数并延期到冷却结束。
4. 若可执行，启动唯一的 F2 进程。
5. Python 成功时返回作品或分页 JSON；失败时返回安全结构化错误。
6. Java 在仍持有许可时完成分类，并对明确风控启动全局冷却。
7. 释放许可；等待的另一条流水线重新检查冷却后决定执行或延期。

## 队列与重试行为

- 冷却延期沿用现有 `deferForCooldown`，回退本次 attempt，不消耗最大尝试次数。
- 普通上游响应、网络或运行时错误沿用现有 1 分钟、5 分钟、30 分钟有限退避。
- `F2_WORK_UNAVAILABLE` 直接进入失败终态，允许用户在下载中心手动重试。
- 不批量修改现有历史失败记录；新版本处理到旧记录时会按新分类自然收敛。

## 安全与可观测性

应用日志记录 work ID、错误码、故障域、HTTP 状态、F2 异常类型、退出码和输出长度。禁止记录 Cookie、完整请求 URL、签名媒体 URL、响应正文和 traceback 全文。持久化错误消息采用同一安全摘要。

日志必须能让用户判断：

- `REMOTE_API`：抖音/F2 上游拒绝或响应异常；
- `NETWORK`：容器到上游的连接问题；
- `APPLICATION`：本应用或 F2 运行环境故障；
- `F2_WORK_UNAVAILABLE`：该作品已删除、不可见或明确不存在。

## 测试与验收

- Python：为 429、401/403、普通 `APIResponseError`、404/不可用、超时、连接和运行时异常验证结构化信封与去敏。
- Java：验证错误信封解析、旧式 traceback 后备分类和 Cookie/签名 URL 不进入诊断。
- 适配器：验证只有 429、认证和验证会触发全局冷却。
- 下载服务：验证上游错误不再被记为 `NETWORK_IO`，作品不可用不重试，网络和运行时错误有限重试。
- 协调器：两个线程不能同时进入 F2 临界区；等待线程在前一个请求启动冷却后不执行外部调用；公平等待与中断行为正确。
- 回归：完整 Maven 测试、Python 测试及 `git diff --check` 通过。

生产验收日志应表现为：同一时刻最多一个 F2 抓取或单作品元数据进程；首次明确 429 后不再出现一串 `F2 work command failed exitCode=1`；失败记录显示准确错误码和归属；PostgreSQL 无新增迁移。
