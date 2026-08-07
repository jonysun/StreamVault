# 抖音作者作品游标回填与上游错误治理实施计划

## 目标

在保持首次 `omaxcur`、后续每轮 `maxcur` 个未知作品语义的前提下，实现最新区扫描、历史游标续传、页重叠、两轮完整 ID 校验和 24 小时复核。同步修复 status-only 空页兼容、f2 异常分类、重试日志等级和单容器请求节流。

## 任务 1：以测试固定 Python 分页状态机

文件：

- 修改 `backstage/src/test/python/test_douyin_incremental.py`
- 修改 `backstage/src/main/docker/buildx/script/douyin_incremental.py`

步骤：

1. 扩展分页输入和 envelope，加入回填游标、完成状态、校验状态与安全续传游标。
2. 增加 HEAD、BACKFILL、VERIFY 三阶段测试。
3. 增加页中途/页末配额均重叠当前页、跨轮去重、顺序漂移和两轮干净校验测试。
4. 保持 AUDIT 全量语义，保持单轮候选不超过配额。
5. 运行 Python 定向测试。

## 任务 2：修复上游空页和异常分类

文件：

- 修改 `backstage/src/main/docker/buildx/script/douyin.py`
- 修改 `backstage/src/test/python/test_douyin_incremental.py`

步骤：

1. `status_code=0` 且仅缺分页字段时规范化为带诊断标记的空终止页。
2. 固定安全文案退出异常证据链。
3. 普通 `APIResponseError` 映射为 `F2_UPSTREAM_RESPONSE_ERROR`；timeout、连接、429、认证分别保留独立分类。
4. 增加敏感数据不落日志测试。

## 任务 3：扩展 Java/Python 抓取协议

文件：

- 修改 `DouyinFetchRequest.java`
- 修改 `DouyinFetchEnvelope.java`
- 修改 `CommandUtil.java`
- 修改 `DouyinIncrementalFetchService.java`
- 修改相应 Java 单元测试

步骤：

1. 请求传递当前回填状态与 24 小时校验到期判断结果。
2. 严格解析新增 envelope 字段并保留旧结果的安全默认值。
3. 继续使用临时文件传递 known IDs，不把 Cookie 写入诊断。
4. 覆盖命令参数、JSON 解析、缺字段和清理测试。

## 任务 4：增加跨数据库回填状态

文件：

- 修改 `CollectDataEntity.java`
- 修改 `CollectPipelineSchemaInitializer.java`
- 修改 `SqliteSchemaPreflight.java`
- 修改 schema/preflight 测试

步骤：

1. 幂等增加 cursor、complete、source、verifying、clean passes 和 verified at 字段。
2. 使用 SQLite/PostgreSQL 均可映射的 VARCHAR、INTEGER、TIMESTAMP。
3. 新增数据库检查测试，确保现有数据默认从未完成回填状态开始且无需生产手工 SQL。

## 任务 5：原子保存计划和回填进度

文件：

- 修改 `CollectDataService.java`
- 修改 `CollectRunService.java`
- 修改 `CollectQueueTransaction.java`
- 修改抓取计划与事务测试

步骤：

1. 作者 source ID 不匹配时忽略旧状态。
2. 构造带回填状态的请求并移除按 known count 放大每轮扫描页数的逻辑。
3. run items、水位和回填进度在同一新事务中提交。
4. VERIFY 发现未知 ID 时立即排队并清空干净次数；连续两次完整无未知才完成。
5. 事务失败时所有状态回滚。

## 任务 6：日志等级、错误域与单容器节流

文件：

- 修改 `CollectJobWorker.java`
- 修改 `CollectJobWorkerTest.java`
- 修改必要的配置文件

步骤：

1. 成功排入下一次 retry 的普通远程错误记录 WARN 且不打印完整堆栈。
2. 重试耗尽、不可重试和队列写失败继续记录 ERROR。
3. 新增 `F2_UPSTREAM_RESPONSE_ERROR` 的 REMOTE_API 分类。
4. 增加单容器全局最小任务间隔；普通节流与风险冷却完全分离。
5. 使用默认配置工作，不要求 compose 新增变量。

## 任务 7：完整验证与审查

命令：

```text
python -m unittest discover -s backstage/src/test/python -p "test_douyin_incremental.py" -v
mvn -f backstage/pom.xml -Dtest=CommandUtilIncrementalFetchTest,DouyinIncrementalFetchServiceTest,CollectDataServiceFetchPlanTest,CollectJobWorkerTest,CollectPipelineSchemaInitializerTest,SqliteSchemaPreflightTest test
mvn -f backstage/pom.xml test
git diff --check
```

审查：

1. 固定作品集合可在有限轮选完全部唯一 ID。
2. 分页重排通过重叠与两轮校验收敛。
3. 普通增量不再按已知总量重复深扫。
4. 生产日志、数据库、Cookie、compose 密码不进入差异。
5. SQLite/PostgreSQL 路径无数据库专用业务分支。
