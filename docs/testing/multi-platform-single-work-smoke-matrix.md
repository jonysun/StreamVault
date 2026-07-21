# 多平台单作品适配冒烟矩阵

验证日期：2026-07-21

## 当前结论

- 统一 Web 入口是 `/admin/directData`。`解析预览` 仍提交 `type=2`，`下载入库` 仍提交 `type=1`，现有 `/admin/api/directData` 参数没有改变。
- 公共入口 `/api/processingVideos`、`/api/directData` 以及后台入口 `/admin/api/directData` 保留原 Ajax 响应外壳；启用新适配器时只增加规范化字段和任务字段。
- 当前开发、生产和 Docker 配置中的 10 个开关全部是 `legacy`。本轮没有默认启用任何新适配器。
- 音频作品不在范围内。B 站和 YouTube 的独立音轨只用于合并成最终视频，不作为音频作品入库。
- 抖音、B 站原有批量收藏/监控仍走旧实现；其他平台的批量监控尚未实现，后续可在单作品在线矩阵通过后增加各平台采集器。

状态说明：`自动通过` 表示使用固定夹具验证了解析、下载结果和字段映射；`待在线验证` 表示代码已实现但没有用真实作品和真实 Cookie 完成端到端验证；`不支持` 表示适配器会明确拒绝，且不会部分入库。

## 平台能力

| 平台 | 后端实现 | 已实现的单作品类型 | 明确边界 | 自动结果 | 在线结果 | 默认开关 |
| --- | --- | --- | --- | --- | --- | --- |
| 抖音 | `DouyinPlatformAdapter` + f2 `fetch_work_data` | 视频、图文、图片/视频混合 | 预览只取元数据；规范化 `sec_uid`、公开用户名和作者签名 | 自动通过 | 待在线验证：需要可用 f2 环境、Cookie 和固定作品 | `legacy` |
| B 站 | `BilibiliPlatformAdapter` + BiliUtil 网关 | 普通投稿视频、分 P 视频；DURL 或 DASH 合并 | 每个 `cid` 是一个作品；番剧、影视和直播明确拒绝 | 自动通过 | 待在线验证：需要可用 Cookie、ffmpeg 和固定 BV/分 P | `legacy` |
| YouTube | `YtDlpPlatformAdapter` + yt-dlp | 普通视频、Shorts，单文件或音视频合并 | 播放列表、频道、多视频集合、进行中直播和纯音频明确拒绝 | 自动通过 | 待在线验证：需要 yt-dlp、网络和固定公开视频 | `legacy` |
| 快手 | `KuaishouPlatformAdapter` + KuaishouParser | 视频；优先 H.265，回退 H.264 | 当前不承诺图文；解析和下载 Cookie 通过独立通道传递 | 自动通过 | 待在线验证：缺少可用快手 Cookie 和固定作品 | `legacy` |
| 小红书 | `XiaohongshuPlatformAdapter` + HongShuExecutor | 视频、图文、图片/视频混合 | 资源按平台顺序保存；Cookie 不进入元数据或请求头输出 | 自动通过 | 待在线验证：缺少可用小红书 Cookie 和固定笔记 | `legacy` |
| 微博 | `WeiboPlatformAdapter` + WeiBoExecutor | 视频、图文、图片/视频混合 | 视频选择最高可用码率；资源按帖子顺序保存 | 自动通过 | 待在线验证：缺少可用微博 Cookie 和固定微博 | `legacy` |
| Twitter/X | `TwitterPlatformAdapter` + yt-dlp 元数据网关 | 单条推文中的多视频，作为一个有序作品 | 当前不承诺纯图片或图片轮播；不允许部分资源入库 | 自动通过 | 待在线验证：需要网络和固定公开推文 | `legacy` |
| Instagram | `InstagramPlatformAdapter` + yt-dlp | Reel 视频 | 图片、轮播和集合明确拒绝 | 自动通过 | 待在线验证：需要网络、可能需要 Cookie 和固定 Reel | `legacy` |
| TikTok | `TikTokPlatformAdapter` + yt-dlp | 视频 | Photo Mode/图片集合明确拒绝 | 自动通过 | 待在线验证：需要网络、可能需要 Cookie 和固定视频 | `legacy` |
| 通用 yt-dlp | `YtDlpPlatformAdapter(generic)` | 未命名站点的单个视频作品，尽力解析 | 不能接管上述正式平台；播放列表、直播、集合和纯音频明确拒绝 | 自动通过 | 待按具体站点逐项验证，不视为正式支持 | `legacy` |

这里的“通用 yt-dlp”只表示未知站点可由 yt-dlp 尝试提取一个视频，不代表该站点已经适配或承诺长期可用。解析出的 extractor 名称会生成 `GENERIC` 支持等级和稳定的平台键；正式平台永远由自己的适配器拥有。

## 字段收口

所有适配器先输出同一个 `WorkMetadata`：

`platformKey`、`platformDisplayName`、`supportTier`、`workId`、`contentType`、`title`、`description`、`authorId`、`authorUsername`、`authorName`、`authorAvatar`、`authorHomepage`、`authorSignature`、`publishTime`、`sourceUrl`、`originalAddress`、`coverUrl`、有序 `mediaResources` 和内部 `rawMetadata`。

统一持久化层再写入现有 `biz_video`、`biz_graphic_content` 和 `biz_author_profile`。旧字段继续保留并继续供视频列表、图文列表、媒体播放、作者页、UniApp 和旧 API 使用；新增规范字段不会替代旧字段：

| 新字段 | 表 | 用途 | 兼容规则 |
| --- | --- | --- | --- |
| `platformkey` | 三张表 | 稳定平台键 | 旧 `videoplatform` / `platform` 保留 |
| `contenttype` | 视频、图文 | `VIDEO` / `GRAPHIC` / `MIXED` | 仍分别落入原视频表或图文表 |
| `authorhomepage` | 视频、图文 | 作者主页 | 原作者 UID、用户名、昵称字段保留 |
| `signature` | 作者表 | 作者签名 | 可空，不影响旧作者记录 |
| `metadataoverrides`、`metadataeditedat`、`metadataeditedby` | 视频、图文 | 人工编辑覆盖及审计 | 刷新、重下后重新应用覆盖值 |
| `privacy`、`favorite` | 图文 | 与视频表能力对齐 | 旧视频字段和行为不变 |

如果后续平台返回的信息无法放入上述规范字段，应先报告字段冲突，不直接修改或复用旧字段语义。

## 人工编辑与维护接口

以下接口已预留，均要求后台管理员会话：

| 接口 | 请求 | 行为 |
| --- | --- | --- |
| `POST /admin/api/updateWorkMetadata` | `workType`、`id`、`overrides`、`syncAuthorProfile` | 可编辑标题、简介、作者名/头像/主页、发布时间、来源地址、标签、隐私和收藏；平台键、作品 ID、本地媒体和原始元数据不可改 |
| `POST /admin/api/refreshWorkMetadata` | `workType`、`id` | 只重新解析元数据，不下载；锁定平台和作品身份，并重新应用人工覆盖 |
| `POST /admin/api/redownloadWork` | `workType`、`id` | 在临时目录下载并校验后替换；失败时保留旧媒体，成功后重建 HLS |

## 自动与本地验证

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 完整 Maven 回归 | 通过 | 172 tests，0 failures，0 errors，0 skipped |
| 平台解析和下载夹具 | 通过 | Douyin、Bilibili、YouTube/通用 yt-dlp、Kuaishou、Xiaohongshu、Weibo、Twitter/X、Instagram、TikTok 适配器测试 |
| 旧 API 参数和响应外壳 | 通过 | `SingleWorkApiCompatibilityTest` |
| 新旧路由只执行一次 | 通过 | `AnalysisServiceRoutingTest`；新适配器失败不回落到旧路径或通用持久化 |
| 预览无下载/无入库 | 通过 | 平台适配器测试和 `WorkIngestServiceTest` |
| 去重、阻止重复作品 | 通过 | `WorkDeduplicationServiceTest` |
| 人工覆盖、刷新、重下回滚 | 通过 | `WorkMetadataEditServiceTest`、`WorkRefreshServiceTest`、`WorkRedownloadServiceTest` |
| HLS、通知、处理历史 | 通过（服务级） | `HlsTranscodeServiceTest`、`WorkIngestServiceTest`、`ProcessHistoryServiceTest` |
| 列表/播放所需兼容字段 | 通过（DTO/服务级） | `MediaFeedServiceTest`、`VideoDataServiceFindAllTest`、`GraphicContentServiceTest`、`PlatformMetadataCompatibilityServiceTest` |
| Web 单作品入口 | 通过 | 桌面 1280x720、移动 390x844；模式切换和无横向溢出；`DirectDataTemplateTest` |
| Cookie/签名数据扫描 | 通过 | `src/test/resources` 无 Cookie、Authorization、签名或 token 模式；本次服务日志只有表名 `biz_cookies_config` 命中关键词，没有值 |
| 默认开关 | 通过 | dev/prod/docker 各 10 个 `streamvault.adapter.*` 均为 `legacy` |

## 旧数据库迁移

迁移只在 `db/spirit.db` 的副本上执行，源库未修改。代表性旧库为 1,088,868,352 字节，迁移前目标表没有本轮新增列。

| 表 | 旧行数 | `platformkey` 回填 | 旧列数据 SHA-256 |
| --- | ---: | ---: | --- |
| `biz_video` | 7,995 | 7,995 | `1d600d2c9755ababbd405a1f0a7948057ce83af36759140d5d639e1b4820db1d` |
| `biz_graphic_content` | 1,943 | 1,943 | `19f6f122088c8b058b9e5c7848ebe293b343b47dd139a91151fc47b67adb7505` |
| `biz_author_profile` | 124 | 124 | `1bc6acc651b23bf8c382082e27ed14430cadac20ef53dc597f57a46c0e3ae1f8` |

项目自身的 `PlatformSchemaInitializer` 在同一副本上连续执行两次。第一次迁移前、第一次迁移后和第二次迁移后的全部旧列哈希一致；第一次与第二次的列清单完全一致。因此本次迁移对该旧库是幂等的，且没有修改旧字段值。

## 尚未完成的在线验收

本次没有提交真实平台 URL、有效 Cookie 或允许保留的测试账号，因此没有伪造“在线通过”结论。以下项目必须在隔离测试库和测试下载目录中完成后，才允许把对应开关改为 `new`：

1. 每个平台、每个承诺内容类型至少一个真实作品，分别执行预览、首次入库、重复提交和播放。
2. 验证来源跳转、作者跳转、作者聚合、手动编辑、刷新、重下、隐私、收藏、标签、删除/恢复和处理历史。
3. 验证 HLS、通知以及现有 Web、UniApp、原生端、桌面端、扩展和 API 消费者。
4. 使用旧路径验证抖音和 B 站收藏/监控任务；新单作品适配器不得改变这些任务。
5. 检查运行日志、失败响应和保存的 `rawMetadata`，确认没有 Cookie、签名 URL 或鉴权头泄漏。

在线矩阵未完成前，所有平台继续使用 `legacy`，且不修改 README 中的默认可用范围。
