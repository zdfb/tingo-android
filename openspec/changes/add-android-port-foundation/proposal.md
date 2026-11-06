# Proposal

## Why

Tingo（听过）目前只有 macOS 桌面形态：Rust `tingo-engine`（约 8000 行，承担转写、说话人区分、纪要、鱼骨图、会议存储）+ Tauri v2 外壳 + 4062 行 TypeScript 界面。而中文会议纪要的真实使用场景大多发生在手机上——会议在手机里开，会后就要纪要。

Android 平台的约束与桌面根本不同（无法录制第三方 App 的 VoIP 音频、无 `~/Documents`、无子进程、进程随时被回收），因此不能简单套壳，必须先建立一层可长期演进的移植基础：一个能构建、能在模拟器上跑、能跨越 Kotlin↔Rust 边界调用的最小骨架。

本变更只做**地基**，不做完整功能对齐；完整功能由后续独立变更逐块交付。

## What Changes

- 新增 `app/android/` Gradle 工程：Kotlin + Jetpack Compose + Material 3，`minSdk 26`、仅 `arm64-v8a`、`compileSdk 36` / `targetSdk 36`
- 新增 `app/engine-ffi/` crate：UniFFI 边界层，**BREAKING**（对 Rust workspace 而言）地把 `tingo-engine` 拆成"可移植核心"与"桌面专属"两部分，通过 cargo feature 隔离：
  - `http-server`（axum，桌面专用）、`sherpa-asr`（sherpa-onnx，桌面专用）、`desktop-shell`（子进程/`/usr/bin/open`/statvfs 等）
  - Android 侧只启用空 feature 集，得到纯逻辑库
- 新增能力探测桥接：App 启动时通过 FFI 读取引擎版本与能力矩阵，界面据此决定可用功能（为后续"ASR 在 Kotlin / 逻辑在 Rust"的分工打下契约）
- 新增会议库**只读**纵切：扫描会议目录 → `meta.json` / `transcript.json` / `纪要.md` → Compose 列表与详情展示（作为端到端打通的示踪弹）
- 新增构建与验证管线：`cargo-ndk` 交叉编译 → Gradle 打包 APK → 模拟器仪表测试；CI 增加 Android job
- 新增工程文档：`app/android/README.md` 记录本地环境、镜像配置、构建与真机/模拟器验证步骤
- 不做：录音采集、ASR、纪要生成、导出与集成（各自后续变更）

## Capabilities

### New Capabilities

- `android-app-shell`: Android 应用外壳——进程启动、权限声明、底部导航骨架、手机单栏与平板/横屏双栏自适应、中英文切换、应用内数据目录约定
- `engine-bridge`: Kotlin 与 Rust 之间的桥接契约——类型映射、能力探测、错误语义与降级、并发与取消、崩溃隔离边界
- `meeting-library-read`: 会议库的只读访问——目录发现、`meta.json` / `transcript.json` / `纪要.md` 解析、列表与详情展示、与桌面端文件格式双向兼容

### Modified Capabilities

无。`openspec/specs/` 当前为空，桌面能力尚未建立规格；本变更引入的三项能力为 Android 侧首批规格。

## Impact

- **新增目录**：`app/android/`（Gradle 工程）、`app/engine-ffi/`（FFI crate）
- **受影响代码**：`app/engine/Cargo.toml`（feature 划分）、`app/engine/src/*`（`#[cfg(feature = ...)]` 门控与 Android 分支）、`.github/workflows/`
- **依赖**：新增 Kotlin/Compose/AGP 依赖、UniFFI、`cargo-ndk`；桌面构建必须保持可编译（feature 默认值不变）
- **不在本变更范围**：`src/tingo/`（旧 Python 实现）的删除由独立变更处理，避免地基变更与删除变更互相掩盖
- **风险**：sherpa-onnx 无法在 Android 复用（桌面 `static` feature 链接的是 macOS 预编译库），故 ASR 将从 Rust 侧移出到 Kotlin——本变更只负责让 Rust 侧在没有 ASR 的情况下依然干净可编译
