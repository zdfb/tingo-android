# Design

## Context

见 `proposal.md — Why`。当前状态与约束：

- `app/engine`（Rust）是一个单 crate，`Cargo.toml` 里 17 个模块全部无条件编译，其中 `axum`（HTTP 服务）、`sherpa-onnx`（`static` feature，链接 macOS 预编译静态库）、`libc`（statvfs）、`std::process`（Swift 录音器、`/usr/bin/open`）在 Android 上要么不可用，要么根本不是我们想要的形态。
- 引擎的路径解析写死 `~/.config/tingo`、`~/Documents/Tingo`、`HOME`，Android 上没有这些路径。
- 全部状态是「文件 + TOML + JSON」，无数据库；会议目录格式同时被桌面 HTML 前端与 Rust 侧使用。
- Kotlin 侧没有任何既有代码，Compose/AGP/Gradle 版本矩阵需要一次成型。
- 本机网络对国外源不稳定（`static.rust-lang.org` 首字节 10s+、`crates.io` 403、`github.com` 000），已改用 USTC/rsproxy/腾讯镜像。

## Goals / Non-Goals

**Goals:**

- 让 `tingo-engine` 在「无 axum、无 sherpa-onnx、无 libc 桌面调用」的 feature 集合下干净编译，并在 Android 交叉编译通过
- 建立 UniFFI 边界，使 Kotlin 能以结构化类型与错误调用引擎，而不是解析 JSON 字符串
- 建立一个端到端可运行的纵切：开会库扫描 → 解析 → Compose 展示
- 建立可重复的构建与验证管线（Gradle + cargo-ndk + 模拟器）

**Non-Goals:**

- 不实现录音、ASR、纪要生成、导出（后续变更）
- 不实现 Kotlin 侧 ASR，也不在本变更中接入 sherpa-onnx Android AAR
- 不改动桌面端可观察行为；不删除旧 Python 实现
- 不追求性能优化（导入 1 小时会议的性能基线留给后续变更）

## Decisions

### D1. 模块布局：`app/android/` + `app/engine-ffi/`，共享 `app/engine`

```
app/
├── engine/           # 既有 Rust crate，新增 feature 划分与路径注入
├── engine-ffi/       # 新 crate：UniFFI 接口定义 + cdylib
└── android/          # 新 Gradle 工程（Kotlin + Compose）
    ├── app/          # :app 模块，jniLibs 由 cargo-ndk 产物填充
    └── bridge/       # UniFFI 生成的 Kotlin 绑定（生成目录，不入库）
```

替代方案：复制一份 `engine-android`（拒绝——两套逻辑必然漂移，与"开源干净"直接冲突）；WebView + 本地 HTTP（拒绝——与 Q5 决策相反，移动端生命周期管理代价高）。

### D2. `tingo-engine` 的 feature 划分

| feature | 内容 | 桌面默认 | Android |
|---|---|---|---|
| `http-server` | `server.rs`（axum 48 路由）、CORS | ✅ | ❌ |
| `sherpa-asr` | `asr.rs`、`audio.rs`、`chunked.rs` | ✅ | ❌ |
| `desktop-shell` | `record.rs`、`obsidian.rs`、`autostop.rs`、`apple_speech.rs`、`update.rs` | ✅ | ❌ |
| （无 feature） | `transcript`、`terms`、`fishbone`、`summarize`、`speakers`、`push`、`i18n`、`minutes_history`、`cluster`、`meetings`、`config`、`storage`、`models` | ✅ | ✅ |

`default = ["http-server", "sherpa-asr", "desktop-shell"]`，桌面构建命令不变；Android 用 `--no-default-features`。CI 必须同时验证两种 feature 组合。

### D3. 路径注入取代 `HOME` 推导

引入 `EnginePaths { meetings_root, config_dir, cache_dir }` 与 `Platform` 枚举（`Desktop` / `Android`）。凡当前依赖 `HOME`/`config.rs` 默认值/`statvfs` 的代码改为接受注入值；桌面侧在 `main` 处按旧逻辑填充，行为不变。磁盘余量探测抽象为 trait，桌面用 `statvfs`，Android 用 `StatFs`（Kotlin 侧传入或 Rust 侧 `cfg` 实现）。

替代方案：保留 `HOME` 且在 Android 上伪造 `HOME` 环境变量（拒绝——`std::env::set_var` 是进程级全局副作用，且与 Android 沙箱语义不符）。

### D4. UniFFI 用 proc-macro 模式，接口只暴露"领域类型 + 一组用例函数"

- 接口面：`engine_version()`、`capabilities()`、`EngineEnv(paths)`、`scan_library()`、`read_meeting(id)`、`parse_minutes(text)`、`parse_fishbone(text)`。
- 数据类型：`MeetingSummary`、`MeetingDetail`、`Transcript`、`Sentence`、`MinutesDoc`、`Fishbone` 直接镜像 Rust 结构，不经过 JSON 字符串。
- 错误：`#[derive(thiserror::Error)] enum EngineError` 映射到 Kotlin sealed class，类别为输入非法/资源缺失/格式损坏/权限不足/内部错误。
- 异步：这次只暴露同步函数；长任务与取消在引入 ASR 的变更里再用 UniFFI `async` 补。

替代方案：手写 JNI（拒绝——样板与崩溃隔离都要自己维护）；暴露 JSON 字符串（拒绝——正是要消除的东西）。

### D5. 崩溃隔离与并发

`engine-ffi` 每个导出函数包一层 `std::panic::catch_unwind`，panic 转为 `EngineError::Internal`；UniFFI 默认线程模型下 Kotlin 调用在调用方线程执行，长调用由 Kotlin 侧派发到 `Dispatchers.IO`。引擎内部保持 `Send + Sync`，状态用 `Arc<Mutex<…>>`（仅配置与缓存）。

### D6. Compose 架构：单 Activity + Navigation Compose + WindowSizeClass

- 单 `MainActivity`，`NavigationSuiteScaffold`（Material 3 adaptive）实现底部导航与双栏
- 每个页面 `ViewModel` 暴露不可变 `UiState`（单一 state class + `StateFlow`），事件以 sealed class 输入 → 这正是 TDD 的 seam 2
- 依赖注入用手写 `AppContainer`（不引 Hilt，减少注解处理与构建复杂度）
- 文案用 `res/values/strings.xml` 与 `values-en/strings.xml`，不用 TS 的 `t(zh, en)` 形态

### D7. 存储与解析：Kotlin 不重复实现业务解析

会议目录扫描由 Kotlin 完成（`DocumentFile`/`File` 遍历，Android 侧天然是 IO），**单个会议的解析（`transcript.json`、`纪要.md`、fishbone、terms）全部走引擎**，保证与桌面同一份实现。Kotlin 只做展示模型转换。

替代方案：Kotlin 重写解析（拒绝——两套解析必然漂移，且无法复用桌面测试）。

### D8. 构建管线：Gradle 任务调用 cargo-ndk，产物落 `jniLibs`

- `:app/build.gradle.kts` 注册 `cargoNdkBuild` 任务，`preBuild` 依赖它；`cargo ndk -t arm64-v8a -t x86_64 -o app/src/main/jniLibs build --release --no-default-features`
- 模拟器用 `x86_64`（Apple Silicon 上 API 36 的 x86_64 镜像可用性待验证，备选 `arm64-v8a` 镜像）
- UniFFI 绑定生成任务 `generateUniffiBindings` 依赖 `cargo build`（host 构建 `engine-ffi` 的 uniffi-bindgen）
- 版本矩阵（待构建时锁定并写入 `libs.versions.toml`）：AGP 8.x 稳定版、Gradle 8.1x、Kotlin 2.x、Compose BOM 对应版本、JDK 21（`openjdk@21`）
- 镜像：`~/.cargo/config.toml` 用 rsproxy；Gradle 用腾讯/阿里 Maven 镜像（`settings.gradle.kts` 的 `repositories`）

### D9. 测试策略（对应 TDD 的 seam）

| seam | 测试形态 | 位置 |
|---|---|---|
| `engine-ffi` 公开 API | Rust 单测 + `androidTest` 往返测试（真实 .so） | `app/engine-ffi/src/`, `app/android/app/src/androidTest/` |
| ViewModel 状态机 | JVM 单测（纯 Kotlin，无 Android 依赖） | `app/android/app/src/test/` |
| 存储 Repository | Robolectric（临时目录） | 同上 |
| 纪要/鱼骨图解析器 | 引擎侧 Rust 单测；Kotlin 只测展示模型转换 | `app/engine/src/fishbone.rs`, `app/android/.../test/` |
| 录音控制器状态机 | 不在本变更范围（后续变更引入） | — |

`androidTest` 需模拟器；`test` 在 JVM 上跑，作为日常红绿循环的主战场。

## Risks / Trade-offs

- **[feature 划分破坏桌面构建]** → CI 同时跑 `cargo test`（默认 feature）与 `cargo build --no-default-features`；桌面行为不变作为验收项。
- **[UniFFI 与 Gradle 集成踩坑]** → 先做最小往返（`engine_version`）打通再扩展；绑定生成固定在 Gradle 任务里，避免手工步骤。
- **[AGP/Kotlin/Compose 版本矩阵]** → 首次构建用 `libs.versions.toml` 集中管理，版本以"能构建通过"为准并写入文档。
- **[网络不稳导致依赖拉取失败]** → 全部走国内镜像；NDK/AGP 首次下载记录到 README，避免重复踩坑。
- **[模拟器无法验证音频链路]** → 本变更不涉及音频；录音与内录的验证留到对应变更，并明确要求真机冒烟。
- **[双 ABI 增加构建时间]** → 日常只构建 `x86_64`（模拟器），打包用 `arm64-v8a`；由 Gradle 属性切换。
- **[会议目录跨设备兼容性]** → 以桌面 v0.5.2 的真实产出作为夹具（fixtures）纳入测试，而非人工构造。

## Migration Plan

纯新增 + 引擎内部重构，无数据迁移。回滚策略：删除 `app/android/`、`app/engine-ffi/`，并回退 `app/engine` 的 feature 改动即可恢复当前状态。桌面构建在每一步都必须保持可编译可测试。

## Open Questions

- 模拟器镜像选择：API 36 的 `x86_64` 镜像在 Apple Silicon 上是否可用；不可用则改用 `arm64-v8a` 镜像（不影响规格与任务拆解，构建时验证）
- UniFFI 版本与 Kotlin 协程支持程度：本变更只用同步接口，版本以能生成为准
