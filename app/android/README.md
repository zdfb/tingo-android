# Tingo for Android

听过（Tingo）的 Android 客户端。**当前进度：录音（含尽力而为的系统内录）、离线转写（含说话人区分）、端侧纪要生成、
回听播放、搜索/归档/删除/导出、设置页模型管理都已可用**；会议库位置切换仍待补。

## 结构

```
app/
├── engine/       # Rust 引擎（桌面与 Android 共用；Android 用 --no-default-features 只取核心）
├── engine-ffi/   # UniFFI 边界层：把引擎以领域类型暴露给 Kotlin
└── android/      # 本 Gradle 工程（Kotlin + Jetpack Compose）
    └── app/      # :app 模块
```

原生库由 `cargo-ndk` 交叉编译后打进 APK；Kotlin 绑定由 `uniffi-bindgen` 生成，两者都接在 Gradle 任务里（`cargoNdkBuild` / `generateUniffiBindings`），不需要手工步骤。

## 环境前提

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | `openjdk@21` 或 Android Studio 自带 JBR |
| Android SDK | platform 37、build-tools 36 | `compileSdk = 37` |
| NDK | 30.x | 路径由 `ANDROID_NDK_HOME` 或 `local.properties` 的 `sdk.dir` 推导 |
| Rust | 1.85+（本机 1.99） | 需要 `aarch64-linux-android`（真机）与 `x86_64-linux-android`（模拟器）两个 target |
| cargo-ndk | 4.x | `cargo install cargo-ndk --locked` |
| Gradle | 9.6.0 | 由 wrapper 指向腾讯镜像；AGP 9.5.1 要求 Gradle ≥ 9.6 |

首次准备：

```bash
brew install openjdk@21
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --locked
cd app/android && echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

### 网络受限时的镜像

本项目在到 `dl.google.com` 直连会被中断 TLS 握手、`github.com` 不可达的网络下开发过，因此：

- Gradle 依赖：`settings.gradle.kts` 里按「阿里云 google 镜像 → 腾讯 Maven Central 镜像 → 官方源兜底」排序；HTTP 超时与重试在 `gradle.properties`
- crates.io：`~/.cargo/config.toml` 指向 `rsproxy` 稀疏镜像
- Rust 工具链：`export RUSTUP_DIST_SERVER=https://mirrors.ustc.edu.cn/rust-static`
- Gradle 发行版：`gradle/wrapper/gradle-wrapper.properties` 指向腾讯镜像
- 桌面 `tingo-engine` 的 `sherpa-onnx-sys` 会从 GitHub 下载静态库：受限网络下需预先下载并 `export SHERPA_ONNX_LIB_DIR=<解压目录>/lib`

## 构建与测试

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/30.0.16248370

# 首次构建先交叉编译 llama.cpp（libllama/libggml*，约 6 MB，产物不进仓库）
./scripts/build-llama-android.sh arm64-v8a x86_64

./gradlew :app:assembleDebug            # 产出 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest        # JVM 单测（仓库、ViewModel、工具函数、权限清单）
./gradlew :app:connectedDebugAndroidTest # 设备级测试（需已连接设备或模拟器）
```

当前状态（2026-11-09 实机验收）：**JVM 单测 213 个全绿**；REDMI Turbo 4（HyperOS / Android 16 /
arm64）在**飞行模式下**跑通 17 个设备级用例（引擎桥接 10、备份恢复与 sherpa 运行时 4、
中文识别 / 双人流水线 / llama 生成 3），证明内置模型确实离线可用。
Compose UI 测试（16 个）在 HyperOS 上被系统拦截——instrumentation 发起的 Activity 启动被
系统取消，需在开发者选项里放开限制，属环境限制而非代码问题；这部分在 API 36 模拟器上全绿。
完整设备级套件要下载约 750 MB 模型，本机网络下约 1.5 小时；只想跑非网络用例可以排除四个
依赖下载的测试类：

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=\
io.github.tancheng33.tingo.PipelineInstrumentedTest,\
io.github.tancheng33.tingo.RecognitionInstrumentedTest,\
io.github.tancheng33.tingo.LlamaInstrumentedTest,\
io.github.tancheng33.tingo.ModelDownloadInstrumentedTest
```

只想构一个 ABI 加快迭代：`./gradlew :app:assembleDebug -PtingoRustAbis=arm64-v8a`（此时
llama 只需编对应 ABI：`./scripts/build-llama-android.sh arm64-v8a`）。

**模型随 APK 内置**：语音模型（Paraformer int8 227 MB + 标点 76 MB + CAM++ 28 MB + VAD 0.6 MB）
与语言模型（Qwen3-0.6B Q4_K_M 397 MB）都打进 `assets/models/`，APK 约 **746 MB**。
安装后首次启动由 `ModelInstaller` 解包到应用私有目录（幂等、带进度），**端侧推理全程不需要网络**。

模型不进仓库（700+ MB 二进制不适合进 git）。构建前先把它们取到 `app/android/models/`：

```bash
./scripts/fetch-models-offline.sh              # 优先从已连接设备取，其次走镜像
./scripts/fetch-models-offline.sh --from-mirror # 只走镜像
```

网络下载路径仍然保留作兜底（开发构建没内置模型时可用），但正常安装包里不需要它。
验证"真的离线可用"：把手机调成飞行模式，跑 `RecognitionInstrumentedTest` /
`PipelineInstrumentedTest` / `LlamaInstrumentedTest` —— 本轮实机（REDMI Turbo 4）在断网下
这三个用例全绿。

**校验强度**：四个语音模型都带 sha256，下载后校验摘要；语言模型这一版 `sha256` 留空
（镜像不稳定，多次下载中断，没能稳定取到摘要），此时只校验体积与校验指纹——这一点在
`assets/llm.json` 的 note 里也写了，换到稳定源后应补上真实摘要。

**关于"再量化"这件事的诚实说明**：`spike/on-device-models/RESULTS.md` 里的 Q4_K_M / Q4_0 是
我们用 `llama-quantize --allow-requantize` 从官方 Q8_0 再量化出来的，用来回答"能不能再压一档、
速度与格式可靠性如何"。App 实际下载的是 unsloth 官方发布的 Q4_K_M：同样的量化档位、体积略大
（397 MB vs 378 MB），但它是别人校验过、有稳定下载地址的产物——把这一个 397 MB 的文件挂在自己
仓库里分发并不合适。也就是说：**实验结论指导了选型，但没有把实验产物当产品分发**。

**内存门槛（估算，未在真机上实测）**：转写时 sherpa 与四个模型常驻，按权重体积估算峰值
约 1 GB；纪要生成时加载 Q4_K_M 权重 397 MB + KV cache，同样是数百兆量级。两条链路不同时驻留，
所以 6 GB 机型理论上够用——但这是估算，`spike/on-device-models/RESULTS.md` 的"端侧运行的剩余工作"
里列着"真机实测速度与内存"，尚未做。加载失败（内存不足）时按设计降级为
"本机暂不支持本地纪要生成"，录音与转写不受影响。

**模型来源与许可**（都在各自上游仓库的许可下使用，App 不打包、不转售）：

| 模型 | 用途 | 来源 |
|---|---|---|
| Paraformer-large int8 + tokens + am.mvn | 中文识别 | `csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09`（FunASR，MIT） |
| `punct.int8.onnx`（CT-Transformer） | 断句标点 | `sherpa-onnx` 官方标点模型（Apache-2.0） |
| `campplus.onnx` | 说话人声纹 | 3D-Speaker CAM++（Apache-2.0） |
| `silero_vad.onnx` | 语音活动检测 | Silero VAD（MIT） |
| `Qwen3-0.6B-Q4_K_M.gguf`（397 MB） | 纪要生成（实验） | `unsloth/Qwen3-0.6B-GGUF` 官方 Q4_K_M（Apache-2.0）。我们自 Q8_0 再量化的那一份只用于可行性验证（`spike/on-device-models/RESULTS.md`），没有分发——见下文说明 |

### 在模拟器上验证

```bash
avdmanager create avd -n tingo_api36 -k "system-images;android-36;google_apis;arm64-v8a"
emulator -avd tingo_api36 -no-window -no-audio -no-boot-anim -no-snapshot
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**注意**：`adb push` 到 `/sdcard/Android/data/<pkg>/files/...` 的文件属主是 shell，App 进程读不到（`Permission denied`）。要验证"能读电脑录的会议"，请用 `connectedDebugAndroidTest`——测试以 App 自己的 uid 把夹具写进会议库，这也正是校验桌面 v0.5.2 目录兼容性的方式。

模拟器 API 37 上 Compose UI 测试会报 `NoSuchMethodException: android.hardware.input.InputManager.getInstance`（Compose 测试库与 API 37 的兼容问题），因此设备级测试请用 API 36 镜像。

## 已知限制

- **录音前台服务不在自动化测试里**：Android 14+ 禁止从后台启动 `microphone` 类型的前台服务，
  无头模拟器里的 instrumentation 进程拿不到这个前提（`ForegroundServiceDidNotStartInTimeException`）。
  服务的判断逻辑（何时该挂前台、通知文案）由 JVM 单测覆盖，真机上需要手动确认一次锁屏录音不中断
- **系统内录（录会议软件的声音）只是尽力而为**：走 `MediaProjection` + `AudioPlaybackCapture`，
  会议软件多用 `USAGE_VOICE_COMMUNICATION` 或直接禁止被抓，这条通路常常抓不到；界面会说明并给出替代做法。
  两路音轨写 `mic.wav` / `system.wav`，结束时合成 `录音.wav`（与桌面版布局一致）。
  降声道、重采样、混音这些数字部分是单测覆盖的，**真机完整链路需要人工验收一次**
- **不能改会议库位置，改由"备份/恢复"满足迁移需求**：数据落在应用专属外部目录
  （`Android/data/io.github.tancheng33.tingo/files/tingo`）。SAF 选出的目录只有 `content://` URI，
  而引擎是原生代码、按绝对路径读写；给引擎整套 IO 加存储抽象代价远超收益。
  设置页提供"备份到文件 / 从备份恢复"：整库打成一个 zip，恢复默认跳过同名会议
- **播放器只做"回听某一句"**：详情页有播放/暂停与进度条，点某句跳到该句开头；
  没有快进、倍速、声音波形。没有 `录音.wav` 的会议（例如只导入了 `transcript.json`）不显示播放条
- 仅支持 `arm64-v8a`（真机）与 `x86_64`（模拟器）；其它 ABI 启动时直接给出不支持提示
