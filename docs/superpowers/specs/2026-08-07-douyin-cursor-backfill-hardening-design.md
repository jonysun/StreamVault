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
8. 完整性以作品唯一 ID 集合为准，不根据作者作品总量除以批次数量推算页码；服务器端分页顺序发生变化时，通过重叠扫描和完整校验重新发现移动的作品。

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
- `backfill_verifying INTEGER NOT NULL DEFAULT 0`：是否正在执行到达末尾后的完整性校验；
- `backfill_clean_passes INTEGER NOT NULL DEFAULT 0`：当前连续未发现未知 ID 的完整校验次数；
- `backfill_verified_at TIMESTAMP`：最近一次完成两轮干净校验的时间。

字段由现有跨数据库 schema initializer 幂等创建，并加入 SQLite schema preflight。实体层使用可同时映射 SQLite 与 PostgreSQL 的字符串和整数类型，不使用 PostgreSQL JSONB、SQLite PRAGMA 或数据库专用布尔表达式。

如果任务当前作者 ID 与 `backfill_source_id` 不同，抓取请求必须忽略旧游标并从头建立新作者进度。只有新的抓取计划成功持久化后才写入新的 source ID。

## 抓取协议

Java 到 Python 的请求增加：

- `backfill_cursor`；
- `backfill_complete`。
- `backfill_verifying`；
- `backfill_clean_passes`。

Python envelope 增加：

- `backfillCursor`：下一轮安全游标；
- `backfillComplete`：是否确认到达历史末尾；
- `backfillVerifying`：下一轮是否继续完整校验；
- `backfillCleanPasses`：连续干净校验次数；
- diagnostics 中的 `phase`、`headPagesFetched`、`backfillPagesFetched` 和空页兼容原因。

`lastCursor` 保留为本次最后一次远程响应游标，仅用于诊断；不能再承担持久化续传语义。

## 分页算法

### 首次抓取

1. 从游标 `0` 按时间倒序读取作品。
2. 跳过数据库和当前响应中已知的重复 ID，选择最多 `omaxcur` 个未知作品。
3. 达到配额时，安全游标保持为当前页请求游标，不直接跳到 `next_cursor`。下一轮会重读当前页，通过唯一 ID 跳过已经选择的作品，从而形成至少一页重叠窗口，兼容页边界轻微漂移。
4. 只有在本轮尚未达到配额且当前页已经全部处理完成时，才继续使用 `next_cursor` 请求后续页。
5. 上游明确到达末尾时进入 VERIFY，不直接设置 `backfillComplete=true`；只有连续两轮完整校验干净后才完成。

### 后续增量抓取

1. 每轮始终从游标 `0` 进入 HEAD 阶段，优先选择新发布的未知作品。
2. watermark 与 known boundary 只用于判定 HEAD 阶段已经进入稳定已知旧区，不得作为整个任务的成功终止条件。
3. HEAD 阶段尚未用完本轮 `maxcur` 配额，并且历史回填未完成时，切换到持久化 `backfill_cursor`。
4. BACKFILL 阶段按相同去重和安全游标规则继续选择历史未知作品。
5. 新作品与历史作品合计不超过本轮配额。
6. 如果新作品单轮填满配额，本轮不推进历史游标。对于固定作品集合，下一轮这些新作品已成为已知项，历史回填仍会继续；如果作者永久以不低于本地处理能力的速度发布作品，任何固定批量系统都无法在数学上追平，系统应通过队列积压指标明确展示这一状态。
7. 不使用 profile 作品总量计算下一页或完成轮数。总量只允许作为诊断指标，不能作为完整性依据，因为删除、新增和同数量替换都会使按数量判断失效。

### 到达末尾后的完整校验

1. BACKFILL 第一次到达远端末尾时不直接宣告最终完成，而是设置 `backfill_verifying=true`、`backfill_clean_passes=0`，下一轮从游标 `0` 开始完整扫描。
2. VERIFY 阶段从第一页扫描至末页，比较每一个远端作品唯一 ID 与本地已知集合。
3. 校验发现未知 ID 时立即按本轮剩余额度生成下载候选，清零 clean passes，并重新进入 BACKFILL；不得为了完成校验而延迟已经发现的下载。
4. 一轮完整扫描没有发现任何未知 ID 时，将 clean passes 增加 1。
5. 连续两轮完整扫描都没有发现未知 ID 后，才设置 `backfill_complete=true`、`backfill_verifying=false` 并写入 `backfill_verified_at`。
6. 两轮完整校验在两个独立的计划任务中执行，继续使用页内延迟，避免在同一任务内连续打满上游。
7. 如果服务器在一次扫描的每个分页请求之间持续任意重排，分页 API 本身无法提供绝对快照保证；连续两次 ID 校验保证在列表能够稳定完成至少一次扫描时最终收敛。

### 历史完成后的运行

`backfill_complete=true` 后，普通增量任务继续执行 HEAD 扫描。距离 `backfill_verified_at` 达到 24 小时后，下一次正常调度清除 complete 并自动重新进入 VERIFY，执行两轮低频完整 ID 校验，捕获旧作品重新公开、置顶变化、发布时间修正以及同数量作品替换。VERIFY 已经进行全量远端扫描，因此同时复用本轮结果检查已知作品的本地媒体完整性，将终态下载失败或文件缺失项生成 `AUDIT_REPAIR`，不增加额外远端请求。该 24 小时间隔提供配置项，但默认值固定为 24 小时且不需要前端或 compose 增加必填变量。

显式 AUDIT 仍保持全量扫描和文件修复能力，并且不复用普通回填完成标记作为审计停止条件。

## 事务与恢复

抓取 envelope、run items、水位和回填状态由同一个 `storeFetchPlan` 新事务写入：

1. 校验 run 仍处于当前可提交状态；
2. 写入本轮候选项；
3. 更新抓取计数和 stop reason；
4. 最后更新 watermark、`backfill_cursor`、`backfill_complete`、`backfill_source_id`、校验状态和验证时间；
5. 任一步失败则整体回滚。

远程抓取失败不会产生 envelope，因此不改变游标。下载在独立队列中失败也不会回退抓取游标；已持久化的下载 item 继续使用现有重试和审计修复机制。

## 上游响应兼容与错误分类

### status-only 空终止页

满足以下全部条件时作为兼容空页：

- 响应是对象；
- `status_code=0`；
- 缺少 `aweme_list`；
- 没有 401、403、429、验证码、登录或风控信号。

HEAD 阶段遇到该响应表示没有更多头部作品，但如果历史回填未完成仍可切换到 BACKFILL。BACKFILL 阶段遇到该响应表示历史终止并进入 VERIFY；只有连续两轮完整校验干净后才设置 `backfillComplete=true`。diagnostics 记录 `STATUS_ONLY_EMPTY_PAGE`，但任务不失败。

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
- 达到配额：下一轮至少重叠当前页，不越过尚未选择的页尾未知项，也不依赖服务器保持完全相同的页边界；
- 服务端置顶、取消置顶、发布时间修正或重新公开：HEAD 扫描、重叠页和定期完整 ID 校验共同重新发现；
- 下载瞬态失败：由下载 item 自身重试，不重新抓取整段作者列表；
- 下载最终失败或本地文件缺失：由显式 AUDIT 或自动 VERIFY 重新发现并生成 `AUDIT_REPAIR`，不能被普通已知 ID 判断永久掩盖；
- 用户主动屏蔽作品：继续遵循屏蔽语义，不进入下载完成分母。

## 测试设计

### Python 分页测试

- 1000 个作品，首次 80、后续每轮 20，最终选择全部唯一 ID；
- 回填过程中头部新增 5 个作品，下一轮优先选择且历史仍继续；
- 页中途和页末达到配额后都从当前页重叠恢复；
- 模拟作品在分页之间前移和后移，最终通过完整 ID 校验发现；
- 到达末尾后必须连续两轮干净校验才完成；
- 校验中发现未知 ID 时立即退出干净校验状态并进入下载批次；
- 完成 24 小时后自动重新进入完整校验；
- 同页和跨页重复不占额度；
- status-only 空页分别在 HEAD 与 BACKFILL 阶段正确处理；
- audit 忽略普通回填完成状态；
- APIResponseError 不再被固定文案误判为超时。

### Java 与数据库测试

- request/command/envelope 新字段完整传递；
- SQLite 与 PostgreSQL schema inspection 均包含新字段；
- 作者 ID 变化忽略旧游标；
- `storeFetchPlan` 成功时原子推进游标；
- 事务失败时 run items、水位、游标和校验状态全部回滚；
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
