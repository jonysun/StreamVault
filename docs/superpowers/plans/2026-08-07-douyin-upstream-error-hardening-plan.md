# 抖音抓取错误分类与根因可观测性修复计划

日期：2026-08-07

状态：已实现，待提交与演习验证

## 1. 背景与现象

2026-08-06 生产日志显示，抖音作者作品列表接口连续返回空响应或请求超时：

- 抓取 worker 启动 49 次，完成 0 次；
- 没有进入 `CollectDownload` 下载阶段；
- 多次触发 30 分钟全局抖音风险冷却；
- profile 接口多数返回 `statusCode=0` 且存在用户，不能据此认定 Cookie 完全失效；
- task 98 单独返回 `statusCode=2`、`UserId不合法`。

当前问题不是单一的“抖音风控”，而是远程 API 故障、本应用错误分类和任务配置错误混在了一起：

| 现象 | 实际根因候选 | 当前分类 | 风险 |
| --- | --- | --- | --- |
| f2 重试耗尽、最后页面 `NoneType` | 风控、网络、上游不可用或空响应 | 一律 `F2_UPSTREAM_RATE_LIMIT` | 误触发全局冷却 |
| author-work 请求超时 | 远程 API/网络超时 | `UPSTREAM_SCHEMA_ERROR` | 监控、重试策略和前端提示错误 |
| profile `UserId不合法` | 任务保存的作者 UID 无效 | `UPSTREAM_SCHEMA_ERROR` | 任务长期无效重试 |
| Java 无法解析结构化错误 | 本应用进程协议故障 | `IllegalStateException` | 远程问题与本应用 bug 无法区分 |

## 2. 目标与非目标

### 目标

1. 让每次失败都能明确回答：这是远程 API、网络、Cookie/验证、任务配置，还是本应用协议/代码问题。
2. 只有有足够证据时才开启全局抖音风险冷却。
3. 保留现有持久队列、重试次数和下载冷却事务语义。
4. 在 SQLite 开发环境和 PostgreSQL 生产环境使用同一套 Java 行为，不新增数据库字段。
5. 用自动化测试覆盖错误分类矩阵和 worker 的退避决策。

### 非目标

- 不更换 f2、抖音接口或 Cookie 获取方案；
- 不调整已经确认的全局冷却默认时长；
- 不把冷却状态跨容器持久化；
- 不修改下载文件、媒体表或 PostgreSQL schema；
- 不在本次修复中重写抓取分页算法。

## 3. 根因分析

### 3.1 Python 错误分类过于依赖异常类名

`douyin.py::_request_error` 当前只检查异常类型名称：

- 类型名包含 `RetryExhausted` 即映射 `F2_UPSTREAM_RATE_LIMIT`；
- 类型名包含 `timeout` 或 `TimeoutError` 才映射 `F2_UPSTREAM_TIMEOUT`；
- 其他异常全部映射 `UPSTREAM_SCHEMA_ERROR`。

生产中的 `APIResponseError` 携带了安全消息 `author-work page request timed out`，但类型名没有 `timeout`，因此被错误归类为 schema error。

### 3.2 “重试耗尽”没有保留足够的上游证据

`APIRetryExhaustedError` 只说明 f2 的内部重试结束，当前 diagnostics 只有 `NoneType`、空 status 等摘要，没有明确 HTTP 状态、响应头信号或重试原因。因此不能自动证明是 429/403 风控。

### 3.3 Java worker 将错误码直接绑定到全局冷却

`CollectJobWorker` 对 `F2_UPSTREAM_RATE_LIMIT` 和 `F2_COOKIE_OR_VERIFY_REQUIRED` 使用全局冷却剩余时间作为重试延迟。若 Python 误报 rate limit，所有后续 Douyin 任务都会被拖入全局冷却。

### 3.4 无效作者 UID 没有独立业务语义

profile 的 `statusCode=2`、`UserId不合法` 最终走通用 schema error，无法让前端或调度器知道应修改任务地址，而不是继续重试。

## 4. 推荐方案

采用“结构化错误协议 + 证据驱动分类 + Java 明确退避策略”的最小改动方案。

### 4.1 错误码分层

| 错误码 | 归属 | 触发条件 | 调度策略 |
| --- | --- | --- | --- |
| `F2_UPSTREAM_RATE_LIMIT` | 远程 API 风控/限流 | 明确 429、403、风控标记或验证信号 | 开启全局冷却 |
| `F2_COOKIE_OR_VERIFY_REQUIRED` | 远程认证/验证 | 明确登录、验证码、challenge、unauthorized | 开启全局冷却 |
| `F2_UPSTREAM_TIMEOUT` | 远程 API/网络 | 异常类型、消息或诊断明确 timeout | 普通瞬时退避，不开启全局冷却 |
| `F2_UPSTREAM_UNAVAILABLE` | 远程 API/网络 | 空响应、连接失败、无 HTTP 状态且无法证明风控 | 指数退避，不开启全局冷却 |
| `INVALID_AUTHOR_ID` | 任务配置/远程明确拒绝 | profile `statusCode=2` 或 `UserId不合法` | 不按普通远程失败循环，提示用户修正 |
| `UPSTREAM_SCHEMA_ERROR` | 远程响应契约 | 有响应但 JSON 结构缺失/类型不符 | 普通失败，保留 schema 诊断 |
| `F2_PROTOCOL_ERROR` | 本应用 | 子进程无结构化错误、结果文件损坏、协议字段缺失 | 高优先级告警，不伪装成远程问题 |

### 4.2 Python 侧分类

1. 新增安全分类函数，输入异常、safe message 和 page/profile diagnostics，输出错误码。
2. 分类顺序固定为：Cookie/验证信号 -> 明确 HTTP 风控 -> 无效 UID -> timeout -> 连接/空响应 -> schema/protocol。
3. `APIRetryExhaustedError` 只有在 diagnostics 明确存在 429/403/风险信号时才归入 rate limit；否则归入 `F2_UPSTREAM_UNAVAILABLE`。
4. 安全日志只输出状态摘要、异常类型、页码、cursor、状态码和字段名，不输出 Cookie 或原始响应体。
5. profile `statusCode=2` 与 `UserId不合法` 单独输出 `INVALID_AUTHOR_ID`。

### 4.3 Java 侧协议与调度

1. `DouyinIncrementalFetchService` 接受上述结构化错误码，并把未识别/缺字段的子进程输出包装为 `F2_PROTOCOL_ERROR`。
2. `CollectJobWorker` 仅对 rate limit 和 Cookie/验证错误调用全局冷却延迟。
3. `F2_UPSTREAM_TIMEOUT`、`F2_UPSTREAM_UNAVAILABLE` 使用现有普通重试退避；不得调用全局冷却 API。
4. `INVALID_AUTHOR_ID` 使用非风控错误路径，保留明确消息，并避免制造无意义的高频重试。
5. 日志字段统一包含：`errorCode`、`faultDomain`、`retryable`、`cooldownApplied`、`taskId`、`runId`、`attempt`。

### 4.4 前端/运维可观测性

本次不重做页面，但后端返回的错误消息必须可直接区分：

- 远程限流/风控；
- 远程网络超时或不可用；
- 作者 UID 无效；
- 本应用协议或代码错误。

同时保留现有聚合进度与逐条下载队列展示，避免把本次抓取错误误显示成下载失败。

## 5. 修改文件范围

### 必改

- `backstage/src/main/docker/buildx/script/douyin.py`
- `backstage/src/main/java/com/flower/spirit/service/DouyinIncrementalFetchService.java`
- `backstage/src/main/java/com/flower/spirit/service/CollectJobWorker.java`
- `backstage/src/main/java/com/flower/spirit/service/CollectDataService.java`

### 测试

- `backstage/src/test/python/test_douyin_incremental.py`
- `backstage/src/test/java/com/flower/spirit/service/DouyinIncrementalFetchServiceTest.java`
- `backstage/src/test/java/com/flower/spirit/service/CollectJobWorkerTest.java`
- 必要时新增 `CollectDataService` 的无效 UID 分类测试。

### 不改

- 数据库 schema、迁移脚本和媒体数据；
- 下载事务、PostgreSQL 连接配置；
- f2 依赖版本，除非新增诊断必须且有独立证据。

## 6. 测试与验收标准

### Python 分类矩阵

- `APIResponseError("page request timed out")` -> `F2_UPSTREAM_TIMEOUT`；
- `APIRetryExhaustedError` + 明确 429/风控诊断 -> `F2_UPSTREAM_RATE_LIMIT`；
- `APIRetryExhaustedError` + `NoneType`/无 status -> `F2_UPSTREAM_UNAVAILABLE`；
- profile `statusCode=2`/`UserId不合法` -> `INVALID_AUTHOR_ID`；
- JSON 字段缺失 -> `UPSTREAM_SCHEMA_ERROR`；
- 没有 `stream-vault-fetch-error=` 或结果文件损坏 -> `F2_PROTOCOL_ERROR`。

### Java worker

- 只有 rate limit/验证错误触发全局冷却；
- timeout/unavailable 不触发全局冷却且保留普通重试；
- invalid UID 记录明确错误且不进入风控冷却；
- 结构化错误字段缺失时记录 protocol error；
- SQLite 与 PostgreSQL 测试行为一致。

### 生产验收

部署后观察至少一个完整调度周期：

1. 日志能看到 `faultDomain=REMOTE_API` 或 `faultDomain=APPLICATION`；
2. 非风控超时不会出现 `platform global risk cooldown`；
3. 明确 429/验证信号仍会触发全局冷却；
4. 无效 UID 能直接定位到具体 task；
5. 抓取成功后才出现下载规划/下载队列日志；
6. 没有新增数据库异常、队列状态错乱或重试次数异常消耗。

## 7. 发布与回滚

1. 从当前已合并代码创建新分支，提交本次分类修复和测试；
2. 本地运行 Python 单测、Java 目标测试和编译；
3. 创建 PR，等待 CI 通过后合并；
4. 构建并发布新镜像，先在演习 compose 验证；
5. 生产仅替换镜像，不执行数据库迁移；
6. 若异常增加，回滚到上一镜像即可，数据库无需回滚。

## 8. 残余风险

- 抖音可能在没有公开 429/403 的情况下静默丢弃响应，因此“上游不可用”和“隐性风控”仍可能无法百分之百区分；这类情况必须显示为 `F2_UPSTREAM_UNAVAILABLE`，不能伪装成已确认的限流。
- 当前全局冷却仍是单 JVM 进程内状态，跨容器实例不共享，符合现有部署约束。
- 生产 DB 快照早于日志，数据库健康仍需上线后用 PostgreSQL 指标和应用日志确认。

## 9. 本地实现记录

已完成：

- Python 将明确 403/429/风控信号、超时、空响应/重试耗尽、无效作者 UID 分层；
- 结构化错误增加 `faultDomain`、`retryable`、`cooldownApplied` 诊断字段；
- Java 将缺失/损坏的 f2 错误协议归类为 `F2_PROTOCOL_ERROR`；
- `INVALID_AUTHOR_ID` 与 `F2_PROTOCOL_ERROR` 终止当前 job，不制造重复重试；
- 非风控上游错误保持普通重试，不读取全局冷却剩余时间；
- 增加 Python、Java worker、Java 协议和 SQLite 事务回归测试。

验证结果：

- Python `test_douyin_incremental.py`：60 passed；
- Java 目标测试：21 passed；
- SQLite 队列事务测试：12 passed；
- Maven 全量测试：448 tests，0 failures，0 errors，1 skipped；
- 未修改数据库 schema 或迁移脚本。
