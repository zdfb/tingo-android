# Change: 会议管理与跨会议搜索（桌面端功能对齐第一批）

## Why

桌面版 v0.5.2 已经能把会议库当资料库来用：改名、打标签、批量归档/删音频/删除、
删除回收站、看存储占用、跨会议全文搜索（还认得识别错的写法）。Android 现在只能
浏览、归档、单条删除、在标题和标签上做子串过滤——一场会议多点就找不着了。

这是"功能级别完整复刻"的第一批，选它是因为日常使用最频繁、且不依赖其它未做的功能。
对照表见 `docs/android-parity.md`（#13–#19）。

## What Changes

- **引擎边界（engine-ffi）**新增四个用例函数，全部复用桌面引擎已有实现（不重写逻辑）：
  - `rename_meeting(id, title)` → 写 `meta.json`
  - `set_tags(id, tags)` → 写 `meta.json`（归一化交给引擎）
  - `batch(ids, action)` → `archive` / `unarchive` / `tag_add` / `tag_remove` / `delete_audio` / `delete`
  - `search(query)` → 跨会议命中：会议 id、命中片段、出处毫秒（复用 `terms::search` 的模糊匹配）
  - `storage_stats()` → 每场会议体积、全库总量、可清理的残留文件
- **Android 界面**：会议库支持长按多选 + 批量操作条；会议详情可改名、编辑标签；
  会议库里搜索走引擎全文检索（标题/标签/纪要正文/转写正文），命中片段高亮出处；
  新增"最近删除"视图（软删除到 `.trash/`，可恢复或彻底清除）；
  设置页新增"存储空间"，显示占用并允许清理残留文件。

## Impact

- `app/engine-ffi/src/lib.rs` 增加 5 个 `#[uniffi::export]` 方法与对应 Record
- Android：`MeetingRepository` / `LibraryViewModel` / `LibraryScreen` / `DetailScreen` /
  `SettingsViewModel` 与新组件 `BatchActionBar`、`RenameDialog`、`TagEditor`
- 规格：`meeting-library-read` 增加批量操作、软删除、跨会议搜索、存储统计四条需求
- 风险：软删除要能跨进程重启保持（目录 `.trash/<id>`），恢复时同名冲突要提示
