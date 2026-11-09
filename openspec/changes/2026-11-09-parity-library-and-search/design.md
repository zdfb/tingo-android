# Design: 会议管理与跨会议搜索

## 引擎边界

四个函数都直接调桌面引擎的既有实现，Kotlin 侧不复制业务逻辑：

| FFI 方法 | 复用的引擎实现 |
|---|---|
| `rename_meeting` | `meetings::write_meta`（`normalize_title` 兜底） |
| `set_tags` | `meetings::write_meta` + `normalize_tags` |
| `batch(ids, action)` | `meetings::write_meta` / `meetings::delete` |
| `search(query)` | `terms::search`（把返回的 JSON 转成 typed Record） |
| `storage_stats` | `meetings::files_size`、`disk` 相关 |

`terms::search` 返回的是 `serde_json::Value`，FFI 层负责解析成
`SearchHit { meeting_id, snippet, start_ms, in_minutes }`，让 Kotlin 不碰 JSON。

## 软删除

桌面版删到 macOS 废纸篓，Android 没有等价物，用应用内 `.trash/<id>`：

- 删除 = 把会议目录整体改名到 `<root>/.trash/<id>`（同分区 rename，瞬时、可恢复）
- 恢复 = 改名回 `<root>/<id>`；目标重名时拒绝并提示（不覆盖用户数据）
- 彻底清除 = 递归删除 `.trash/<id>`
- 列表扫描必须跳过 `.trash`（引擎的 `list` 本来就忽略非会议目录，但要显式验证）

## 搜索

桌面版搜索会做"术语扩展 + 差一个字"的模糊匹配，这正是它认得识别错字的原因。
Android 直接复用，不做第二套匹配规则——两套规则必然漂移。

结果按会议分组，每场最多几条命中（引擎已限 `MAX_HITS=5`、`MAX_MEETINGS=30`）。
点击命中跳到详情页对应毫秒（与既有"点句子跳转"同一入口）。

## 批量操作

界面：长按进入多选 → 底部出现操作条（归档 / 标签 / 删音频 / 删除）；全选/反选。
引擎侧一次调用处理一批 id，单个失败不影响其它（返回每个 id 的结果）。
