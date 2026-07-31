# PostgreSQL Schema-only 独立启动设计

## 背景与问题

迁移 Compose 使用 `--streamvault.schema-only=true` 启动应用，以便在空 PostgreSQL 中执行 Flyway。现有实现仍启动完整的 `SpiritApplication`，只是在 `ApplicationReadyEvent` 最后退出。因此在退出之前，`AppConfig`、Quartz、运行控制、队列恢复和其他业务初始化都会运行。

空目标库尚未导入 `biz_config` 数据时，`AppConfig` 通过 `ConfigService.getData()` 直接读取第一行并抛出 `IndexOutOfBoundsException`。即使只为该异常判空，其他 Ready 监听器仍可能向目标库写入运行控制数据，使后续迁移预检无法确认目标业务表为空。

## 目标

- `--streamvault.schema-only=true` 只创建和校验 PostgreSQL 数据库结构。
- 只装配数据库连接与 Flyway，不扫描或初始化任何 StreamVault 业务 Bean。
- Flyway 成功时进程正常退出并返回退出码 `0`；失败时返回非零退出码。
- schema-only 执行后，除 `flyway_schema_history` 和数据库结构外，不产生业务数据。
- 普通 Web 应用、SQLite 部署、PostgreSQL 正常运行模式及已有维护命令保持原行为。

## 方案

### 启动分流

`SpiritApplication.main` 在调用现有 `initData()` 和完整 Spring Boot 启动之前识别 schema-only 参数。识别为真时，启动一个仅包含 `@Configuration` 与 `@EnableAutoConfiguration` 的内部配置源，并将 Web 类型设为 `NONE`。

该最小配置不启用组件扫描、JPA Repository 扫描、实体扫描或调度，因此不会创建 `AppConfig`、Quartz、控制器、队列 Worker、恢复服务及业务监听器。Spring Boot 的 DataSource 和 Flyway 自动配置仍会根据 `docker,postgresql` profiles 与 Compose 中的数据库参数执行。

### 参数规则

本次只支持迁移 Compose 已明确使用的命令行形式：

```text
--streamvault.schema-only=true
```

值必须显式为 `true`，避免普通启动被意外分流。现有 `--backfill-douyin-publishtime` 与 `--reset-content-index` 路径不变。

### 执行流程

1. JVM 进入 `SpiritApplication.main`。
2. 检测 schema-only 参数。
3. 启动无 Web、无组件扫描的最小 Spring 上下文。
4. DataSource 连接 PostgreSQL；PostgreSQL profile 使用合法的 `SELECT 1` 连接初始化语句。
5. Flyway 校验并应用 `db/migration/postgresql`。
6. 上下文正常关闭，主方法返回，容器退出码为 `0`。
7. 任一连接、校验或迁移异常继续向外传播，Java 进程返回非零退出码。

## 不采用的方案

- 不在 `AppConfig` 中为缺少配置行创建默认业务数据：目标库必须保持空，数据只能由 SQLite 迁移程序导入。
- 不给每个业务初始化器分别添加 schema-only 判断：修改分散且未来新增监听器时容易复发。
- 不通过 `psql` 绕过 Flyway：Flyway 历史和应用实际启动路径必须保持一致。

## 验证

### 自动测试

- 参数检测：仅显式 `--streamvault.schema-only=true` 进入最小路径。
- 部署契约：schema-only 配置源不包含组件扫描、JPA Repository、实体扫描和调度注解。
- 原有完整后端测试必须全部通过，证明普通启动和 SQLite 行为未回归。

### 生产服务器演练

修复镜像发布后，销毁并重建 `streamvault-rehearsal-1` 的独立 PostgreSQL 演练卷，从空库执行：

1. `schema-migrate` 返回 `0`；
2. Flyway 历史只有成功的 `V001`；
3. 目标业务表行数均为 `0`；
4. `data-migrate --mode dry-run` 返回 `status=ok`；
5. 暂不启动演练目录中的 `stream-vault` Web 服务。

## 风险与回滚

主要风险是 schema-only 参数识别错误。通过严格匹配参数和值及回归测试控制该风险。代码回滚只需恢复原主启动路径；演练数据库始终使用独立 Compose 项目和命名卷，可通过带固定项目名的 `down -v` 重建，不影响生产 SQLite 服务。
