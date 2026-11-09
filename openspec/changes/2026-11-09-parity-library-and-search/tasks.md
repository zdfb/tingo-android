# Tasks

## 1. 引擎边界（Rust / UniFFI）

- [x] 1.1 `rename_meeting(id, title)`
- [x] 1.2 `set_tags(id, tags)`
- [x] 1.3 `batch(ids, action)` → 逐 id 结果
- [x] 1.4 `search(query)` → typed `SearchHit`
- [x] 1.5 `storage_stats()` → 每场体积 + 残留文件
- [x] 1.6 软删除：`delete_to_trash` / `restore_from_trash` / `purge_trash` / `list_trash`
- [x] 1.7 引擎单测（cargo test）覆盖以上行为

## 2. Android 数据层

- [x] 2.1 `MeetingRepository` 暴露改名/标签/批量/搜索/存储/回收站
- [x] 2.2 `LibraryViewModel`：多选状态、批量执行、搜索改为引擎全文检索、回收站视图
- [x] 2.3 `SettingsViewModel`：存储统计与清理

## 3. Android 界面

- [x] 3.1 会议库：长按多选 + 批量操作条（归档/标签/删音频/删除），全选
- [x] 3.2 详情页：改名、编辑标签
- [x] 3.3 搜索结果：按会议分组、显示命中片段、点击跳到时间点
- [x] 3.4 回收站视图：恢复 / 彻底清除
- [x] 3.5 设置页存储空间：占用与清理

## 4. 收尾

- [x] 4.1 规格 `meeting-library-read` 增量
- [x] 4.2 单测（JVM）+ 设备测试全绿
- [x] 4.3 双轴 code review
