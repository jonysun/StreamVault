# DTK Admin Watchlist 实现计划

设计依据：`docs/superpowers/specs/2026-10-11-dtk-admin-watchlist-design.md`

## 阶段 1：配置与持久化

1. 在 `ConfigEntity` 增加 Admin URL、Admin Key、自动同步开关字段及访问器。
2. 在 `ConfigService`、`AppConfig`、设置页保存/加载链路中接入字段；Admin Key 空值保留旧值，响应和页面不回显明文。
3. 增加 PostgreSQL V016 迁移；开发 SQLite 依赖 Hibernate update。

## 阶段 2：Admin HTTP 客户端

1. 新增 `DtkAdminClient`，实现连接测试、watchlist 分页、创建和删除。
2. 对 DTK envelope、HTTP 状态、错误码和 request_id 做统一解析；脱敏日志。
3. 用 DTO/record 表达 watchlist 项和平台键，分页必须完整结束。

## 阶段 3：同步服务与状态

1. 新增本地同步任务实体/DAO，持久化队列任务状态和计数。
2. 新增 `DtkAdminWatchlistService`，单线程执行器串行处理自动、增量、完全同步。
3. 从 `biz_collect_data` 构建抖音收藏作者集合；平台适配接口为 TikTok 留位。
4. 自动同步接入 `saveCollectData` 事务提交后；远程失败不影响本地事务。
5. 增量只 POST 缺失项；完全同步在完整读取成功后 POST 缺失项并 DELETE 多余同平台 author。

## 阶段 4：管理 API 与前端

1. 增加配置保存、连接测试、增量/完全同步启动和状态查询端点。
2. 在 `admin/config.html` 增加 DTK 管理区域、脱敏 Key 输入、开关、按钮、状态轮询和完全同步二次确认。

## 阶段 5：验证

1. 添加来源解析、平台键、分页、差异和删除安全测试。
2. 运行 Maven 编译和相关测试，检查 PostgreSQL migration、SQLite 启动兼容性。
3. 检查 `git diff --check`、敏感字段搜索和工作区未跟踪用户文件不被纳入提交。
