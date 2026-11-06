# Tasks

## 1. 引擎 feature 划分

- [x] 1.1 在 `app/engine/Cargo.toml` 增加 `http-server` / `sherpa-asr` / `desktop-shell` 三个 feature，`default` 保持三者全开，模块声明按 feature 门控；验证：`cargo build` 与 `cargo build --no-default-features` 均成功，`cargo test` 全绿
- [x] 1.2 为 feature 组合加 CI 守护：`.github/workflows/ci.yml` 增加桌面（默认 feature）与 Android（`--no-default-features --target aarch64-linux-android`）两条编译路径；验证：本地以相同命令复现通过
- [x] 1.3 引入 `EnginePaths { meetings_root, config_dir, cache_dir }` 与 `Platform`，替换 `config.rs` / `meetings.rs` / `storage.rs` 中的 `HOME` 与 `~/.config` / `~/Documents` 推导；验证：新增单元测试断言注入路径被使用，且桌面默认值仍解析到原路径
- [x] 1.4 把磁盘余量探测抽象为 trait（桌面与 Android 都实现为 `statvfs`，Android 侧不依赖 `desktop-shell` feature），并把 `meetings.rs:379` 的 `libc` 调用迁到桌面实现内；验证：`cargo test` 通过且 `--no-default-features` 下不再引用 `libc`
- [x] 1.5 以桌面 v0.5.2 真实产出构造会议夹具（`app/engine/tests/fixtures/`），供引擎侧解析测试使用；验证：夹具被测试引用且测试通过

## 2. engine-ffi 桥接边界

- [x] 2.1 新建 `app/engine-ffi` crate（cdylib + rlib，依赖 `tingo-engine` 无默认 feature），暴露 `engine_version()` 与 `capabilities()`；验证：Rust 单测断言版本非空、能力集合含六项键
- [x] 2.2 定义跨边界领域类型（`MeetingSummary`、`MeetingDetail`、`Transcript`、`Sentence`、`MinutesDoc`、`Fishbone`）与 `EngineError` 五类错误；验证：Rust 单测覆盖中文文本、空集合、缺失别名、毫秒时间戳的往返相等
- [x] 2.3 为每个导出函数加 `catch_unwind` 崩溃隔离，panic 映射为 `EngineError::Internal`；验证：注入 panic 的测试用例返回内部错误且测试进程存活
- [x] 2.4 实现 `EngineEnv` 初始化与 `scan_library()` / `read_meeting()` / `parse_minutes()` / `parse_fishbone()`；验证：以夹具目录为输入的单测覆盖正常、缺文件、损坏 `transcript.json`、非法 fishbone JSON 四种情形
- [x] 2.5 配置 UniFFI 绑定生成（uniffi-bindgen bin 目标 + `uniffi.toml` 指定 Kotlin 包名），验证：`cargo run --bin uniffi-bindgen generate ...` 能产出 Kotlin 文件且无警告

## 3. Android 工程骨架

- [x] 3.1 建立 `app/android/` Gradle 工程（`settings.gradle.kts`、`libs.versions.toml`、`:app` 模块、`MainActivity` 显示一句文案），配置国内 Maven 镜像；验证：`./gradlew :app:assembleDebug` 产出 APK
- [x] 3.2 注册 `cargoNdkBuild` 任务（`cargo ndk -t arm64-v8a -t x86_64 -o app/src/main/jniLibs build --release --no-default-features`）并挂到 `preBuild`；验证：APK 内含 `lib/*/libtingo_ffi.so`，且 ABI 仅 `arm64-v8a` + `x86_64`
- [x] 3.3 注册 `generateUniffiBindings` 任务，把生成的 Kotlin 绑定纳入 Kotlin 编译源集；验证：`./gradlew :app:compileDebugKotlin` 通过，绑定类可被 `:app` 引用
- [x] 3.4 建立 `AppContainer` 手写 DI 与 `EngineFacade`（Kotlin 侧封装桥接、把 `EngineError` 映射为本地化提示键）；验证：`androidTest` 在模拟器上调用 `engineVersion()` 与一次 `scan_library()` 成功
- [x] 3.5 增加 ABI 前置校验：不支持的 ABI 在加载原生库前提示并终止初始化；验证：`androidTest` 覆盖"受支持则加载成功"分支，构建期检查覆盖 `arm64-v8a` 打包

## 4. 会议库只读访问

- [x] 4.1 实现 `MeetingRepository`：根目录发现、按时间倒序、不可读目录隔离、扫描不修改文件；验证：JVM 单测用临时目录断言排序、损坏目录隔离与扫描前后文件哈希不变
- [x] 4.2 实现 `meta.json` 容错解析与标题回退（经桥接调用引擎）；验证：JVM 单测覆盖缺文件与缺字段两情形
- [x] 4.3 实现 `LibraryViewModel` 状态机（加载中/成功/空/错误/不可读项），事件为 sealed class；验证：JVM 单测覆盖五种状态与"扫描失败但保留旧数据"
- [x] 4.4 实现会议列表界面（标题、时间、标签、不可读标记）与空态；验证：`androidTest` 断言列表条目数与排序
- [x] 4.5 实现会议详情界面（转写分段、说话人别名回退、纪要章节化、fishbone 区块识别与非法 JSON 降级）；验证：`androidTest` 断言四类展示与"时间戳点击定位"回调参数
- [x] 4.6 用桌面 v0.5.2 产出夹具做兼容验收（含缺 `录音.m4a` 的目录）；验证：`androidTest` 断言该会议可打开且播放控件被隐藏

## 5. 应用外壳

- [x] 5.1 实现底部导航（录音/会议/设置）与会议详情全屏打开、返回保持列表滚动位置与筛选；验证：`androidTest` 覆盖进入详情再返回后筛选条件不变
- [x] 5.2 实现基于 WindowSizeClass 的单/双栏自适应，窗口尺寸变化后保持选中项；验证：`androidTest` 在两类尺寸下断言布局形态与选中项
- [x] 5.3 建立 `values/strings.xml` 与 `values-en/strings.xml`，实现应用内语言切换并持久化；验证：单测断言 persistence 读写；`androidTest` 断言切换后文案变化且重建 Activity 后仍为英文
- [x] 5.4 实现私有数据目录初始化（首次启动创建根目录并写探针文件）；验证：`AppContainer.ensureDataRoot()` 返回 true，端到端测试能读到该目录下的会议
- [ ] 5.4b （拆分出来，另开变更 `add-storage-root-selection`）用系统目录选择器改选会议库根目录：SAF 返回 `content://` URI，而引擎按绝对路径存取，需要先给引擎加一层存储抽象；本变更不提供假入口，README 已记录该限制
- [x] 5.5 收敛权限清单为最小集（仅录音与通知按需申请），拒绝录音权限时仍可浏览；验证：构建期读取合并后的 `AndroidManifest.xml` 断言无全盘存储/后台定位/通讯录权限；`androidTest` 覆盖拒绝后仍可列出会议

## 6. 构建与验证管线

- [x] 6.1 在 `.github/workflows/ci.yml` 增加 Android job（安装 JDK 21、NDK、Rust target、cargo-ndk，运行 `./gradlew :app:assembleDebug` 与 JVM 单测）；验证：本地以同一命令序列零错误完成
- [x] 6.2 编写 `app/android/README.md`：环境前提、镜像配置、构建命令、模拟器与真机验证步骤、已知限制（无音频链路）；验证：按文档从干净状态执行一遍命令全部成功
- [x] 6.3 模拟器端到端冒烟：创建 AVD、安装 APK、放入夹具会议、打开列表与详情并截图；验证：截图与界面元素可见，日志无 panic

## Workflow follow-up

- 全部任务完成后运行 code-review skill 做双轴（Standards / Spec）核查，直到两轴无未决问题
- 核查通过后 archive 本变更，把三项能力同步进 `openspec/specs/`
