# 手动标记远端已删除下载项

## 目标

让管理员处理因作者列表快照无法刷新而长期处于 `LIST_SNAPSHOT_PENDING` 的收藏下载项。管理员确认作品在服务器端已删除或不可见后，可批量将其移入下载历史并显示为“远端已删除”。

## 边界

- 仅允许 `COLLECT`（收藏下载）来源。
- 仅允许当前处于 `QUEUED` 或 `RETRY_WAIT` 且 `error_code = LIST_SNAPSHOT_PENDING` 的项。
- 不改变作者任务启用状态，不删除作者、作品记录或本地媒体文件。
- 保留 `work_id`、原始链接、标题和作者快照。
- 自动的完整作者列表验证逻辑保持不变；HTTP 200、空作者或空作品列表不会单独触发自动删除判定。

## 数据更新

在同一数据库写事务内更新选中项：

- `process_state = SKIPPED_REMOTE_MISSING`
- `error_code = REMOTE_LIST_MISSING`
- `error_message` 记录人工确认及原状态
- 清空锁、可用时间和 `metadata_snapshot`
- 设置 `finished_at` 和 `updated_at`

该状态已属于下载中心历史状态，因此更新后自动从当前任务视图移除，并在历史记录中显示。

## 接口与界面

- `POST /admin/api/download-center/transition` 接受 `action=MARK_REMOTE_MISSING`。
- 后端返回变更数量和跳过数量；非法来源或不符合状态的项计入跳过。
- 下载中心新增“标记远端已删除”按钮。
- 按钮仅在当前任务视图选择项中存在可处理的 `LIST_SNAPSHOT_PENDING` 收藏项时启用。
- 操作前弹窗明确：这是人工确认，系统没有完整作者列表证据；不会删除本地媒体文件。

## 验证标准

1. 选中待刷新作者列表的收藏项后可执行操作，操作成功后状态为历史中的“远端已删除”。
2. 选中单链接、YouTube 或其他状态的项不会被更新。
3. 重复执行、并发状态变化不会抛出错误，返回准确的 `changed/skipped`。
4. 原有自动 `SKIPPED_REMOTE_MISSING` 逻辑和重试逻辑不受影响。
