# PostgreSQL、F2 与抖音下载链路加固实施计划

## 目标

按已确认设计修复 F2 日志竞态、下载侧风控识别及 PostgreSQL 动态字段长度问题，不依赖或修改 SQLite。

## 实施步骤

1. 修改 `douyin.py`，在导入 F2 时使用进程独立临时日志目录并恢复原工作目录。
2. 修改 `DouyinIncrementalFetchService` 和 `CollectJobWorker`，将残余 F2 日志清理竞态归为可重试的 `F2_RUNTIME_ERROR`，归属 `APPLICATION`。
3. 修改 `DouUtil`，对单作品 F2 非 JSON 输出生成不含正文和 Cookie 的安全诊断；保留退出码、长度及明确 HTTP/风控类别。
4. 修改 `PlatformCookieService`，把 HTTP 429 和常见限流文本纳入明确风控证据；通过现有 `DouyinPlatformAdapter` 路径启动容器内全局冷却。
5. 新增 PostgreSQL Flyway `V006__expand_dynamic_media_fields.sql`，将已确认的外部 URL 和路径字段扩展为 `TEXT`；同步 `VideoDataEntity` 与 `GraphicContentEntity` 映射。
6. 增加脚本契约、错误分类、适配器冷却和 PostgreSQL 部署契约测试。
7. 运行 Python 定向测试、Java 定向测试、完整 Maven 测试及 `git diff --check`，最后复核日志分类与部署影响。

## 验收点

- F2 日志目录不再跨进程共享。
- 同类 F2 运行时失败可以重试，但不触发风控冷却。
- 429 会延期下载并启动全局冷却，404、超时和普通 HTML 响应不会被误判为已确认风控。
- V006 不修改旧迁移、不删除数据，Hibernate PostgreSQL 校验与实体一致。
- 测试日志和持久错误信息不包含 Cookie 或完整上游响应正文。
