# SQLite 到 PostgreSQL 一次性迁移手册

本文用于将生产环境的 StreamVault 从 SQLite 一次性切换到 PostgreSQL。迁移分为：发布新镜像、制作生产快照、两次隔离演练、正式停机迁移、验证上线和回滚准备。

## 一、必须遵守的边界

- 不得使用仓库内的 `db/spirit.db` 做迁移；它只是开发侧审计样本。
- 演练和正式迁移都必须使用生产服务器在应用停止后生成的一致性快照。
- 不得在 SQLite 源库上执行更新、瘦身、`VACUUM`、`REINDEX` 或表结构修改。
- 迁移程序只读打开 SQLite。瘦身只发生在 PostgreSQL 目标库：当 `biz_video.jsonData = biz_video.videoinfo` 时，目标库中的 `videoinfo` 写为 `NULL`。
- 两次演练必须使用准备上线的同一个镜像版本。
- 正式切换后至少保留最终 SQLite 快照、旧 Compose 文件、旧镜像标签和 PostgreSQL 数据卷 7 天。
- 回滚只恢复 SQLite 快照，不把 PostgreSQL 切换后产生的新数据反向合并到 SQLite。

## 二、生产目录约定

建议生产服务器使用以下结构。本文后续命令都从该目录执行：

```text
/你的生产目录/
├── docker-compose.yml
├── docker-compose.sqlite.rollback.yml
├── app/
│   └── db/spirit.db
├── tmp/
├── migration-snapshots/
└── migration-reports/
```

仓库中的生产模板是 `docker/docker-compose.production.yml.example`。部署时将它复制为生产目录根部的 `docker-compose.yml`，这样其中的 `./app`、`./tmp` 相对路径才会继续指向现有生产数据。模板中的 `CHANGE_ME_REPLACE_ON_PRODUCTION_HOST` 只能在生产服务器上替换，不能提交回仓库。

开始前先记录生产目录和新镜像。`vX.Y.Z` 必须替换为包含本迁移代码的新版本，不能继续使用旧的 `v5.7.1`：

```bash
export PROD_DIR=/你的生产目录
export RELEASE_IMAGE=jonysun/stream-vault:vX.Y.Z
cd "$PROD_DIR"
```

备份当前 SQLite Compose，并放入新版 Compose：

```bash
cp docker-compose.yml docker-compose.sqlite.rollback.yml
# 将仓库 docker/docker-compose.production.yml.example 上传或复制到：
# $PROD_DIR/docker-compose.yml
# 然后编辑 docker-compose.yml 中的 STREAMVAULT_IMAGE 和四处
# CHANGE_ME_REPLACE_ON_PRODUCTION_HOST，使密码保持一致。
```

确认三条生产挂载仍然存在，尤其是媒体目录：

```yaml
volumes:
  - "./app:/app"
  - "./tmp:/tmp"
  - "/home/admin_sun/Video/Downloads/stream_vault:/app/resources"
```

## 三、配置生产 Compose

生产模板已经直接写入 PostgreSQL 地址、账号、内部端口和网络，不需要 `.env`。在生产服务器生成密码并编辑 Compose：

```bash
openssl rand -hex 32
vi docker-compose.yml
docker compose -f docker-compose.yml config >/dev/null
docker pull "$RELEASE_IMAGE"
```

把生成的密码同时写入 `stream-vault`、`postgres`、`schema-migrate`、`data-migrate` 四个服务的 `*_PASSWORD` 字段，并将四处 `vX.Y.Z` 替换为实际镜像标签。

安全要求：

- PostgreSQL 不映射宿主机 `5432` 端口，只允许 Compose 内部网络访问。
- 数据保存在 Compose 命名卷 `stream-vault-postgres-data` 中。
- 账号、密码和数据库地址直接写在生产目录的 Compose 文件中，不写进镜像；该文件不得提交到 Git。
- `SPRING_PROFILES_ACTIVE: docker,postgresql` 是实际切换数据库的开关；仓库中的通用 Compose 默认仍使用 SQLite。

## 四、生成一致性生产快照

### 4.1 暂停任务并停止旧应用

先在管理页面启用全局暂停，并确认收藏抓取、下载、HLS 和后台队列不再变化。然后停止应用，最多等待 120 秒完成 SQLite 回写：

```bash
cd "$PROD_DIR"
docker stop -t 120 my-stream-vault
docker inspect my-stream-vault --format '{{.State.Status}}'
```

输出必须是 `exited`。

### 4.2 使用 SQLite Backup API 制作快照

不要只复制 `spirit.db` 主文件。下面的命令会通过 SQLite Backup API 把主文件和可能存在的 WAL 数据合并为一个一致性快照，同时保持源库只读：

```bash
cd "$PROD_DIR"
mkdir -p migration-snapshots migration-reports
export SNAPSHOT="spirit-$(date +%Y%m%d-%H%M%S).db"

docker run --rm \
  --entrypoint /opt/venv/bin/python3 \
  -e SNAPSHOT="$SNAPSHOT" \
  -v "$PROD_DIR/app:/source:ro" \
  -v "$PROD_DIR/migration-snapshots:/dest" \
  "$RELEASE_IMAGE" -c \
  'import os, sqlite3
src = sqlite3.connect("file:/source/db/spirit.db?mode=ro", uri=True)
dst = sqlite3.connect("/dest/" + os.environ["SNAPSHOT"])
src.backup(dst)
print(dst.execute("PRAGMA quick_check(1)").fetchone()[0])
dst.close()
src.close()'

sha256sum "migration-snapshots/$SNAPSHOT" | tee "migration-reports/$SNAPSHOT.sha256"
```

Backup API 输出和 `PRAGMA quick_check(1)` 必须为 `ok`。把本次的 `SNAPSHOT` 文件名记录下来；两次演练可以共用这份一致性快照。

演练快照生成完成后，可以先用旧 Compose 恢复 SQLite 生产服务：

```bash
docker compose -f docker-compose.sqlite.rollback.yml up -d
```

正式迁移当天生成最终快照后，不得执行这条启动命令。

## 五、完成两次隔离演练

两次演练都在生产服务器执行，但不得挂载生产 `./app` 目录，也不得启动演练目录中的 `stream-vault` 应用服务。

以下过程分别执行两次，把 `RUN=1` 改为 `RUN=2` 再执行第二次：

```bash
export RUN=1
export REHEARSAL_DIR="$PROD_DIR/rehearsal-$RUN"
export PROJECT_NAME="streamvault-rehearsal-$RUN"

mkdir -p "$REHEARSAL_DIR/app/db" "$REHEARSAL_DIR/tmp" "$REHEARSAL_DIR/reports"
cp "$PROD_DIR/docker-compose.yml" "$REHEARSAL_DIR/docker-compose.yml"
cp "$PROD_DIR/migration-snapshots/$SNAPSHOT" "$REHEARSAL_DIR/app/db/spirit.db"
cd "$REHEARSAL_DIR"
```

### 5.1 创建空 PostgreSQL 并执行 Flyway 建表

```bash
docker compose --profile migration \
  -p "$PROJECT_NAME" -f docker-compose.yml \
  up -d postgres

docker compose --profile migration \
  -p "$PROJECT_NAME" -f docker-compose.yml \
  run --rm schema-migrate
```

`schema-migrate` 必须以退出码 `0` 结束。

### 5.2 只读预检

```bash
set -o pipefail
docker compose --profile migration \
  -p "$PROJECT_NAME" -f docker-compose.yml \
  run --rm -T data-migrate | tee reports/dry-run.json
```

报告必须包含 `"status": "ok"`，并且不能有：缺失表、目标库意外已有数据、源表无主键或结构不兼容。

### 5.3 正式导入演练库

只有 dry-run 成功后才能执行：

```bash
docker compose --profile migration \
  -p "$PROJECT_NAME" -f docker-compose.yml \
  run --rm -T data-migrate --mode load --confirm LOAD | tee reports/load.json
```

导入是一次性的。失败后不要直接重复执行 `load`；先保留报告和日志，销毁该演练 PostgreSQL 卷，再从空库重新开始。

### 5.4 独立验证

```bash
docker compose --profile migration \
  -p "$PROJECT_NAME" -f docker-compose.yml \
  run --rm -T data-migrate --mode verify | tee reports/verify.json
```

自动验证报告必须同时满足：

- `status=ok`；
- 没有缺失表；
- 所有源表与目标表行数一致；
- `biz_video` 中不再存在 `jsonData = videoinfo` 的完全重复原始数据；
- 没有逐表行数不一致；
- PostgreSQL 中完全重复的 `videoinfo` 行数为 0。

补充说明：`load` 会按每张表的实际主键重置 PostgreSQL 自增序列，并按 `Asia/Shanghai` 转换 SQLite 的毫秒时间戳；自动 `verify` 不逐条比较时间字段，也不执行试写。主键序列和实际写入能力必须在第 6.9 节的冒烟测试中确认。

保存 dry-run、load、verify 报告、快照 SHA-256、镜像 digest 和总耗时。然后只销毁本次演练项目的数据卷：

```bash
docker compose --profile migration -p "$PROJECT_NAME" -f docker-compose.yml \
  down -v --remove-orphans
```

确认演练 1 完整成功后，用 `RUN=2` 从空 PostgreSQL 卷再做一遍。两次都成功才允许正式迁移。

## 六、正式迁移操作

建议预留 120 分钟维护窗口。

### 6.1 T-30：上线前确认

- 两次演练均使用准备发布的同一镜像；
- 两次 `verify` 均为 `status=ok`；
- 已记录两次快照哈希、行数、耗时和镜像 digest；
- 磁盘空间足够同时保留 SQLite 快照和 PostgreSQL 数据卷；
- 旧 Compose、旧镜像和回滚命令可用。

### 6.2 T-10：暂停所有生产任务

在管理页面启用全局暂停，等待收藏、下载、HLS 和队列不再变化。暂停状态会随 SQLite 数据一起迁移到 PostgreSQL。

### 6.3 T+0：停止应用并制作最终快照

按“第四节”停止 `my-stream-vault` 并重新生成一份最终快照。记录新的 `SNAPSHOT` 和 SHA-256。正式迁移期间不得再启动旧 SQLite 应用。

### 6.4 T+15：启动空 PostgreSQL 并建表

```bash
cd "$PROD_DIR"

docker compose --profile migration \
  -f docker-compose.yml up -d postgres

docker compose --profile migration \
  -f docker-compose.yml run --rm schema-migrate
```

首次正式迁移前，目标 PostgreSQL 卷必须是空库。如果此前错误使用生产项目做过演练，应停止并排查，不能在不确认卷内容的情况下继续。

### 6.5 T+20：对停机后的生产源库执行 dry-run

```bash
set -o pipefail
docker compose --profile migration -f docker-compose.yml \
  run --rm -T data-migrate | tee "migration-reports/$SNAPSHOT.dry-run.json"
```

不是 `status=ok` 时立即停止，不执行 `load`。

### 6.6 T+25：执行一次性导入

```bash
docker compose --profile migration -f docker-compose.yml \
  run --rm -T data-migrate --mode load --confirm LOAD \
  | tee "migration-reports/$SNAPSHOT.load.json"
```

迁移程序从只读挂载的 `$PROD_DIR/app/db/spirit.db` 读取数据，按批次写入 PostgreSQL。源 SQLite 不会被修改。

### 6.7 T+75：执行迁移验证

```bash
docker compose --profile migration -f docker-compose.yml \
  run --rm -T data-migrate --mode verify \
  | tee "migration-reports/$SNAPSHOT.verify.json"
```

验证不满足第五节列出的全部条件时，不启动新应用。

### 6.8 T+90：以 PostgreSQL 配置启动单个应用容器

```bash
docker compose --profile postgresql -f docker-compose.yml \
  up -d postgres stream-vault

docker inspect my-stream-vault --format 'status={{.State.Status}} image={{.Config.Image}}'
docker inspect my-stream-vault --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | grep '^SPRING_PROFILES_ACTIVE=docker,postgresql$'

docker logs --since 10m my-stream-vault | tee "migration-reports/$SNAPSHOT.startup.log"
```

必须确认：

- 容器持续为 `running`，没有反复重启；
- 激活配置包含 `docker,postgresql`；
- Flyway、Hibernate 和数据库连接没有报错；
- 不再出现 SQLite `PRAGMA`、锁库或 WAL 报错；
- 应用就绪状态为 `READY`。

### 6.9 T+100：冒烟测试

保持全局暂停，依次验证：

1. 登录和首页数据查询；
2. 视频列表、图文列表和作者页面；
3. 数据库审计与运行状态页面；
4. 手工执行一个作者收藏抓取；
5. 放行并完成一个下载；
6. 如有 HLS 任务，验证一个转码任务；
7. 确认新增数据能写入且主键没有冲突。

### 6.10 T+110：逐项解除暂停

按“抓取 -> 下载 -> HLS/转码”的顺序逐项解除暂停，每一步观察至少 5 分钟。重点查看：

- 应用错误日志和进程超时；
- 队列深度是否正常下降；
- PostgreSQL 活跃连接、锁等待和磁盘增长；
- 同一任务是否出现重复执行；
- 新作品能否正常入库、排队和下载。

到 T+120 仍无异常，才能宣布迁移完成。

## 七、迁移后的保留与观察

- 至少观察 24 小时后再考虑清理旧容器；
- 最终 SQLite 快照、哈希、三份迁移报告和启动日志至少保留 7 天；
- PostgreSQL 命名卷不得在观察期内删除；
- 不要立即对 PostgreSQL 执行额外瘦身，先让 autovacuum 正常工作；
- 将最终上线镜像 digest、Git commit、迁移时间和操作者记录到迁移报告中。

## 八、回滚步骤

出现数据校验失败、持续启动失败、队列无法推进或严重性能问题时执行回滚：

1. 重新启用所有暂停控制并停止 PostgreSQL 版应用：

```bash
cd "$PROD_DIR"
docker stop -t 120 my-stream-vault
```

2. 保存当前 SQLite 文件旁可能生成的 WAL/SHM 文件，不直接删除：

```bash
export ROLLBACK_DIR="migration-snapshots/rollback-artifacts-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$ROLLBACK_DIR"
test ! -e app/db/spirit.db-wal || mv app/db/spirit.db-wal "$ROLLBACK_DIR/"
test ! -e app/db/spirit.db-shm || mv app/db/spirit.db-shm "$ROLLBACK_DIR/"
```

3. 用最终一致性快照恢复 SQLite 主文件：

```bash
cp "migration-snapshots/$SNAPSHOT" app/db/spirit.db
sha256sum app/db/spirit.db "migration-snapshots/$SNAPSHOT"
```

两个哈希必须一致。

4. 使用旧 Compose 和旧镜像启动一个 SQLite 应用容器：

```bash
docker compose -f docker-compose.sqlite.rollback.yml up -d
docker logs --since 10m my-stream-vault
```

5. 确认 SQLite `quick_check`、应用就绪状态、页面查询和队列状态正常后，再逐项解除暂停。

6. 保留 PostgreSQL 数据卷用于诊断，不执行 `down -v`，也不尝试把切换后 PostgreSQL 中的新写入自动合并回 SQLite。

## 九、成功判定清单

- [ ] 新镜像由包含 PostgreSQL 迁移代码的提交构建；
- [ ] 两次隔离演练均从空 PostgreSQL 卷开始并验证成功；
- [ ] 正式快照由停止后的生产库通过 Backup API 生成；
- [ ] 正式 dry-run、load、verify 报告均已归档；
- [ ] PostgreSQL 表行数和瘦身结果通过自动验证；
- [ ] 冒烟试写确认主键序列和时间字段使用正常；
- [ ] 应用以 `docker,postgresql` 配置稳定运行；
- [ ] 抓取、下载、HLS 和队列逐项恢复正常；
- [ ] SQLite 最终快照与旧部署配置仍可用于回滚。
