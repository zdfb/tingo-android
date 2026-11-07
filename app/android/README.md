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

只想构一个 ABI 加快迭代：`./gradlew :app:assembleDebug -PtingoRustAbis=arm64-v8a`（此时
llama 只需编对应 ABI：`./scripts/build-llama-android.sh arm64-v8a`）。

模型不进 APK：语音模型（Paraformer 227 MB + 标点 76 MB + CAM++ 28 MB + VAD 0.6 MB）与
语言模型（Qwen3-0.6B Q4_K_M 397 MB）由 App 首次使用时按 `assets/models.json`、`assets/llm.json`
下载到 `files/tingo/models/`，带续传与 sha256 校验。

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
- **不能改会议库位置**：数据落在应用专属外部目录（`Android/data/io.github.tancheng33.tingo/files/tingo`）。用 SAF 选目录返回的是 `content://` URI，而引擎按绝对路径存取，需要先给引擎加一层存储抽象（后续变更），当前不提供假入口
- **播放器只做"回听某一句"**：详情页有播放/暂停与进度条，点某句跳到该句开头；
  没有快进、倍速、声音波形。没有 `录音.wav` 的会议（例如只导入了 `transcript.json`）不显示播放条
- 仅支持 `arm64-v8a`（真机）与 `x86_64`（模拟器）；其它 ABI 启动时直接给出不支持提示
