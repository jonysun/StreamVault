# 抖音作者作品游标回填与上游错误治理设计

## 背景

作者作品抓取当前以“每轮选满固定数量的本地未知作品”为目标。该语义可以最终补齐历史作品，但每轮都从第一页重新扫描。作者已知作品越多，单轮需要跳过的历史前缀越长，生产日志已经出现请求到第 25 页后才失败的情况。

同时，上游返回 `status_code=0` 但不包含 `aweme_list` 时，当前应用会将 f2 可作为空终止页处理的响应升级为 `UPSTREAM_SCHEMA_ERROR`；普通 `APIResponseError` 还可能因为调用方固定文案包含 `timed out` 而被误分为超时。这些问题共同放大了请求量、失败率和 ERROR 日志数量。

## 目标与成功标准

1. 对任一固定时刻的作者公开可访问作品集合，经过有限轮成功抓取和下载后，本地成功下载的唯一作品数 `n` 不小于远端符合条件的唯一作品数 `m`。
2. 首次抓取按 `omaxcur`，后续每轮按 `maxcur` 选择未知作品；新发布作品优先，剩余额度继续历史回填。
3. 作者回填期间新增作品不会遗漏；作者删除或私密作品不触发本地反向删除，因此允许 `n > m`。
4. 每轮不再从第一页扫描全部已知历史前缀。对于 1000 个作品、首次 80、后续 20 的典型任务，历史主体应接近顺序扫描一次，而不是累计重复扫描上千页。
5. 抓取失败、进程退出或数据库事务失败不得推进回填游标。
6. SQLite 与 PostgreSQL 使用相同业务语义和事务边界，不引入数据库专用抓取逻辑。
7. 上游、任务配置、认证风控和应用协议错误可从错误码与日志中明确区分。

这里的 `m` 指远端当前公开可访问、未被用户主动屏蔽且能够返回下载元数据的唯一作品。上游永久拒绝访问或永久不返回媒体信息的作品不属于客户端可保证下载的集合。

## 已评估方案

### 方案一：恢复 known boundary 后直接结束

优点是请求最少。缺点是 known boundary 后仍可能存在尚未下载的历史作品，会破坏“每轮补一批直到全部完成”的既有需求，因此不采用。

### 方案二：保留每轮从第一页扫描

优点是不新增持久化状态，并且固定集合最终可补齐。缺点是请求量随已知作品数近似二次增长，正是当前深分页和上游失败被放大的主要原因，因此不采用。

### 方案三：最新区扫描与持久化历史游标结合

每轮从第一页检查新发布作品，在确认进入已知旧区后切换到上次保存的历史游标。该方案同时满足最新作品优先、历史最终补齐和请求量近似线性增长，作为本次实现方案。

## 持久化状态

在 `biz_collect_data` 增加以下字段：

- `backfill_cursor VARCHAR(64)`：下一轮历史回填的安全起始游标；
- `backfill_complete INTEGER NOT NULL DEFAULT 0`：当前作者的历史公开列表是否已经扫描到末尾；
- `backfill_source_id VARCHAR(255)`：游标所属的规范化作者 ID。

字段由现有跨数据库 schema initializer 幂等创建，并加入 SQLite schema preflight。实体层使用可同时映射 SQLite 与 PostgreSQL 的字符串和整数类型，不使用 PostgreSQL JSONB、SQLite PRAGMA 或数据库专用布尔表达式。

如果任务当前作者 ID 与 `backfill_source_id` 不同，抓取请求必须忽略旧游标并从头建立新作者进度。只有新的抓取计划成功持久化后才写入新的 source ID。

## 抓取协议

Java 到 Python 的请求增加：

- `backfill_cursor`；
- `backfill_complete`。

Python envelope 增加：

- `backfillCursor`：下一轮安全游标；
- `backfillComplete`：是否确认到达历史末尾；
- diagnostics 中的 `phase`、`headPagesFetched`、`backfillPagesFetched` 和空页兼容原因。

`lastCursor` 保留为本次最后一次远程响应游标，仅用于诊断；不能再承担持久化续传语义。

## 分页算法

### 首次抓取

1. 从游标 `0` 按时间倒序读取作品。
2. 跳过数据库和当前响应中已知的重复 ID，选择最多 `omaxcur` 个未知作品。
3. 如果配额在完整页末达到，安全游标使用 `next_cursor`。
4. 如果配额在页中途达到且该页仍有未选择未知作品，安全游标保持为该页请求游标。下一轮会重新读取该页，通过已知 ID 跳过已经选择的作品，避免页尾遗漏。
5. 上游明确到达末尾时设置 `backfillComplete=true`。

### 后续增量抓取

1. 每轮始终从游标 `0` 进入 HEAD 阶段，优先选择新发布的未知作品。
2. watermark 与 known boundary 只用于判定 HEAD 阶段已经进入稳定已知旧区，不得作为整个任务的成功终止条件。
3. HEAD 阶段尚未用完本轮 `maxcur` 配额，并且历史回填未完成时，切换到持久化 `backfill_cursor`。
4. BACKFILL 阶段按相同去重和安全游标规则继续选择历史未知作品。
5. 新作品与历史作品合计不超过本轮配额。
6. 如果新作品单轮填满配额，本轮不推进历史游标。对于固定作品集合，下一轮这些新作品已成为已知项，历史回填仍会继续；如果作者永久以不低于本地处理能力的速度发布作品，任何固定批量系统都无法在数学上追平，系统应通过队列积压指标明确展示这一状态。

### 历史完成后的运行

`backfill_complete=true` 后，普通增量任务只执行 HEAD 扫描。显式 AUDIT 仍保持全量扫描能力，用于发现旧作品重新公开、文件缺失或历史下载需要修复的情况，并且不复用普通回填完成标记作为审计停止条件。

## 事务与恢复

抓取 envelope、run items、水位和回填状态由同一个 `storeFetchPlan` 新事务写入：

1. 校验 run 仍处于当前可提交状态；
2. 写入本轮候选项；
3. 更新抓取计数和 stop reason；
4. 最后更新 watermark、`backfill_cursor`、`backfill_complete` 和 `backfill_source_id`；
5. 任一步失败则整体回滚。

远程抓取失败不会产生 envelope，因此不改变游标。下载在独立队列中失败也不会回退抓取游标；已持久化的下载 item 继续使用现有重试和审计修复机制。

## 上游响应兼容与错误分类

### status-only 空终止页

满足以下全部条件时作为兼容空页：

- 响应是对象；
- `status_code=0`；
- 缺少 `aweme_list`；
- 没有 401、403、429、验证码、登录或风控信号。

HEAD 阶段遇到该响应表示没有更多头部作品，但如果历史回填未完成仍可切换到 BACKFILL。BACKFILL 阶段遇到该响应表示历史终止，设置 `backfillComplete=true`。diagnostics 记录 `STATUS_ONLY_EMPTY_PAGE`，但任务不失败。

真正存在非零状态、字段类型损坏或无法验证的协议结构时仍抛出明确错误，不静默吞掉。

### 异常分类

- 明确 `APITimeoutError`、`TimeoutError` 或底层 timeout 证据：`F2_UPSTREAM_TIMEOUT`；
- `APIConnectionError`、`APIUnavailableError` 或空响应重试耗尽：`F2_UPSTREAM_UNAVAILABLE`；
- 429 或 `APIRateLimitError`：`F2_UPSTREAM_RATE_LIMIT`；
- 401、403、验证或登录信号：`F2_COOKIE_OR_VERIFY_REQUIRED`；
- 普通 `APIResponseError`：`F2_UPSTREAM_RESPONSE_ERROR`；
- 本地命令协议或结果 envelope 损坏：`F2_PROTOCOL_ERROR`，fault domain 为 `APPLICATION`；
- 作者 ID 非法：`INVALID_AUTHOR_ID`，fault domain 为 `TASK_CONFIGURATION`。

固定的用户安全文案只负责展示，不参与异常类型推断。日志只记录异常类型、HTTP 状态和脱敏摘要，不记录 Cookie 或原始敏感响应体。

## 请求节流与日志等级

保留现有单 fetch worker 和页内延迟，同时增加单容器内的 Douyin 抓取任务最小间隔。该间隔与风控冷却分离：

- 普通节流限制连续任务启动频率；
- 429、403 或验证信号继续触发现有全局风险冷却；
- 普通连接和响应错误不触发风险冷却；
- 不跨容器共享节流状态，符合现有部署约束。

已成功创建下一次 retry 的错误记录为 WARN，并保留结构化摘要但不打印完整堆栈。只有不可重试错误、重试耗尽或队列状态写入失败记录为 ERROR。

## 删除、重复与下载失败

- 作者删除或私密作品：本地历史文件和记录保留，不因为远端列表缩短而删除；
- 同页或跨页重复 ID：只选择一次且只占一个配额；
- 页中途达到配额：不越过尚未选择的页尾未知项；
- 下载瞬态失败：由下载 item 自身重试，不重新抓取整段作者列表；
- 下载最终失败或本地文件缺失：由 AUDIT 重新发现并生成 `AUDIT_REPAIR`，不能被普通已知 ID 判断永久掩盖；
- 用户主动屏蔽作品：继续遵循屏蔽语义，不进入下载完成分母。

## 测试设计

### Python 分页测试

- 1000 个作品，首次 80、后续每轮 20，最终选择全部唯一 ID；
- 回填过程中头部新增 5 个作品，下一轮优先选择且历史仍继续；
- 页中途达到配额后从当前页安全恢复；
- 完整页达到配额后推进到下一页；
- 同页和跨页重复不占额度；
- status-only 空页分别在 HEAD 与 BACKFILL 阶段正确处理；
- audit 忽略普通回填完成状态；
- APIResponseError 不再被固定文案误判为超时。

### Java 与数据库测试

- request/command/envelope 新字段完整传递；
- SQLite 与 PostgreSQL schema inspection 均包含新字段；
- 作者 ID 变化忽略旧游标；
- `storeFetchPlan` 成功时原子推进游标；
- 事务失败时 run items、水位和游标全部回滚；
- queued retry 使用 WARN 路径，terminal failure 使用 ERROR 路径；
- 普通节流不触发风险冷却，风险错误仍使用全局冷却。

### 完整验证

- 运行 Python `test_douyin_incremental.py`；
- 运行抓取服务、命令协议、schema、队列事务和 worker 定向测试；
- 运行 Maven 全量测试；
- 运行 `git diff --check`；
- 检查差异不包含生产数据库、生产日志、Cookie 或 compose 密码。

## 非目标

- 不删除生产端或本地已有媒体文件；
- 不修改当前每轮任务的 `omaxcur/maxcur` 用户配置语义；
- 不增加抓取并行度；
- 不增加跨容器协调服务；
- 不改变非抖音收藏模式；
- 不以本次修复自动触发生产部署、数据库维护或全量审计。
