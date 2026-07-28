# SQLite 运行历史保留期维护实施计划

## 目标

完成数据库工程阶段 B 的第二个独立交付：在现有签名 preview token、数据库 fingerprint、
全局暂停和分批断点机制上，补齐运行历史的安全保留期清理。此计划不删除作品或媒体文件，
不合并重复作品，不创建唯一约束，也不执行 `VACUUM`。

## 清理操作和顺序

默认操作顺序为：

1. `CLEAR_EXACT_DUPLICATE_VIDEOINFO`
2. `PURGE_EXPIRED_RUN_ITEMS`
3. `PURGE_EXPIRED_RUN_EVENTS`
4. `PURGE_EXPIRED_TERMINAL_RUNS`
5. `PURGE_EXPIRED_TERMINAL_JOBS`

父 `run` 只有在处于终态、超过 90 天且已经没有任何 item/event 子记录时才允许删除。
失败 run item 保留 365 天，其他 run item、run event 和终态 job/run 保留 90 天。
缺少任一可选历史表时，对应操作返回已耗尽的空批次。

## 任务 1：增加事务级失败测试

文件：

- 修改 `backstage/src/test/java/com/flower/spirit/service/transaction/DatabaseMaintenanceTransactionTest.java`

断言：

1. 过期 item 和 event 先分批删除。
2. 已无子记录的过期终态 run 随后删除。
3. 仍有未过期失败 item 的 run 保留。
4. 活跃 run、近期终态 run、活跃 job 和近期终态 job 保留。
5. 过期终态 job 删除。
6. 缺少可选历史表时所有历史清理方法安全 no-op。

## 任务 2：实现运行历史分批事务

文件：

- 修改 `backstage/src/main/java/com/flower/spirit/service/transaction/DatabaseMaintenanceTransaction.java`

实现：

1. 为 event、terminal run 和 terminal job 增加主键游标批处理。
2. 每批保持独立 `REQUIRES_NEW` 事务并复用现有 operation 进度记录。
3. terminal run 查询使用 `NOT EXISTS` 同时保护 item 和 event 引用。
4. 所有动态 SQL 标识符只来自代码内常量，不接受请求输入。
5. 缺表返回 `BatchResult(0, 0, true)`。

## 任务 3：扩展 preview/apply 编排

文件：

- 修改 `backstage/src/main/java/com/flower/spirit/service/DatabaseMaintenanceService.java`
- 修改 `backstage/src/test/java/com/flower/spirit/service/DatabaseMaintenanceServiceTest.java`

实现：

1. 注册三个新操作并固定默认顺序。
2. preview 直接读取 audit 的 `retentionCandidates`，删除服务内重复的 SQLite 计数 SQL。
3. apply 的 switch 只分派到对应事务方法，不放入数据库方言 SQL。
4. 测试默认 preview 顺序、各操作估算和未知操作拒绝。

## 任务 4：验证和交付

聚焦测试：

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'
& 'F:\opencode\Project\streamV\.tmp\maven\apache-maven-3.9.9\bin\mvn.cmd' `
  '-Dtest=DatabaseAuditServiceTest,DatabaseMaintenanceServiceTest,DatabaseMaintenanceTransactionTest' test
```

完整验证：

```powershell
& 'F:\opencode\Project\streamV\.tmp\maven\apache-maven-3.9.9\bin\mvn.cmd' test
git diff --check
```

交付检查：

1. 所有 apply 仍默认关闭并要求全局暂停、有效 token 和未变化 fingerprint。
2. 不存在作品表 `DELETE`、媒体文件删除、表重建、索引创建或 `VACUUM`。
3. 父 run 删除明确受两个 `NOT EXISTS` 约束保护。
4. SQLite 日期 SQL 只存在于审计/事务基础设施边界，维护编排保持数据库中立。
5. 提交、推送、创建 PR，检查通过后合并到 `main`。
