# SQLite 数据瘦身只读审计实施计划

## 目标

实现数据库工程阶段 B 的第一个独立交付：扩展现有 `/admin/database/audit` 只读报告，
识别可以安全清理的重复大字段、规范作品业务键重复、媒体引用冲突、缺失规范平台键、
孤儿记录和保留期候选。此计划不执行删除、合并、唯一索引创建或 VACUUM。

## 成功标准

- 现有 `video`、`graphic`、`collectSnapshots`、`differentSamples`、`storage` 和
  `fingerprint` 字段保持兼容。
- raw payload 审计能区分两列相等、不同、仅 `jsonData` 和仅 `videoinfo`。
- 只有非空 `platformkey + videoid` 才作为可确认作品重复业务键。
- 重复样本不返回标题、作者、实际媒体路径或 raw payload。
- 孤儿和保留期统计与当前 SQLite schema、已有清理策略一致。
- 报告 fingerprint 包含新增统计，数据状态变化会使旧 maintenance preview 失效。
- 聚焦测试和完整 Maven 测试通过。

## 任务 1：扩展测试 schema 和 raw payload 断言

文件：

- 修改 `backstage/src/test/java/com/flower/spirit/service/DatabaseAuditServiceTest.java`

步骤：

1. 将测试媒体表补充 `platformkey`、旧平台名、`videoid`、`contenttype` 和媒体引用列。
2. 将运行、队列、作者历史和收藏明细表加入 fixture。
3. 增加仅 `jsonData`、仅 `videoinfo`、两列相等和两列不同的测试行。
4. 断言报告不包含 raw payload 内容。

## 任务 2：增加作品重复与路径冲突测试

文件：

- 修改 `backstage/src/test/java/com/flower/spirit/service/DatabaseAuditServiceTest.java`

步骤：

1. 插入同一 `platformkey + videoid` 的重复视频和重复图文。
2. 视频重复组使用不同 `videoaddr`，图文重复组使用相同 `images`。
3. 断言候选组数、涉及行数和媒体引用冲突组数。
4. 断言样本只包含平台键、作品 ID、行数、行 ID 和不同引用数量。
5. 插入空 `platformkey` 行，断言它只进入 normalization 统计，不进入确认重复组。

## 任务 3：增加孤儿和保留期测试

文件：

- 修改 `backstage/src/test/java/com/flower/spirit/service/DatabaseAuditServiceTest.java`

步骤：

1. 插入没有父 run 的 run item 和 run event。
2. 插入没有父 profile 的 author name history。
3. 插入没有父收藏任务的 collect detail。
4. 插入超过当前 90/365 天策略的 run item、终态 run、run event 和终态 job。
5. 断言只读报告的孤儿和保留候选计数，不执行任何删除。

## 任务 4：实现只读审计扩展

文件：

- 修改 `backstage/src/main/java/com/flower/spirit/service/DatabaseAuditService.java`

步骤：

1. 在现有视频统计中增加 `jsonOnlyRows`、`videoInfoOnlyRows` 和 `emptyRawRows`。
2. 增加 `workDuplicates.video` 和 `workDuplicates.graphic` 汇总及最多 20 组脱敏样本。
3. 重复聚合只使用非空、trim 后的 `platformkey` 和 `videoid`。
4. 视频媒体引用使用 `videoaddr`，图文媒体引用使用 `images`；报告只返回 distinct 数量。
5. 增加 `normalization`、`orphans` 和 `retentionCandidates` 顶层对象。
6. 对可选历史表先检查存在性；缺表返回 0，不让审计接口失败。
7. 将新增稳定计数加入 fingerprint，不把 samples 或完整内容加入 fingerprint 输入。
8. 保留 dbstat 不可用时的现有降级行为。

## 任务 5：验证和交付

聚焦测试：

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'
& 'F:\opencode\Project\streamV\.tmp\maven\apache-maven-3.9.9\bin\mvn.cmd' '-Dtest=DatabaseAuditServiceTest,DatabaseMaintenanceServiceTest,DatabaseMaintenanceTransactionTest' test
```

完整验证：

```powershell
$env:JAVA_HOME='F:\opencode\Project\streamV\.tmp\jdk17\jdk-17.0.18+8'
& 'F:\opencode\Project\streamV\.tmp\maven\apache-maven-3.9.9\bin\mvn.cmd' test
git diff --check
```

交付检查：

1. 本 PR 没有 `DELETE`、`UPDATE`、表重建、唯一索引或 VACUUM 新逻辑。
2. API 只增加字段，不删除或重命名现有字段。
3. 业务键不使用昵称、标题或模糊匹配。
4. 样本和日志不包含 raw payload、cookie、token 或媒体实际路径。
5. 提交并推送当前分支，创建 PR；检查通过后合并到 `main`。
