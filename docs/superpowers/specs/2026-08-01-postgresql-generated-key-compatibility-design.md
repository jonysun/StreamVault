# PostgreSQL 生成主键兼容性修复设计

## 背景与问题

生产环境从 SQLite 切换到 PostgreSQL 后，定时收藏任务在创建 `biz_collect_run` 记录时抛出 `InvalidDataAccessApiUsageException`。当前插入代码使用 `Statement.RETURN_GENERATED_KEYS`，随后调用 `KeyHolder.getKey()` 获取主键。

SQLite JDBC 在该路径上只返回一个生成值，因此现有测试能够通过。PostgreSQL JDBC 在未限定返回列时可能返回插入后的整行；此时 `GeneratedKeyHolder` 中包含 `id` 以及其他业务字段，`getKey()` 因为返回列不止一个而拒绝取值。

同样的组合还存在于数据库维护操作的创建路径。如果只修复收藏任务，后续执行数据库瘦身或历史清理时仍可能触发相同异常。

## 目标

- 收藏任务在 SQLite 和 PostgreSQL 上都能正确创建运行记录和持久化队列任务。
- 数据库维护操作在 SQLite 和 PostgreSQL 上都能正确取得新建操作的 ID。
- PostgreSQL 返回整行生成键时，代码明确选择 `id`，不依赖 `KeyHolder.getKey()` 的单列假设。
- SQLite 只返回单个匿名生成值时继续兼容。
- 不修改数据库结构、Flyway 迁移、收藏分页逻辑、下载队列逻辑或任务状态机。
- 生成键缺失、类型错误或返回多行时快速失败，并给出可诊断的异常信息。

## 方案

### 统一生成 ID 提取器

在事务包内增加一个包级可见的生成 ID 提取器，供 `CollectQueueTransaction` 和 `DatabaseMaintenanceTransaction` 共同使用。提取规则按以下顺序执行：

1. 要求生成键结果恰好包含一行。
2. 在该行中优先查找字段名为 `id` 的值，字段名匹配不区分大小写。
3. 如果没有 `id` 字段，但该行只有一个字段，则将该字段作为 SQLite 兼容返回值。
4. 选中的值必须是 `Number`，转换为 `long` 返回。
5. 返回行数、字段或类型不符合约定时抛出 `IllegalStateException`，错误信息包含业务类型和返回字段名，但不输出整行业务数据。

该方案直接兼容已观察到的 PostgreSQL 整行返回行为，同时保留 SQLite 回滚部署能力。现有 SQL 和 `PreparedStatement` 创建方式保持不变。

### 调用点

- `CollectQueueTransaction.insertRun()`：提取 `biz_collect_run.id`。
- `CollectQueueTransaction.insertJob()`：提取 `biz_job_queue.id`。
- `DatabaseMaintenanceTransaction.create()`：提取 `biz_database_maintenance_operation.id`。

除生成键读取外，不改变这些事务的提交、回滚、去重或状态更新行为。

## 未采用方案

### 指定生成列名称

可以将 `prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)` 改为 `prepareStatement(sql, new String[] { "id" })`。改动较少，但仍依赖各 JDBC 驱动对指定生成列的实现差异，并且不直接覆盖生产日志中已经出现的多列 `KeyHolder` 形态。

### PostgreSQL `RETURNING id`

可以使用 `INSERT ... RETURNING id`，但会把共享事务 SQL 绑定到 PostgreSQL 方言，削弱 SQLite 回滚路径，不符合本次兼容性修复范围。

### 仅修收藏任务

只修改 `CollectQueueTransaction` 能解除当前故障，但会保留数据库维护入口的同类已知风险，不满足一次性排除此类问题的目标。

## 测试

新增提取器单元测试，至少覆盖：

- PostgreSQL 风格：一行中同时包含 `id` 和多个业务字段，正确返回 `id`。
- SQLite 风格：一行只有一个匿名数值字段，正确返回该值。
- 返回字段名为大写 `ID` 时仍正确识别。
- 无生成键行、返回多行、缺少 `id` 且包含多个字段、主键值不是数字时明确失败。

继续运行以下现有事务测试，确认 SQLite 行为未回归：

- `CollectQueueTransactionTest`
- `DatabaseMaintenanceTransactionTest`

最后运行后端完整 Maven 测试套件。成功标准为所有测试通过，且代码库中不再存在直接调用 `KeyHolder.getKey()` 的生产路径。

## 发布与生产验证

修复镜像发布前，生产端保持 `stream-vault` 应用停止，PostgreSQL 容器和数据卷继续运行。无需重新迁移 SQLite 数据，也不能重复执行正式 `load`。

新镜像部署后按以下顺序验证：

1. 应用连接现有 PostgreSQL 并达到 `READY`。
2. 手动触发一个收藏任务，成功创建 run 和 job，不出现 `The getKey method should only be used when a single key is returned`。
3. 确认任务能够从 `QUEUED` 进入后续抓取状态。
4. 检查应用日志中没有新的 `InvalidDataAccessApiUsageException`、`PSQLException` 或事务回滚异常。
5. 观察一个定时调度周期后再恢复其他自动任务。

## 风险与回滚

主要风险是不同驱动返回的生成键字段名不同。通过“优先 `id`、单字段兼容回退、其他情况拒绝猜测”的规则控制风险。提取失败会使当前事务回滚，不会留下半条收藏运行或队列记录。

代码回滚只需恢复旧生成键读取实现；数据库没有结构或数据迁移，因此不需要数据库回滚。生产数据库继续使用本次已完成并验证的 PostgreSQL 数据卷。
