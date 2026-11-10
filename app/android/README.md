# Tingo for Android

听过（Tingo）的 Android 客户端。**当前进度：录音（含尽力而为的系统内录）、离线转写（含说话人区分）、
远端 API 纪要生成、回听播放、搜索/归档/删除/导出、设置页、新手引导与示例会议都已可用**。

**分工**：录音与转写全部在本机完成（音频不出设备）；只有"把转写稿写成纪要"这一步
走用户自己配置的模型服务。**应用不预置任何服务商配置**：默认四项全空，
设置页只是给出五家常服务的"填空按钮"（点一下填好地址、协议、模型名），
密钥永远由用户自己粘，换服务商时会清空——上一家的密钥不该被送到下一家。

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

./gradlew :app:assembleDebug            # 产出 app/build/outputs/apk/debug/app-debug.apk
# 没有内置模型（300+ MB，不进仓库）时加 -PtingoSkipBundledModels=true 才能出包
./gradlew :app:assembleDebug -PtingoSkipBundledModels=true
./gradlew :app:testDebugUnitTest        # JVM 单测（仓库、ViewModel、工具函数、权限清单）
./gradlew :app:connectedDebugAndroidTest # 设备级测试（需已连接设备或模拟器）
```

**为什么纪要不再跑在手机上**：端侧 0.6B（Qwen3-0.6B Q4_K_M）在本机实测，一小时会议的提示词
裁剪到 2k token 时首字要等 41 秒；不裁剪（6.3k token）首字 149 秒、解码掉到 4.9 tok/s，
输出还常缺章节。调参（flash-attn、KV q8_0、8/8 线程、更低比特量化）能把解码从 23 tok/s
提到 33 tok/s，但改不了"长上下文预填充动辄几十秒"这个量级。既然用户要的是点了就有结果，
这一步改走远端 API；语音模型仍在本机，转写依旧离线可用。

当前状态（2026-11-10 实机验收）：**JVM 单测 326 个全绿**；REDMI Turbo 4（HyperOS / Android 16 /
arm64）跑通 **37 个设备级用例**（引擎桥接、备份恢复、sherpa 运行时、中文识别、双人流水线、
远端纪要端到端、设置页、会议库管理、音频导入、示例会议、长混音），其中转写相关用例在
**飞行模式下**同样通过，证明语音链路离线可用。

设备侧一共 52 个 `@Test`：**Compose UI 测试 11 个**只在本机模拟器跑（HyperOS 会拦掉
instrumentation 发起的 Activity 启动）；非 UI 41 个里有 3 个在本机跑不了——
`LanguageSwitchTest`、`MainActivityEndToEndTest`（同一个 Activity 启动限制，会挂住）
与 `ModelDownloadInstrumentedTest`（要联网下 700 MB 模型）。实跑的 37 个就是剩下的那批。
Compose UI 测试（11 个）在 HyperOS 上被系统拦截——instrumentation 发起的 Activity 启动被
系统取消，需在开发者选项里放开限制，属环境限制而非代码问题；这部分在 API 36 模拟器上全绿。
完整设备级套件要下载约 750 MB 模型，本机网络下约 1.5 小时；只想跑非网络用例可以排除三个
依赖下载的测试类：

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=\
io.github.tancheng33.tingo.PipelineInstrumentedTest,\
io.github.tancheng33.tingo.RecognitionInstrumentedTest,\
io.github.tancheng33.tingo.ModelDownloadInstrumentedTest
```

只想构一个 ABI 加快迭代：`./gradlew :app:assembleDebug -PtingoRustAbis=arm64-v8a`。

**语音模型随 APK 内置，且与桌面端同一份**：ModelScope 的 Paraformer-large **量化（int8）**
模型（238 MB）+ CT-Transformer 标点 **int8**（76 MB）+ CAM++ 声纹（28 MB，fp32，桌面端同样是这一份）
+ Silero VAD（0.6 MB，fp32）。识别与标点都是 int8 量化版，声纹与 VAD 上游只提供 fp32，
桌面端也用这两个，所以两边一致。
打进 `assets/models/`，**debug 包实测 620 MB**（模型按不压缩方式存放；release 用签名压缩会更小）。安装后首次启动由 `ModelInstaller` 解包到应用私有目录
（幂等、带进度），**转写全程不需要网络**。（早期版本的端侧纪要 GGUF 与 llama.cpp 已整体移除。）

**图标与品牌图是生成的，不要手改**：`res/mipmap-*/ic_launcher*.png` 与
`res/drawable-nodpi/tingo_mark.png` 都由 `scripts/gen-brand-assets.py` 从
`docs/assets/tingo-icon.png` 生成（需要 Pillow）。改了品牌图就重跑一遍脚本，
否则下次有人重新生成时会出现"图库里两个版本"。

模型不进仓库（300+ MB 二进制不适合进 git）。构建前先把它们取到 `app/android/models/`：

```bash
./scripts/fetch-models-offline.sh              # 优先从已连接设备取，其次走镜像
./scripts/fetch-models-offline.sh --from-mirror # 只走镜像
```

ModelScope 的原始 `model_quant.onnx` 不能直接被 sherpa-onnx 加载（缺 LFR 窗长与归一化元数据），
取模型时要补元数据、把随包的 `tokens.json` 展开成 `符号 编号` 的 `tokens.txt`——桌面端在首次启动时
由 `engine::models::prepare_paraformer` 做，脚本里用等价的 python 实现（不必为了取模型编一遍 Rust）。

网络下载路径仍然保留作兜底（开发构建没内置模型时可用），但正常安装包里不需要它。
验证"转写真的离线可用"：把手机调成飞行模式，跑 `RecognitionInstrumentedTest` /
`PipelineInstrumentedTest` —— 本轮实机（REDMI Turbo 4）在断网下这两个用例全绿。
纪要需要网络，这是有意的取舍：`RemoteMinutesInstrumentedTest` 用本机回环的假服务端验证
请求报文、鉴权头与响应解析，不依赖任何外部服务。

**校验强度**：六个模型文件都带 sha256，下载后校验摘要；`models.json` 里记的就是内置文件的指纹，
换模型时体积或摘要对不上会直接拒绝解包，而不是把坏模型喂给推理引擎（`BundledModelInstrumentedTest`
盯的就是"内置的这份能不能认出内容"）。

**内存**：转写时 sherpa 与四个模型常驻，按权重体积估算峰值约 1 GB；纪要不再占用本机内存。
十万级会议的转写已在 10 分钟双轨混音用例里验过堆增长 < 80 MB。

## 配置纪要服务

设置页 → **模型服务**，可以先点一家预设把地址/协议/模型填好，再粘自己的 API Key：

| 字段 | 例子 | 说明 |
|---|---|---|
| 接口地址 | `https://api.deepseek.com` | 也可以填到 `/v1`，两种写法等价 |
| API Key | `sk-...` | 存在应用私有 SharedPreferences，不进日志、不进导出包 |
| 模型名 | `deepseek-chat` | 由服务商决定 |
| 接口协议 | `openai` / `anthropic` | `openai` 走 `{地址}/v1/chat/completions`，`anthropic` 走 `{地址}/v1/messages` |

三项（地址、密钥、模型名）没填全时，详情页的"生成纪要"会置灰并给"去设置"的入口——
不让人点一个只会失败的按钮。明文 HTTP 地址是允许的，方便指向局域网自建网关或本地 ollama。

**模型来源与许可**（都在各自上游仓库的许可下使用，App 不打包、不转售）：

| 模型 | 用途 | 来源 |
|---|---|---|
| Paraformer-large int8 + tokens + am.mvn | 中文识别 | `csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09`（FunASR，MIT） |
| `punct.int8.onnx`（CT-Transformer） | 断句标点 | `sherpa-onnx` 官方标点模型（Apache-2.0） |
| `campplus.onnx` | 说话人声纹 | 3D-Speaker CAM++（Apache-2.0） |
| `silero_vad.onnx` | 语音活动检测 | Silero VAD（MIT） |

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
