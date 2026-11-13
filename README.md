<p align="center"><img src="brand/tingo-icon.svg" width="112" alt="Tingo"></p>
<h1 align="center">听过 · Tingo for Android</h1>
<p align="center">录下会议，在手机上转写，把重要内容整理成纪要。</p>
<p align="center">
  <a href="https://github.com/zdfb/tingo-android/releases/tag/v0.5.4">v0.5.4 测试版</a> · Android 10+ · <a href="README.en.md">English</a>
</p>

## 界面

以下截图来自 v0.5.4 在 Android 真机上运行的实际界面。会议列表中的条目是测试录音。

<table><tr>
<td align="center"><b>录音</b><br><img src="docs/android/v0.5.4/record.png" width="360" alt="录音页面"></td>
<td align="center"><b>会议记录</b><br><img src="docs/android/v0.5.4/library.png" width="360" alt="会议记录页面"></td>
<td align="center"><b>设置</b><br><img src="docs/android/v0.5.4/settings.png" width="360" alt="设置页面"></td>
</tr></table>

## 可以做什么

- **录音和实时文字**：录制麦克风，可按需尝试录制其他应用的声音；录音工作台展示实时文字、临时说话人和重点标记。
- **本地转写**：首次下载 Paraformer 及配套模型后，在手机上进行语音识别和说话人区分。录音和转写保存在本机。
- **生成和回看纪要**：配置自己的模型服务，将转写文本整理成纪要；通过时间戳回听原录音。
- **管理会议**：搜索、归档、删除和恢复会议，备份与恢复数据，导出会议内容及 PDF。

系统声音录制需要 Android 的系统授权；部分应用禁止捕获声音。锁屏录音、双路音频同步等功能仍需进一步真机验收。

## 安装与使用

1. 从 [v0.5.4 发布页](https://github.com/zdfb/tingo-android/releases/tag/v0.5.4)下载 `Tingo-Android-v0.5.4-debug.apk`，安装到 Android 10 或更新版本的设备。
2. 首次启动时确认下载语音模型，并按提示授予麦克风权限。使用系统声音录制时，还需确认系统授权。
3. 如需自动生成纪要，在设置中填写模型服务地址、模型名称和 API Key。

**版本状态：**这是 debug 签名的测试包，正式签名尚未配置。JVM 单元测试和 Debug 构建已通过；完整真机自动化测试因设备系统拦截尚未完成。新版应用 ID 为 `io.github.zdfb.tingo`，不自动迁移旧版数据。

## 数据与隐私

录音和本地转写保存在设备的应用专属目录中。生成纪要时，转写文本会发送给你配置的模型服务商；录音文件不会发送给该服务。卸载应用会删除其专属目录，卸载前请备份需要保留的数据。

## 从源码构建

需要 JDK 21、Android SDK 37、NDK 30、Rust 和 `cargo-ndk`。模型不内置于安装包，首次使用时下载。

```bash
cd app/android
./gradlew :app:assembleDebug
```

构建环境和历史测试记录见 [Android 开发文档](app/android/README.md)。

## 项目来源与许可证

本项目基于 [tancheng33/tingo](https://github.com/tancheng33/tingo) 开发，作为独立的 Android 项目维护。感谢原作者及贡献者。项目采用 [MIT 许可证](LICENSE)。
