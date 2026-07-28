# SQLite 媒体 identity 修复实施计划

## 目标

按已确认设计只实施阶段 A：消除 `biz_video` 和 `biz_graphic_content` 使用 Hibernate
`TABLE` 主键生成器时，在 SQLite WAL 外层读快照之后写 `seq_common` 导致的确定性
`SQLITE_BUSY_SNAPSHOT`。

## 成功标准

- 实际媒体实体在 SQLite WAL 事务中先读后插入能够成功提交。
- 视频和图文使用数据库原生 identity，持久化不再修改 `seq_common`。
- 已存在 SQLite 媒体表必须是单列 `id INTEGER PRIMARY KEY`，否则启动安全失败。
- 业务服务、下载顺序、重试分类和 PostgreSQL direct executor 保持不变。
- 聚焦测试和完整 Maven 测试通过。

## 任务 1：增加主键映射失败测试

文件：

- 新增 `backstage/src/test/java/com/flower/spirit/entity/MediaIdentityGenerationTest.java`

步骤：

1. 反射读取 `VideoDataEntity.id` 和 `GraphicContentEntity.id`。
2. 断言两者使用 `GenerationType.IDENTITY` 且 generator 名为空。
3. 在修改实体前运行测试，确认当前 `TABLE` 映射导致失败。

## 任务 2：增加真实 SQLite WAL 回归测试

文件：

- 新增 `backstage/src/test/java/com/flower/spirit/database/sqlite/MediaIdentitySqliteIntegrationTest.java`

步骤：

1. 使用临时 SQLite 文件、WAL、Hibernate SQLiteDialect 和实际两个媒体实体创建 schema。
2. 创建 `seq_common` 哨兵记录。
3. 在视频事务中先查询表建立读快照，再 persist、flush、commit 新视频。
4. 在图文事务中执行同样的先读后写流程。
5. 断言两行均持久化、ID 非空且哨兵值不变。
6. 在修改实体前运行测试，确认旧 `TABLE` 生成器能够复现快照冲突。

## 任务 3：切换媒体实体为原生 identity

文件：

- 修改 `backstage/src/main/java/com/flower/spirit/entity/VideoDataEntity.java`
- 修改 `backstage/src/main/java/com/flower/spirit/entity/GraphicContentEntity.java`

步骤：

1. 将 `@GeneratedValue` 改为 `GenerationType.IDENTITY`。
2. 删除两个实体的 `@TableGenerator` 注解和 import。
3. 不修改 `Integer` ID、字段映射、DAO 或业务服务。
4. 重新运行任务 1 和任务 2 测试，确认通过。

## 任务 4：扩展 SQLite schema preflight

文件：

- 修改 `backstage/src/main/java/com/flower/spirit/config/SqliteSchemaPreflight.java`
- 修改 `backstage/src/test/java/com/flower/spirit/config/SqliteSchemaPreflightTest.java`

步骤：

1. 将 `biz_video` 和 `biz_graphic_content` 加入 identity 表集合。
2. 扩展兼容 schema fixture，覆盖两张媒体表。
3. 验证两张表的原生 INSERT 自动生成 ID，且不修改 `seq_common`。
4. 增加媒体表 `BIGINT PRIMARY KEY` 拒绝测试，确认不会自动重建表。
5. 保持缺失表允许 Hibernate 创建的现有行为。

## 任务 5：验证和交付

聚焦测试：

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'
& 'F:\opencode\Project\streamV\.tmp\maven\apache-maven-3.9.9\bin\mvn.cmd' '-Dtest=MediaIdentityGenerationTest,MediaIdentitySqliteIntegrationTest,SqliteSchemaPreflightTest,WorkPersistenceServiceTest,WorkIngestServiceTest' test
```

完整验证：

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'
& 'F:\opencode\Project\streamV\.tmp\maven\apache-maven-3.9.9\bin\mvn.cmd' test
git diff --check
```

交付检查：

1. `VideoDataEntity` 和 `GraphicContentEntity` 不再引用 `TableGenerator`。
2. 业务服务没有新增 SQLite 类型、PRAGMA 或数据库条件分支。
3. diff 只包含设计/计划、两个实体、preflight 和对应测试。
4. 提交并推送当前分支，创建 PR；检查通过后合并到 `main`。
