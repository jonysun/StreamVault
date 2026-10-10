# DTK Admin Watchlist 同步设计

日期：2026-10-11

状态：已获用户确认，待实现计划

## 1. 目标

在系统设置中增加独立的 DTK Admin 配置和 watchlist 管理能力：

- 使用独立的自建 DTK Admin Base URL 和 Admin Key。
- 可选地在新增抖音收藏作者后，将作者同步到 DTK watchlist。
- 支持手动增量同步和完全同步。
- 增量同步只新增 DTK 缺失的本地收藏作者。
- 完全同步在补齐缺失作者后，删除 DTK 中不存在本地对应收藏任务的作者。
- 所有同步均为单向本地到 DTK，不得新增、删除、暂停或修改本项目内的收藏任务。
- 为 TikTok 保留相同的跨平台抽象，首版只启用现有抖音收藏作者来源。

## 2. 范围与硬约束

### 2.1 本地作者来源

同步候选集合只来自 `biz_collect_data` 中的收藏任务，不读取 `biz_author_profile`，也不把普通作品中偶然出现的作者加入 DTK 关注。

候选任务包括已启用和已暂停任务。暂停只影响本地抓取调度，不代表作者从本地收藏集合中消失。只有删除本地收藏任务后，作者才可能在下一次完全同步中被认定为远程多余项。

每个候选项统一为：

```text
platform: douyin | tiktok
kind: author
target_id: 平台作者标识
```

同一平台和 `target_id` 去重。无法从收藏任务来源安全提取作者标识时，增量和自动同步跳过并报告；完全同步遇到任何无法解析的任务时禁止执行删除阶段，避免因本地集合不完整而误删远程数据。

### 2.2 同步方向

本地收藏任务是唯一事实来源。任何同步模式都不得反向修改本地收藏任务、作者档案或抓取状态。

- 自动同步：只处理刚成功保存的收藏任务作者，只新增或确认 DTK 项，不删除。
- 增量同步：扫描全部本地收藏任务作者，只新增 DTK 缺失项，不删除任何 DTK 项。
- 完全同步：先完成与增量同步相同的补齐，再删除 DTK 中属于 `douyin/author` 或 `tiktok/author` 且本地没有对应收藏任务的项。不删除 DTK 的 `content` 项，也不删除其他平台项。

## 3. 组件设计

### 3.1 配置

在 `ConfigEntity` 和 `biz_config` 增加：

- `dtkAdminBaseUrl`：自建 DTK 的管理 API 根地址。
- `dtkAdminApiKey`：独立 Admin Key，不复用普通 DTK 解析 API Key。
- `dtkAdminWatchlistSyncEnabled`：新增收藏任务后是否自动同步。

Admin Key 保存时空值表示保留旧值。配置查询和保存响应不得回显明文 Key；日志只记录脱敏后的 URL 和 Key 指纹/末四位。

PostgreSQL 增加版本化迁移；SQLite/开发环境沿用现有 Hibernate schema update 机制，并通过启动 schema 检查保证字段可用。

### 3.2 DtkAdminClient

新增独立客户端，职责如下：

- 规范化 Base URL，并拼接 `/api/v1/admin` 路径。
- 使用 `X-API-Key` 请求头。
- 使用现有 DTK 超时配置作为默认超时，连接和读取超时统一分类。
- 调用 watchlist 接口：
  - `GET /api/v1/admin/watchlist?platform=...&kind=author&limit=500&offset=...&lang=zh`
  - `POST /api/v1/admin/watchlist?lang=zh`
  - `DELETE /api/v1/admin/watchlist/{entry_id}?lang=zh`
- 解析 DTK 的 `DtkResponse` 信封，保留 HTTP 状态、稳定错误码、消息和 `meta.request_id`。
- 对 watchlist 列表的 `data` 做明确的 DTO 解析；若远程返回结构不符合预期，视为分页不完整，禁止完全同步删除。
- 不记录 Admin Key、完整授权头或可能包含敏感请求体的日志。

POST 请求体固定使用 DTK 文档中的字段：

```json
{
  "platform": "douyin",
  "kind": "author",
  "target_id": "sec_user_id"
}
```

可选的 `label` 只使用本地收藏任务名称，名称为空时省略；不主动覆盖 DTK 的 interval/pages 默认值。

### 3.3 DtkAdminWatchlistService

新增服务负责本地集合构建、去重、差异计算和同步编排。服务内部使用单线程执行器，保证同一时间只有一个管理同步操作访问 DTK，避免手动按钮和自动同步并行造成重复请求。

自动同步注册在收藏任务事务提交之后，远程失败不能回滚本地收藏任务。重复的自动同步请求应合并同一 `platform + target_id`，避免短时间内重复 POST。

手动同步采用后台任务方式执行，接口立即返回任务标识。同步状态持久化到独立的 `biz_dtk_admin_sync_job` 表，至少记录：

- 任务 ID、模式（AUTO/INCREMENTAL/FULL）、状态、创建/开始/结束时间；
- 扫描数、新增数、删除数、跳过数、失败数；
- 最近错误分类、错误码、消息和 DTK request ID。

PostgreSQL 迁移固定创建以下字段：`id VARCHAR(64) PRIMARY KEY`、`mode VARCHAR(16)`、`status VARCHAR(16)`、`scanned_count INTEGER`、`added_count INTEGER`、`deleted_count INTEGER`、`skipped_count INTEGER`、`failed_count INTEGER`、`error_domain VARCHAR(32)`、`error_code VARCHAR(128)`、`error_message TEXT`、`request_id VARCHAR(64)`、`created_at TIMESTAMP`、`started_at TIMESTAMP`、`finished_at TIMESTAMP`。计数默认值为 0，状态初始为 `QUEUED`。SQLite 由 Hibernate schema update 创建相同字段。

不建立按作者的本地镜像表；每次增量/完全同步都以当前本地收藏任务集合和 DTK 当前分页结果重新计算差异，避免本地状态与远程状态长期漂移。

### 3.4 管理端点与设置页

新增管理端点：

- `POST /admin/api/saveDtkAdminConfig`
- `POST /admin/api/testDtkAdmin`
- `POST /admin/api/dtkAdminWatchlistSync/incremental`
- `POST /admin/api/dtkAdminWatchlistSync/full`
- `GET /admin/api/dtkAdminWatchlistSync/status/{jobId}`

设置页在普通 DTK 解析节点池旁增加独立的“DTK 管理配置”区域，包含：

- Admin Base URL；
- Admin Key 输入框（空值表示不修改，页面不回显）；
- 自动同步收藏作者开关；
- 测试连接、增量同步、完全同步按钮；
- 最近一次同步状态和计数。

完全同步按钮必须显示明确的删除提示，确认后才提交；运行中禁止重复启动同模式任务。普通 DTK 节点检测不应检测或覆盖 Admin 配置。

## 4. 同步流程

### 4.1 自动新增

1. 收藏任务在本地事务中保存。
2. 事务提交后，若开关开启且平台为抖音/TikTok，提取并规范化作者 ID。
3. 将作者加入 Admin 单线程队列；队列先查询/利用当前任务去重信息，随后 POST 缺失项。
4. 失败时按网络、认证、限流、远程业务错误分类并重试有限次数；最终失败只记录同步任务状态，不影响本地收藏。

### 4.2 增量同步

1. 读取所有抖音/TikTok 收藏任务，包含暂停任务，构建本地去重集合。
2. 按平台和 `kind=author` 分页读取 DTK watchlist，直到返回空页或不足 `limit` 的末页。
3. 仅对本地集合中远程缺失的作者执行 POST。
4. 不执行 DELETE，不修改任何本地记录。

### 4.3 完全同步

1. 完成与增量同步相同的本地集合解析和远程完整分页读取。
2. 若本地来源存在无法解析项、远程分页失败、远程响应结构不完整或鉴权失败，任务失败并立即结束，不执行任何删除。
3. POST 所有远程缺失的本地作者。
4. DELETE 远程多余的同平台 `author` 项，使用远程返回的 `entry_id`。
5. 删除失败继续处理其他条目，最终状态记录逐项失败数量和错误详情；本地数据始终不变。

## 5. 错误、重试与安全

- 401/403：配置或权限错误，不进行无意义重试，前端提示 Admin Key/权限范围。
- 429：读取 `error.retry_after`（若有），按上限等待后重试。
- 408、连接/读取超时、502、503：指数退避有限重试。
- 400/404 等请求或目标错误：记录具体 DTK 错误码，继续处理可独立处理的其他作者；完全同步删除阶段遇到列表请求错误则不启动删除。
- 自动同步不得在收藏保存事务内发起网络请求。
- 所有错误详情可在同步状态中查看，但敏感请求头和 Key 必须脱敏。
- 完全同步是破坏性远程操作，必须由显式按钮触发并二次确认；没有“自动完全同步”开关。

## 6. TikTok 兼容性

本地候选提取、DTK 请求体、远程差异键和删除条件都使用平台适配器，不在抖音代码中写死全部流程。首版实现抖音 `sec_uid` 提取；TikTok 适配器保留接口和 `platform=tiktok` 分支，待项目现有 TikTok 收藏来源格式确定后接入，不影响抖音功能。

## 7. 测试与验收

单元测试至少覆盖：

- 抖音收藏来源规范化和无效来源跳过；
- TikTok 分支的接口占位和平台字段映射；
- DTK watchlist 多页合并、空页/短页结束和结构异常；
- 增量同步只 POST、不 DELETE；
- 完全同步只删除多余的同平台 author 项；
- 本地来源解析不完整时完全同步不删除；
- DTK 失败不改变本地收藏任务；
- 重复自动同步合并；
- Admin Key 不出现在日志、响应或错误文本中。

集成测试使用可控 HTTP stub 验证 `X-API-Key`、POST/DELETE 请求体、分页、重试和状态持久化。验收标准：收藏任务在 Admin 不可用时仍能成功保存；增量同步不删除远程手工关注；完全同步只删除明确属于本地作者集合之外的同平台 author 项；任务状态能在页面看到扫描/新增/删除/失败计数。

## 8. 后续扩展

`DtkAdminClient` 预留统一请求和错误模型，后续可在同一设置区域增加身份池、代理、诊断、暂停 watchlist 等 Admin 操作，不与普通解析节点池耦合。
