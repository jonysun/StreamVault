# 抖音下载、账号不可用与 HLS 可靠性设计

## 背景

生产日志显示四类问题同时存在：抖音作品媒体地址返回 HTTP 404、作者 profile/list 接口返回不可用结果、F2 诊断 JSON 被 Java 日志中间截断，以及 HLS 任务使用进程内 1000 条队列导致任务被拒绝。目标是区分远端事实与本地故障，确保删除/停用操作可追溯、可恢复，并且不删除已有本地数据。

## 既有约束与复用

- 继续使用 PostgreSQL 作为运行数据库，并通过 Flyway 增量迁移变更结构。
- 复用已有 `biz_blocked_work` 黑名单，不创建第二套作品黑名单。
- 黑名单身份为 `platform + workid + worktype`，现有收藏规划和单链接入口已经支持查询。
- 作者任务自动停用只改变收藏任务状态和 Quartz 调度，不删除作者资料、作品记录或文件。
- Cookie、`X-Bogus`、`msToken` 等敏感值只能记录是否存在、长度、键名或脱敏值。

## 设计

### 1. 作品 404 与下载中心

F2 作品详情请求由 `WorkIngestService` 经抖音适配器发起，底层调用 `douyin.py fetch_work_data`。远端返回 HTTP 404 时使用稳定错误码 `F2_WORK_UNAVAILABLE`，错误文案为“可能服务器端已删除或媒体地址已失效”。该错误属于远端资源不可用，不再与连接超时、DNS 失败混用。

下载中心记录：

- 平台、作品 ID、作者和标题；
- 原始作品链接，抖音缺失时由作品 ID 生成 `https://www.douyin.com/video/{workId}`；
- `F2_WORK_UNAVAILABLE`、用户可读原因、请求路径、HTTP 状态和安全诊断；
- 最近一次失败时间和重试次数。

HTTP 404 不自动进入普通网络重试循环，但保留手动重试。下载中心增加“删除并拉黑”操作：在同一事务内写入已有黑名单，并将所有尚未运行的同平台同作品下载项转为 `SKIPPED_BLOCKED`。领取前、收藏规划、直接下载和手动重试都重新检查黑名单。正在运行的下载不强杀，由其完成或按租约恢复后再被拦截。已有本地视频、图文和文件不删除。

### 2. 作者账号不可用

收藏抓取链路为：

`CollectJobWorker -> CollectDataService -> DouyinIncrementalFetchService -> CommandUtil.f2IncrementalFetch -> douyin.py fetch_douyin_list_incremental`。

F2 先请求 `/aweme/v1/web/user/profile/other/` 校验作者，再请求 `/aweme/v1/web/aweme/post/` 分页取作品。只有 profile 响应出现明确的停用、封禁、账号不存在信号，或协议层明确返回账号不可用，才产出 `ACCOUNT_DEACTIVATED`、`ACCOUNT_BANNED` 或统一的 `ACCOUNT_UNAVAILABLE`。普通 403 风控仍保持 `F2_COOKIE_OR_VERIFY_REQUIRED`，不能误判为删号。

账号不可用时：

1. 当前运行终止并记录远端账号状态、原因、接口和 HTTP 证据。
2. 自动将收藏任务设为停用，移除 Quartz 调度。
3. 任务页面显示“可能原作者被封禁/删号”，并显示检测时间与原始 UID。
4. 通过现有通知渠道发送任务名、UID、诊断摘要和三个处理选项：仅停止收藏、删除收藏任务、删除收藏任务并删除已有作品。
5. 手动恢复任务时清除远端不可用标记、恢复 `taskenabled` 和 Quartz 调度；恢复动作不删除历史数据。

`INVALID_AUTHOR_ID` 仅用于明确的 UID 格式/参数错误，并保留完整响应摘要，避免把未知 schema 当作封禁。

### 3. F2 诊断与日志

Python 侧保留请求证据：安全 URL 身份、方法、查询键名、尝试次数、HTTP 状态、响应类型、内容类型、正文长度、正文安全摘要、解析分支和异常类型。Java 侧不再对任意 JSON 字符串直接 `substring`。改为构造固定字段的诊断对象，再序列化为合法 JSON；正文使用脱敏后的字段级摘要，并在确有上限时使用完整截断标记，而不是在 JSON 中间截断。

每条 F2 失败日志至少包含：`operation`、`endpoint`、`path`、`method`、`statusCode`、`contentType`、`bodyLength`、`classificationReason`、`faultDomain`、`retryable`、`cooldownScope`、`requestAttempts` 和安全 `responseSummary`。日志不能输出 Cookie 原文、签名值或完整下载 URL 查询串。

### 4. HLS 持久队列

将 `HlsTranscodeService` 的进程内 `ArrayDeque` 改为 PostgreSQL 持久队列表，使用 `video_id` 唯一约束去重。队列字段包括状态、尝试次数、最大尝试次数、可用时间、租约、最后错误和创建/更新时间。入队采用幂等 upsert，队列满不再拒绝任务；暂停 HLS 只阻止领取，不阻止入队。worker 使用数据库租约领取，成功、失败、重试和超时都持久化，应用重启后能够恢复未完成任务。

现有 HLS 状态接口继续提供队列数、运行数、失败数、最近错误和当前运行 ID，并新增持久队列统计。迁移期间保留一次性内存队列兼容读取，发布后通过扫描没有 HLS 文件的视频补齐历史任务。

### 5. API 异常返回

修复 `ApiController.processingVideos` 的异常处理：记录带请求上下文和完整堆栈的结构化错误，并返回错误响应，不再在提交失败时返回“已提交”。该改动不改变成功响应格式。

## 数据库迁移

新增 PostgreSQL Flyway 迁移仅包含 HLS 队列表及必要索引；作品黑名单和收藏任务字段不重复迁移。迁移脚本必须可重复执行、可回滚停用新 worker，并在启动时完成 schema 检查。生产发布前先在 rehearsal 数据库执行迁移、队列重建和一致性查询。

## 测试与验收

- F2 profile/list：403、明确封禁、明确删号、非法 UID、空 schema、网络超时分别得到正确错误码和 fault domain。
- F2 诊断：日志中的诊断字段始终是可解析 JSON，敏感字段不泄露。
- 404：下载中心显示原链接和“可能服务器端已删除”；删除并拉黑后，收藏规划、领取和手动重试均不会重新入队。
- 作者停用：任务自动停止、通知发送、历史数据保留，手动恢复后可再次调度。
- HLS：暂停时可持续入队，重启后可恢复，唯一约束不产生重复，失败按策略重试，队列不因容量达到 1000 而丢任务。
- API：提交异常返回失败状态并带可追踪错误码。

## 非目标与风险

- 不自动删除远端已删除作品对应的本地数据。
- 不把所有 403 解释为 Cookie 过期；远端只能提供状态证据，最终 Cookie/验证码处理仍需运维更新。
- HLS 持久化会增加数据库写入和索引，需要观察 PostgreSQL 锁等待、队列吞吐和磁盘空间。
