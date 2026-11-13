# Design

## 模块结构

```
app/android/app/                 # 手机端
  .../wear/WearStreamService     # 接收音频、回传状态
  .../wear/WearProtocol          # 帧与控制消息
app/android/wear/                # Wear OS companion
  .../recording/WearRecorder     # 麦克风与本地分片
  .../communication/WearLink     # ChannelClient / MessageClient
  .../ui/WearHomeScreen          # 极简录音界面
```

两个模块使用相同的 applicationId 前缀和同一签名，Data Layer path 固定为 `/tingo/recording`、`/tingo/control`、`/tingo/transcript`。

## 音频流

手表录制 16 kHz、单声道、16 bit PCM。每帧包含 `sessionId`、单调递增 `sequence`、采样率、声道数、采样数和 payload。手机只接受当前会话的帧；重复帧幂等丢弃，跳号进入缺帧状态并请求补传。

实时路径使用 `ChannelClient`，因为它提供面向连续字节流的连接；开始、暂停、结束和状态更新使用 `MessageClient`。普通 `DataItem` 只保存小型状态，不承载音频。通道断开期间，手表把帧写入应用私有缓存，并在恢复后按序发送。

## 识别与回传

手机收到帧后写入有上限的环形缓冲区，交给现有 `LiveAsr`。识别线程不能阻塞音频接收；缓冲区满时优先保留录音文件，并把 `TRANSCRIPTION_LAGGING` 状态回传给手表。临时结果带 `sessionId` 和 `source=watch`，最终转写仍以手机保存的完整录音为准。

## 权限与生命周期

手表端在开始录音前申请 `RECORD_AUDIO`，并启动 microphone 类型前台服务；录音通知提供停止操作。手机端接收服务由 Data Layer 唤起，但只有当前会话才打开音频流。用户结束录音、手机端收到结束帧或会话超时后，双方关闭通道并原子保存状态。

## 断线策略

- 连接断开小于 30 秒：手表继续录音并缓存分片，手机显示“等待手表连接”。
- 恢复连接：手表先发送缓存索引，手机返回缺失序号，再按序补传。
- 缓存空间不足：停止实时发送但继续保留已写录音，提示用户尽快靠近手机；不丢弃已落盘数据。
- 最终录音同步失败：保留手表文件，手机会议显示“待同步”，下次连接继续处理。

## TDD seams

1. `WearProtocol`：帧编码、解码、序号、重复与跳号。
2. `WearRecorderPort`：采集、暂停、恢复、分片和本地缓存，测试不调用真实麦克风。
3. `WearStreamReceiver`：连接状态、背压、缺帧补传和会话结束，测试使用内存通道。
4. `WearTranscriptionBridge`：音频帧到 `LiveAsr`、临时结果回传和延迟状态。
5. `WearHomeScreen` 与手机录音状态组件：测试可见状态和按钮操作，不测像素。

## 验收指标

- 连接稳定时，手机临时文字端到端延迟目标为 1～3 秒。
- 断线期间手表不丢已落盘音频，重连后序号连续。
- 结束后手机会议库只生成一场会议，最终转写使用完整音频。
- 手表无法捕获系统声音时，界面不显示“已录到系统声音”。
