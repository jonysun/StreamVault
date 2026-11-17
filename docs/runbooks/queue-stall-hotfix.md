# 下载队列故障排查与镜像发布

## 2026-11-16 修复

- 单链接入队兼容 PostgreSQL 返回多列生成键；入队失败时，历史记录显示 `FAILED / ENQUEUE_FAILED`。
- DTK 请求超时覆盖响应头和完整响应体。响应体停滞时取消 HTTP 请求，进入现有节点切换或队列重试流程，释放抓取线程。

现场日志显示：2026-11-16 的 10 次定时触发复用了同一批 229 个任务，全部 `inserted=false`，其中 228 个 `QUEUED`、1 个 `FETCHING`。任务 153（runId=180089）从 11 月 15 日 12:26 起没有结束，最后一条分页响应在 12:27:35。单个抓取线程被占用后，其余任务无法被领取。日志证明消费停滞；“仅返回部分 HTTP 响应体导致超时失效”的路径已在本地测试复现。

## 超时边界

DTK 的 `dtkTimeoutMs` 默认为 15000 毫秒，最小有效值为 1000 毫秒。现在每次 HTTP 请求直到完整响应体接收结束都受这个超时限制，超时或调用线程被中断时取消异步请求。

这个数值不是整轮收藏任务的总时限。多页抓取、节点切换和异步任务轮询会累计耗时；整个收藏任务目前没有统一的执行时间看门狗。抓取线程每 15 秒更新一次运行心跳，心跳不能证明上游抓取取得了进展。启动恢复只处理旧的未完成运行，不是运行期间的任务超时机制。

## 标准发布流程

先把修复提交并推送到 `main`，再运行 `.github/workflows/docker-image.yml` 中的 `Build latest`。该工作流目前没有 `push` 触发器，需要手动触发：

```sh
gh workflow run docker-image.yml --repo jonysun/StreamVault --ref main \
  -f image_tag=latest -f create_release=false
```

跟进对应提交的 Actions 运行，确认 `Build and Push with Input Tag` 成功发布 `linux/amd64` 和 `linux/arm64` 镜像，再更新 NAS。

NAS 当前 Compose 项目使用 `jonysun/stream-vault:latest`。在项目目录中执行（Docker 权限不足时使用 `sudo docker`）：

```sh
cd /volume4/docker/my-stream-vault
docker compose -f docker-compose.yaml pull stream-vault
docker compose -f docker-compose.yaml up -d --no-deps stream-vault
```

保留现有生产 Compose 配置、数据库服务、数据库卷和媒体挂载。更新应用会短暂中断服务，不需要重新迁移数据或清空队列。

如果曾自行安装 JAR 覆盖，请先撤销该覆盖，否则挂载的旧 JAR 会遮住新镜像中的程序。

## 批量补齐视频缩略图

最新镜像内置 `streamvault-backfill-thumbnails` 命令。它先复用或下载数据库中的远程封面 URL，失败后才用 ffmpeg 从视频抽帧，并把本地封面路径写回 `videocover`：

```sh
docker exec my-stream-vault streamvault-backfill-thumbnails
```

先预览而不写文件或数据库：

```sh
docker exec my-stream-vault streamvault-backfill-thumbnails --dry-run
```

分批执行可加 `--limit 100`。命令只处理 `biz_video` 中已有本地视频文件的记录，不会重新下载视频。

## 验证

```sh
docker logs --since 5m my-stream-vault 2>&1 | grep -E 'Readiness|CollectRecovery|CollectWorker|DirectDownloadWorker|ENQUEUE_FAILED|transport-error'
```

1. 应用启动完成；旧的超时运行应出现 `CollectRecovery` 恢复记录，随后出现新的 `CollectWorker start/complete` 或带明确错误原因的重试记录。
2. 下载中心的收藏任务开始变化，不再整批维持同一状态。
3. 重新提交之前因生成键异常而未真正入队的单链接，确认进入解析/下载或显示具体错误。旧历史记录中的“假排队”不会自动变成下载任务。
4. 若 DTK 响应继续卡住，应在配置超时后出现 `HttpTimeoutException`，而非无限等待。上游不可用时仍可能重试，修复不保证上游请求成功。
