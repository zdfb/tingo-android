# Android 开发指南

Android 客户端由 Kotlin、Jetpack Compose 和 Rust 引擎组成。项目入口及当前版本状态见[仓库首页](../../README.md)。

## 目录

- `app/src/main`：应用、资源及示例会议。
- `app/src/test`、`app/src/androidTest`：JVM 和设备测试。
- `../engine`：Rust 核心引擎及测试夹具。
- `../engine-ffi`：Rust 与 Android 的 UniFFI 桥接层。

## 构建环境

需要 JDK 21、Android SDK platform 37、NDK 30.0.16248370、Rust、`cargo-ndk` 和 Android Rust target（`aarch64-linux-android`、`x86_64-linux-android`）。在本目录的 `local.properties` 中设置 `sdk.dir`，或根据本地环境设置 `ANDROID_NDK_HOME`。

```bash
./gradlew :app:assembleDebug
```

构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。Gradle 会构建 Rust 原生库并生成 Kotlin 绑定。语音模型不内置于 APK，首次使用时由应用下载并校验。

## 验证

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

设备测试需要连接真机或启动模拟器；部分识别测试需要下载模型。v0.5.4 的 JVM 单元测试和 Debug 构建已通过，完整真机自动化测试因设备系统拦截尚未完成。系统音频捕获受设备和其他应用的限制，双路录音同步仍需进一步真机验收。

## 数据与模型服务

会议和录音保存在应用专属目录，卸载应用会删除这些数据；需要长期保留的内容应先备份。语音识别在本机运行；生成纪要时，转写文本会发送到用户配置的模型服务。
