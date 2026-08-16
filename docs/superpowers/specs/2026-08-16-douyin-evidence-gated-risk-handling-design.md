# 抖音请求证据分级与风险处理设计

## 背景

当前生产环境同时出现两类抖音失败：单作品详情接口反复返回 HTTP 200 空响应，以及部分请求返回 HTTP 403。前者是无法确认原因的上游异常，后者是明确的认证、验证或风控拒绝。系统已将两者拆分为详情软退避和全局风险冷却，但作者资料、作者作品列表请求的结构化 F2 诊断在 Java 的任务冷却日志中没有完整呈现。因此，运维人员无法判定一次全局冷却是否由 HTTP 403、明确验证码字段或模糊文本触发。

本设计只覆盖抖音作者资料、作者作品列表、单作品详情和媒体下载链路，不改动 YouTube 或其他平台。

## 目标

- 每一次实际抖音 HTTP 请求都生成可脱敏审计的请求证据。
- 将远端认证/验证/限流、上游可用性、网络、协议和应用运行时错误明确区分。
- 只有确认的风险证据才能开启 30 分钟可配置的全局冷却。
- 模糊文本或无 HTTP 证据的失败不会被误报为全局 Cookie 风控。
- 冷却、重试和任务状态保留可解释的错误码和分类依据。
- 日志、数据库错误消息和后台状态中不泄露 Cookie、签名参数、完整 URL 或响应正文。

## 非目标

- 不新增错误事件数据库表、历史页或跨容器共享冷却状态。
- 不改变收藏任务的分页、回填、下载排队或已有作品保留语义。
- 不通过解析或保存远端响应正文来判断风险。

## 证据模型

Python F2 命令对作者资料、作者作品列表和单作品详情复用同一份脱敏证据模型。每个 HTTP 尝试包含：

- `endpoint`：HTTP 方法、origin、路径、查询参数名称和是否存在签名参数；不包含参数值。
- `attemptHistory`：最多两次尝试的 HTTP 状态、响应长度、内容类型、空响应标记、错误类别、异常类型和耗时。
- `lastRequest`：最终一次尝试，作为现有字段的兼容别名。
- `upstreamStatus`：JSON 响应中的抖音业务状态码（如存在）。
- `responseSummary`：仅包含顶层键名、字段类型和受限长度的状态文本。
- `classificationReason`：机器可读的分类依据，例如 `HTTP_STATUS_403`、`STRUCTURED_LOGIN_STATUS_REQUIRED`、`STATUS_TEXT_MARKER_LOGIN`、`EMPTY_HTTP_RESPONSE` 或 `NETWORK_TIMEOUT`。
- `confidence`：`CONFIRMED`、`SUSPECTED` 或 `NONE`。

所有字段在 Python 输出前和 Java 日志前都必须继续经过现有 Cookie 脱敏流程。

## 错误分类和处理

| 证据 | 错误码 | 域 | 冷却与重试 |
| --- | --- | --- | --- |
| HTTP 401 或 403 | `F2_COOKIE_OR_VERIFY_REQUIRED` | `REMOTE_API` | `CONFIRMED`；开启全局风险冷却 |
| HTTP 429 或明确限流状态 | `F2_UPSTREAM_RATE_LIMIT` | `REMOTE_API` | `CONFIRMED`；开启全局风险冷却 |
| 明确 `captcha`、`verify_status`、`verify_required` 或 `login_status` 失败字段 | `F2_COOKIE_OR_VERIFY_REQUIRED` | `REMOTE_API` | `CONFIRMED`；开启全局风险冷却 |
| 仅状态文本或异常消息中出现 login、verify、captcha、challenge，而没有上述证据 | `F2_AUTH_OR_VERIFY_SUSPECTED` | `REMOTE_API` | `SUSPECTED`；该作者任务短退避，不开启全局冷却 |
| 两次 HTTP 200 空响应 | `F2_UPSTREAM_SOFT_BLOCK` | `REMOTE_API` | 详情接口 5 分钟软退避；作者列表任务 5 分钟退避；不开启全局风险冷却 |
| HTTP 408、客户端超时 | `F2_UPSTREAM_TIMEOUT` | `NETWORK` | 常规重试；不冷却 |
| DNS、代理、连接或传输异常 | `F2_NETWORK_ERROR` | `NETWORK` | 常规重试；不冷却 |
| HTTP 503 或明确服务不可用 | `F2_UPSTREAM_UNAVAILABLE` | `REMOTE_API` | 常规重试；不冷却 |
| JSON 无效、响应结构不符、非零业务状态且无风险证据 | `UPSTREAM_SCHEMA_ERROR` | `REMOTE_API` | 常规重试；记录业务状态；不冷却 |
| F2 进程、隔离日志目录、命令/临时文件协议失败 | `F2_RUNTIME_ERROR` 或 `F2_PROTOCOL_ERROR` | `APPLICATION` | 常规重试；不冷却 |
| 无效作者标识 | `INVALID_AUTHOR_ID` | `TASK_CONFIGURATION` | 终止该任务；不冷却 |
| 作品 HTTP 404 | `F2_WORK_UNAVAILABLE` | `REMOTE_API` | 正常终结该作品；不冷却 |

`F2_COOKIE_OR_VERIFY_REQUIRED` 和 `F2_UPSTREAM_RATE_LIMIT` 是唯一可调用 `PlatformCookieService.reportRisk` 的错误码。`F2_AUTH_OR_VERIFY_SUSPECTED` 必须在后台和日志中可见其“未确认”性质，不能写入全局风险时间戳。

## 调用链与日志

1. `InstrumentedDouyinCrawler` 保存每一次 HTTP 尝试的安全证据。
2. `douyin.py` 按证据优先级产出结构化错误 JSON，附上 `classificationReason` 和 `confidence`。
3. `DouyinIncrementalFetchService` 解析并保留该诊断，生成受限长度、已脱敏的 `CollectFetchException` 消息。
4. `CollectDataService` 只对确认风险调用 `reportRisk`；其他错误保持任务级重试语义。
5. `CollectJobWorker` 在任务冷却或失败日志中输出 `event=F2_FETCH_FAILURE`、任务/运行标识、错误码、域、重试性、冷却范围、分类依据和安全证据摘要。
6. 详情下载链路输出同一格式，以便横向比较作者列表和单作品接口的远端表现。

出现全局冷却时，日志必须能直接回答：哪个链路的哪个端点、哪一次 HTTP 状态或结构化字段、何时、以什么规则启动了全局冷却。

## 测试矩阵

- Python：401、403、429、200 空响应、408、超时、网络异常、503、无效 JSON、非零业务状态、结构化登录/验证字段、仅模糊文本。
- Python：验证所有诊断中不存在 Cookie、签名值或完整 URL。
- Java：确认风险才调用 `reportRisk`；疑似风险、软阻断、网络、协议错误均不调用。
- Java：作者列表失败进入任务冷却/重试时保留分类依据和受限证据摘要。
- Java：详情链路与作者列表链路输出一致的风险域和冷却范围。
- 全量回归：既有抖音增量抓取、快照下载、队列和 PostgreSQL 测试必须继续通过。

## 验收标准

- 生产日志中的每次“全局冷却”都包含确认性依据，例如 `HTTP_STATUS_403` 或 `STRUCTURED_LOGIN_STATUS_REQUIRED`。
- 仅含模糊文本的失败显示为 `F2_AUTH_OR_VERIFY_SUSPECTED`，不造成全局暂停。
- 200 空响应不再显示为全局风险冷却。
- 使用同一条失败日志即可区分远端 API、网络、任务配置和应用自身错误。
