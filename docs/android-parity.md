# 桌面端 → Android 功能对照（v0.5.2）

对照基准：`origin/main` @ `fa4caa5`（桌面版 v0.5.2）。桌面侧的权威清单是
`app/engine/src/server.rs` 的路由表（30+ 条）与 `app/src/views/*.ts` 的界面。

Android 侧现状由 `app/android/app/src/main/kotlin/**` 与
`app/engine-ffi/src/lib.rs`（当前只导出 11 个函数）共同决定。

| # | 能力 | 桌面端 | Android | 差距 |
|---|---|---|---|---|
| 1 | 录音（麦克风） | `/api/record/*` | 已有（前台服务） | — |
| 2 | 系统内录 | `/api/record/*`（macOS 系统音频） | 已有（AudioPlaybackCapture，尽力而为） | — |
| 3 | 静音/结束自动停录 | `autostop.rs` | 无 | 缺 |
| 4 | 转写（Paraformer + 标点 + 声纹聚类） | `asr.rs` + `cluster.rs` | 已有（sherpa-onnx） | — |
| 5 | Apple Speech 可选后端 | `apple_speech.rs` | 不适用（macOS 专有） | 不迁移 |
| 6 | 纪要生成 | `summarize.rs`（LLM API） | 已有（本轮改为远端 API） | — |
| 7 | 纪要历史版本 | `/api/meetings/{id}/minutes/history` | 无 | 缺 |
| 8 | 纪要自检与重试 | `validate` + retry | 已有 | — |
| 9 | 鱼骨图 | `fishbone.rs` | 已有（解析 + 卡片） | — |
| 10 | 说话人命名（手改） | `/api/meetings/{id}/speakers` | FFI 有 `speaker_naming` 能力位，界面未接 | 缺界面 |
| 11 | AI 猜说话人名字 | `/api/meetings/{id}/speakers/guess` | 无 | 缺 |
| 12 | 人名/术语表（跨会议） | `terms.rs` | 无 | 缺 |
| 13 | 跨会议搜索（含识别错字模糊匹配） | `terms::search` + `/api/search` | 只有本地标题/标签过滤 | 缺 |
| 14 | 会议重命名 | `/api/meetings/{id}/meta` | 无 | 缺 |
| 15 | 标签 | `/api/meetings/{id}/meta`（tags） | 只展示、不过滤不编辑 | 缺 |
| 16 | 归档 | `/api/meetings/{id}`（archived） | 已有 | — |
| 17 | 批量操作（归档/加标签/删音频/删除） | `/api/meetings/batch` | 无 | 缺 |
| 18 | 删除到废纸篓 + 恢复 | `meetings::delete`（macOS 废纸篓） | 直接删除 | 缺（Android 用"最近删除"目录） |
| 19 | 存储空间管理与清理 | `disk.rs` + `/api/env` | 无 | 缺 |
| 20 | 导出 Markdown / ZIP | `/api/meetings/{id}/minutes` | 已有（zip + md 分享） | — |
| 21 | 导出 Word（docx） | `/api/meetings/{id}/docx` | 无 | 缺 |
| 22 | 导出 SRT 字幕 | `/api/meetings/{id}/srt` | 无 | 缺 |
| 23 | 导出 PDF | `/api/meetings/{id}/pdf` | 无 | 缺 |
| 24 | 导出 Excalidraw（鱼骨图） | `/api/meetings/{id}/excalidraw` | 无 | 缺 |
| 25 | 导入音频 | `/api/import` | 无 | 缺 |
| 26 | 内置示例会议 / 新手引导 | `/api/demo` + `welcome.ts` | 无 | 缺 |
| 27 | 检查更新（校验安装包） | `update.rs` | 无（走应用商店，不迁移自更新） | 不做自更新 |
| 28 | Obsidian 导出 | `/api/meetings/{id}/obsidian` | 无 | 缺（可后置） |
| 29 | Webhook 推送 | `/api/meetings/{id}/push` | 无 | 缺（可后置） |
| 30 | 热词 | `asr.rs`（hotwords） | 无 | 缺 |
| 31 | 会议库位置可切换 | `/api/config`（storage_root） | 固定 | 缺 |
| 32 | 服务商预设 + 拉模型列表 | `/api/llm/providers`、`/api/llm/models` | 无（要手填四项） | 缺 |
| 33 | 界面语言 | `i18n.rs` | 已有（中/英） | — |
| 34 | 备份/恢复整库 | 桌面无（靠 iCloud/文件夹） | 已有（zip 备份） | Android 独有 |

## 实施顺序（按日常使用价值）

1. **会议管理 + 搜索**（#13–#19）：重命名、标签、批量、最近删除、存储空间、跨会议搜索
2. **说话人与术语**（#10–#12）：手改名字、AI 猜名字、跨会议术语表
3. **纪要历史 + 导出**（#7、#21–#24）：版本历史、Word/SRT/PDF/Excalidraw
4. **配置补齐**（#30–#32）：热词、库位置、服务商预设与模型列表
5. **入门与导入**（#25、#26）：导入音频、示例会议、引导

不迁移：Apple Speech（macOS 专有）、桌面自更新（Android 走商店）。
