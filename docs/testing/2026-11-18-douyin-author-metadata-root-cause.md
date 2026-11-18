# 抖音作者元数据缺失：根因与验证

## 已有数据证据

用户 PostgreSQL 查询：视频缺用户名 7,650，缺头像 2,449；图文缺用户名 2,491，缺头像 726。各项有重叠。

按作者档案关联，用户提供的前 15 组结果合计 4,287 条视频：作品 `authorusername` 为空，匹配档案 `username` 有值。该结果证明两份数据不一致，不能证明所有缺失都由某一个下载接口造成。该查询按昵称和用户名分组，也没有排除历史错误作者绑定或重复档案。

用户进一步查询视频 `7298556660748913954`：保存的 JSON 长度 72,587，全文不存在 `unique_id`。第一个 `"author"` 是音乐作者字符串，不能用首次出现位置判断作品作者结构。后续片段展示了作品作者对象：canonical `uid` / `sec_uid`、`web_url`、`nickname`、`verified`、`avatar.urls`；作品包含 `content_id`、`kind`、`fetched_at`。这是转换后的响应结构，与项目支持的 DTK 归一化结构相符，不能直接称为抖音原始 API 响应。尚未确认该对象是否另有 `username` 等字段。

该实际结构复现了头像字段丢失：`author.avatar.urls` 有地址，但此前提取器仅识别 `avatar_300x300` / `avatar_medium` / `avatar_thumb`。本轮补上此结构支持，并验证直链输出与传给下载适配器的详情均能提取头像。用户名缺失则需要区分归一化响应未提供用户名和其他字段别名，不能用头像问题解释用户名问题。

用户对 `jsondata::jsonb->'author'` 列字段名返回 0 行，确认该作者不在 JSON 顶层；原先的顶层查询不足以定位作者字段。JSON 能成功转换为 jsonb，后续可以用递归 JSONPath 检查作者对象而不依赖文本出现位置。新增实际结构兼容修复后，4 组相关 Java 测试共 65 项通过（0 失败、0 错误）。

用户随后递归枚举所有 author 对象的字段，共 22 个不同字段，包括 `sec_uid`、`uid`、`nickname`、`avatar_thumb`、`avatar`、`web_url` 以及 story/关注/认证字段；没有 `unique_id`、`uniqueId`、`username`、`handle` 或 `short_id`。结合全文不存在 `unique_id`，该样本不能归为“保存 JSON 有用户名但作品列未映射”，也不支持“用户名只是使用常见别名”。当前 Java DTK 归一化和 raw JSON 保存代码均不删除这些用户名字段；raw sanitizer 也不会把 unique_id 当敏感字段删除。保存前的输入已缺用户名是与当前代码一致的解释，但历史部署代码版本和 DTK 收到的抖音原始响应尚未取得，仍不能断言抖音原始 API 从未返回用户名。不同作者对象的字段合并列表也不能证明 avatar_thumb 在当时用于入库的那个 author 对象中存在或非空。

## 已复现的源头缺陷

1. `douyin.py:fetch_video` 手写字段投影没有 `unique_id`、`sec_uid` 或嵌套 `author`，而 Java `DouUtil.getBogusWithCookie` 读取这些字段。上游有用户名也无法传到 Java。原输出还使用 Python 字典表示法而非标准 JSON。
2. `fetch_user_like_videos`、`fetch_user_post_videos`、`fetch_user_collects_videos`、`fetch_user_feed_videos` 都从 F2 展平结果重建对象，丢弃原始作者用户名、签名等。推荐列表还没有输出头像。
3. 当前增量 `normalize_aweme` 摘要没有用户名、签名和独立 `sec_uid`。下载快照已经保留 `unique_id`，所以此处主要影响摘要消费者，不能据此认定当前所有收藏下载都丢用户名。
4. `build_download_snapshot` 只保留 `avatar_thumb`，会丢弃上游仅提供的 `avatar_medium`、`avatar_300x300`。
5. DTK `coerceDetail` 只归一化嵌套 author/user；直链解析和适配器读取嵌套 author，因此响应平铺的作者字段虽然仍在 raw JSON 中，却不会成为作品作者信息。DTK 头像提取只支持对象形式 `avatar_thumb`。
6. 旧版图文执行器先确定作者字段，之后才抓 DTK 补充详情，补充详情仅写入 JSON。补充数据有用户名也不会用于当次入库。
7. 旧版重新下载将部分响应中的空作者字段直接写回已有作品，可能抹掉有效用户名和头像。

## 自动补全为什么遗漏

`upsertAuthor` / `upsertCanonicalAuthor` 将本次观察合并进作者档案，生成的 `AuthorObservation` 是保存后的档案。队列 `inspect` 检查的也是这份档案，而不是本次作品。

如果作者档案早已有用户名和头像，本次作品响应缺字段，作品仍写空值；合并后的作者档案却完整，故不会排远端补全任务。每日 `enqueueMissingWorkAuthors` 同样仅判断档案缺字段。只有 `applyExternalDouyinProfile` 的远端刷新路径批量同步当时已经存在的作品；之后新入库的作品仍会再次缺字段。

这是重复存储作者信息后的入库一致性缺口。单纯扩大远端重试或增加每作品一次作者 API 请求，会掩盖字段投影错误并增加请求量。

现有日志也不支持“自动补全完全没有运行”：同步的 2026-11-17 日志中有 29 次作者补全开始、28 次完成、1 次 FETCH_FAILED 后重试。完成日志不表示全部作者字段最终非空。

## DTK 接口侧源码核对与解析器回放

审查了用户公开仓库 `jonysun/Douyin_TikTok_Download_API` 的 main，核对到的提交为 `4f0bed8483c35a980315d9c7b3a1d4a1119ad2b2`（2026-10-02）。源码下载保存在忽略的 `.tmp/dtk-author-audit/`，不是部署中的运行容器，不能据此确认 NAS 正在运行该提交。

- `src/dtk/platforms/douyin/parser.py:152` 的 `author_from_node` 显式提取 `unique_id`，再尝试 `short_id`。`content_from_node`（作品详情和列表共用）及 `parse_author`（作者资料）都调用它。没有发现将非空用户名删除的投影。文件提交历史中，2026-09-11 的 `3093c72d58e2dafa485285f7c9d056b62eb5f97e` 仅增加 canonical `sec_uid`；其上下文已经存在相同的用户名提取逻辑。
- `src/dtk/models/content.py:92` 的 `Author` 将 `unique_id` 声明为可空字段；`stats` 注释明确说明列表接口通常返回精简作者信息。用户名是否实际省略仍应以具体响应为准。
- `src/dtk/platforms/douyin/parser.py:345` 附近 `Content.raw=detail.raw()` 保留输入作品对象。`src/dtk/services/fetch.py:778` 的 `_dump` 使用 `model_dump(mode="json")`，`include_raw` 只控制是否去掉 `raw` 子树，不删除用户名字段。StreamVault 列表请求已带 `include_raw=true`。
- StreamVault `RawMetadataSanitizer.sanitize` 最后使用默认 `JSON.toJSONString`。默认序列化会省略 `null` 字段：DTK 的 `unique_id:null` 在数据库中可能表现为完全没有 `unique_id`。新增本地回归同时证明：非空用户名在归一化作者和 raw 作者中均能完整保存。

直接运行上述 DTK 源码的纯解析器，使用其合成 `video_normal.json` / `user_profile.json` 夹具进行 4 项断言：有用户名时保留；去掉 `unique_id` / `short_id` 时结果为 None；只保留 `short_id` 时得到 fallback；独立作者资料仍提供用户名。4 项均通过。结果位于 `.tmp/dtk-author-audit-parser-result.json`。这是源码回放，不是对用户历史作品进行实时抓取。

因此，本样本不能归为已复现的 F2 投影丢字段，DTK 当前公开转换逻辑也不支持“非空用户名被主动删除”的解释。与证据一致的链条是：作品/列表作者片段没有公开用户名 → DTK 保留为 null → StreamVault 入库时未使用已知档案 → 完整档案不触发远端补全 → 作品长期为空。前一箭头对该历史请求仍缺 on-wire 响应和运行版本证据，不能提升为对全部抖音接口的断言。

尝试只读获取同步日志中实际 DTK 服务的 `/openapi.json`，返回 HTTP 403，未取得运行版本或实时业务响应；没有修改 DTK 服务或远端数据库。进一步确认运行实例与源码是否一致，需要部署镜像版本或该实例的响应证据，不能用合成夹具替代。

## 本次修复范围

- F2 从原始作品/列表响应保留作者字段，单条输出标准 JSON。
- 增量摘要和下载快照保留相应作者字段与备用头像。
- DTK 支持平铺作者信息和已有通用头像提取逻辑。
- 图文将已抓到的同作品、同作者补充详情合并后再解析；拒绝不同作品或冲突的 canonical UID。
- 保存当次作品前，按平台和 canonical UID 读取已有作者档案，只填当次作品缺少的字段，保留本次有效观察。不按昵称猜身份，不增加外部请求，不在这里批量更新历史作品。
- 重新下载的空字段保留旧值。

远端补全队列继续负责获取本地未知的作者信息。历史批量修复未执行。

## 验证与边界

Python 回归先复现列表摘要及四种旧版批量命令丢用户名、单条输出不是标准 JSON；DTK HTTP fixture 先复现平铺作者和备用头像丢失。

修复后验证包括原始 author 保真、实际下载入口的保存前合并、按 UID 匹配和不覆盖有效观察、拒绝冲突图文补充响应。

验证结果：相关 Java 测试 102 项、Python 增量/命令测试 99 项全部通过；定向 diff 空白检查通过。包含重新下载部分作者响应不会清空已有字段的公开入口测试。本报告记录时本轮修改尚未推送或部署。

现已取得一个历史作品保存 JSON 的结构片段和全部嵌套 author 对象的去重字段列表，尚未取得对应上游原始响应及历史部署代码版本。无法把 7,650 / 2,491 条逐条归因；不能仅凭作品响应缺少用户名认定作者未设置用户名（本地匹配档案已有用户名）。进一步区分抖音作品接口字段裁剪与 DTK 转换丢失，需要接口侧证据，继续对保存后的数据库执行缺失统计无法跨越这一边界。最后才评估历史补齐。
