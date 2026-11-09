# 桌面端 → Android 功能对照（v0.5.2）

对照基准：`origin/main` @ `fa4caa5`（桌面版 v0.5.2）。桌面侧的权威清单是
`app/engine/src/server.rs` 的路由表（30+ 条）与 `app/src/views/*.ts` 的界面。

Android 侧现状由 `app/android/app/src/main/kotlin/**` 与
`app/engine-ffi/src/lib.rs`（第一批后导出 20 个函数）共同决定。

| # | 能力 | 桌面端 | Android | 差距 |
|---|---|---|---|---|
| 1 | 录音（麦克风） | `/api/record/*` | 已有（前台服务） | — |
| 2 | 系统内录 | `/api/record/*`（macOS 系统音频） | 已有（AudioPlaybackCapture，尽力而为） | — |
| 3 | 静音/结束自动停录 | `autostop.rs` | 无 | 缺（下一批） |
| 4 | 转写（Paraformer + 标点 + 声纹聚类） | `asr.rs` + `cluster.rs` | 已有（sherpa-onnx） | — |
| 5 | Apple Speech 可选后端 | `apple_speech.rs` | 不适用（macOS 专有） | 不迁移 |
| 6 | 纪要生成 | `summarize.rs`（LLM API） | 已有（本轮改为远端 API） | — |
| 7 | 纪要历史版本（含手工编辑纪要） | `/api/meetings/{id}/minutes/history` | 已有（详情页溢出菜单：编辑 / 历史 / 恢复 / 删除） | — |
| 8 | 纪要自检与重试 | `validate` + retry | 已有 | — |
| 9 | 鱼骨图 | `fishbone.rs` | 已有（解析 + 卡片） | — |
| 10 | 说话人命名（手改） | `/api/meetings/{id}/speakers` | 已有（详情页说话人 chip 点开改名） | — |
| 11 | AI 猜说话人名字 | `/api/meetings/{id}/speakers/guess` | 已有（提示词与解析复用引擎，调模型走用户配置） | — |
| 12 | 人名/术语表（跨会议） | `terms.rs`（从各场纪要的 terms 块汇总） | 引擎已能读（FFI `glossary`）；App 内只读展示待做 | 缺界面 |
| 13 | 跨会议搜索（含识别错字模糊匹配） | `terms::search` + `/api/search` | 已接（引擎全文检索、命中片段、跳转时间点） | — |
| 14 | 会议重命名 | `/api/meetings/{id}/meta` | 已有（详情页溢出菜单） | — |
| 15 | 标签 | `/api/meetings/{id}/meta`（tags） | 已有（详情编辑、批量加/减） | — |
| 16 | 归档 | `/api/meetings/{id}`（archived） | 已有 | — |
| 17 | 批量操作（归档/加标签/删音频/删除） | `/api/meetings/batch` | 已有（多选 + 批量条） | — |
| 18 | 删除到废纸篓 + 恢复 | macOS 废纸篓 | 已有（`.trash/<id>`，可恢复/彻底清除） | — |
| 19 | 存储空间管理与清理 | `disk.rs` + `/api/storage/cleanup` | 已有（总量/音频/残留 + 清理残留） | — |
| 20 | 导出 Markdown / ZIP | `/api/meetings/{id}/minutes` | 已有（zip + md 分享） | — |
| 21 | 导出 Word（docx） | `/api/meetings/{id}/docx`（macOS `textutil`） | 已有（直接写 OOXML；抬头/页脚/鱼骨图文字与桌面同口径） | 实现不同，内容等价 |
| 22 | 导出 SRT 字幕 | `/api/meetings/{id}/srt` | 已有（命名与桌面一致，导出后走系统分享） | — |
| 23 | 导出 PDF | `/api/meetings/{id}/pdf`（系统 WebKit 打印） | 已有（`PdfDocument` 排 A4） | 实现不同；**尚未画鱼骨图示意，也无品牌页眉页脚** |
| 24 | 导出 Excalidraw（鱼骨图） | `/api/meetings/{id}/excalidraw` | 已有（复用引擎 `fishbone::excalidraw`，与桌面同一份） | — |
| 25 | 导入音频 | `/api/import`（引擎调 macOS `afconvert` 解码） | 已有：系统选择器 → `MediaCodec` 解码成 16k 单声道 → 引擎落库 → 转写 | 解码器不同，链路一致 |
| 26 | 内置示例会议 / 新手引导 | `/api/demo` + `welcome.ts` | 无 | 缺 |
| 27 | 检查更新（校验安装包） | `update.rs` | 无（走应用商店，不迁移自更新） | 不做自更新 |
| 28 | Obsidian 导出 | `/api/meetings/{id}/obsidian` | 无 | 缺（可后置） |
| 29 | Webhook 推送 | `/api/meetings/{id}/push` | 无 | 缺（可后置） |
| 30 | 热词 | `asr.rs`（仅 FunAsrNano 识别用）/ `summarize.rs`（glossary） | 已有：设置页编辑 + 提示词注入；**识别阶段不生效**（Paraformer 无热词通道） | 识别侧差异 |
| 31 | 会议库位置可切换 | `/api/config`（storage_root） | 固定 | 缺 |
| 32 | 服务商预设 + 拉模型列表 | `/api/llm/providers`、`/api/llm/models` | 无（要手填四项） | 缺 |
| 33 | 界面语言 | `i18n.rs` | 已有（中/英） | — |
| 34 | 备份/恢复整库 | 桌面无（靠 iCloud/文件夹） | 已有（zip 备份） | Android 独有 |

## 实施顺序（按日常使用价值）

1. ~~**会议管理 + 搜索**（#13–#19）~~ 已完成（`2026-11-09-parity-library-and-search`）
2. ~~**说话人与术语**（#10–#12）~~ 已完成说话人部分；术语表编辑随热词一起做：手改名字、AI 猜名字、跨会议术语表
3. **纪要历史 + 导出**（#7、#21–#24）：版本历史、Word/SRT/PDF/Excalidraw
4. **配置补齐**（#30–#32）：热词与模型列表已完成；剩库位置、服务商预设
5. **入门与导入**（#25、#26）：导入音频、示例会议、引导

不迁移：Apple Speech（macOS 专有）、桌面自更新（Android 走商店）。

## 界面复刻清单（桌面 `app/index.html` + `app/src/style.css` + `views/`）

桌面外壳是「左侧边栏 + 右侧主区」：侧栏含品牌、三条导航（新会议 / 会议记录 / 设置）、
运行中任务区、最近会议列表、环境信息块；主区按路由换。手机上没有横向空间，
导航落到底栏（已实现），其余区块要按屏幕重排，不能照搬位置：

| 桌面区块 | Android 现状 | 计划 |
|---|---|---|
| 侧栏导航三条 | 底栏三条（录制 / 会议 / 设置） | 已对齐 |
| 品牌区（图标 + Tingo 听过） | 会议库标题行只有文字 | 待做：补品牌图标与副标题 |
| `side-tasks` 运行中任务 | 只有录制中状态条 | 待做：做成"进行中任务"面板（转写/纪要/导入） |
| `recent` 最近会议 | 会议库整列即列表 | 已完成：首页"最近 3 场"卡片 |
| `env` 环境块（占用/时长/版本） | 设置页有存储一节 | 已完成：首页底部状态行（模型就绪 / 纪要服务 / 版本） |
| 拖拽导入覆盖层 | 无（手机没有拖拽） | 换成"导入音频"入口（第五批） |
| `modal` 通用弹层 | 各页各自 AlertDialog | 抽成统一的 TingoModal 后统一观感 |
| toast | 各页错误条 | 抽成统一 toast |

顺序：先把首页（新会议）与会议库的视觉对齐，再统一弹层与提示，最后做导入与引导。

已知差距（写在明处，不装作已经一致）：

- 导入只接受音频/视频文件（十个扩展名，与桌面一致）；导入后库里存的是 16 kHz 单声道 wav，
  原文件不进库（桌面保留原扩展名，解码在转写时发生）
- 会议库位置不可切换：Android 的库固定在应用外部目录（可被"文件"App 看到），
  桌面可以指向任意目录（#31 仍缺）

- 导出仅覆盖"当前版本"，桌面还能导出某个历史版本（`?version=`）
- Android 的 PDF只有文字排版，没有品牌页眉页脚，也没有鱼骨图示意（桌面用 HTML/CSS 画）

进度：首页的「最近 3 场」与「环境块状态行」已完成（`RecordScreen` 的 `RecentAndStatus`，
状态由 `AppContainer` 的模型/服务/版本实际状态得出，真机用例 `HomeScreenInstrumentedTest` 把关）。
