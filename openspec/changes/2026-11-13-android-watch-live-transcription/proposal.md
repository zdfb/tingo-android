# Change: Wear OS 手表录音与手机实时转写

## Why

手机放在桌面中央时，用户仍需要拿起手机才能开始或结束录音。手表有麦克风，适合贴身操作；手机则有更大的电量、存储和 Paraformer 推理能力。把两者连接起来，可以让手表负责采集，手机负责实时识别和保存会议。

## What Changes

- 新增 Wear OS companion module，提供开始、暂停、继续、结束录音和连接状态。
- 手表以 16 kHz、单声道、16 bit PCM 分片录音，通过 `ChannelClient` 持续发送到手机。
- 手机新增 Wear stream service，接收分片、处理序号和断线状态，并把音频喂给现有实时识别接口。
- 手机通过 `MessageClient` 回传临时转写、最终句子、说话人编号和重点标记；手表显示最近几句和录音状态。
- 连接中断时手表继续写本地缓存；重连后按序补传，手机端合并为同一场会议。
- 录音结束后由手机完成最终转写、会议入库和纪要生成。

## Out of Scope

- 手表端不运行 Paraformer、说话人聚类或大模型纪要。
- 手表端不录制手机应用的系统声音；系统声音仍由手机端按现有权限处理。
- 首期不在手表展示完整会议库、详情页或 PDF。
- 首期只支持 Wear OS 手表与 Android 手机配对；不支持 iPhone 配对。

## Impact

- 新增 `app/android/wear` Wear OS module 和独立 Compose UI。
- 手机端新增 `wear` 通信、音频流协议、断点恢复和状态回传代码。
- 现有 `LiveAsr`、`RecordViewModel` 和会议库接口保持兼容，通过接口注入连接层。
- 应用需要 Wear OS Data Layer 依赖、手表麦克风权限和手表端前台录音服务。
