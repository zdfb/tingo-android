# Wear OS 实时录音 Specification

## Purpose

定义 Wear OS 手表采集麦克风、与 Android 手机传输音频并接收实时转写的行为。手表负责采集和缓存，手机负责识别、保存和生成纪要。

## Requirements

### Requirement: 手表麦克风录音

手表应用 SHALL 在用户明确点击开始并授予麦克风权限后，以 16 kHz、单声道、16 bit PCM 录音。录音 SHALL 在 microphone 前台服务中持续，锁屏后 MUST 继续。

#### Scenario: 开始与结束

- **WHEN** 用户在手表点击开始并授予权限
- **THEN** 手表创建带 sessionId 的录音会话并开始写入本地分片
- **WHEN** 用户点击结束
- **THEN** 手表停止采集、关闭分片并通知手机结束会话

### Requirement: 手机实时识别

手机 SHALL 通过 Wear OS `ChannelClient` 接收当前会话的音频帧，并把连续帧送入现有实时识别接口。识别 SHALL 在手机端执行，手表端 MUST NOT 依赖语音模型。

#### Scenario: 连接稳定

- **WHEN** 手表持续发送带序号的音频帧且手机在线
- **THEN** 手机按序接收并产生临时转写，目标延迟为 1～3 秒

### Requirement: 双向状态同步

手机和手表 SHALL 使用 `MessageClient` 同步开始、暂停、继续、结束、连接、缺帧和识别延迟状态。临时文字 SHALL 带 sessionId，过期会话的结果 MUST 被丢弃。

### Requirement: 断线缓存与补传

手表 SHALL 在通道断开时继续本地录音并缓存未确认帧。重连后手机 SHALL 返回缺失序号，手表按序补传；缓存不足时 MUST 提示用户，但不得删除已落盘音频。

#### Scenario: 断线恢复

- **WHEN** 手表录音中连接断开 20 秒后恢复
- **THEN** 手表补传断线期间的帧，手机最终得到连续会话并继续实时识别

### Requirement: 最终会议入库

录音结束后手机 SHALL 将完整音频保存到现有会议库，并使用手机端最终转写流程生成 `transcript.json`。手表端只保留同步状态和必要的本地缓存。

### Requirement: 音频来源边界

手表端 SHALL 只声明麦克风录音；系统声音捕获、MediaProjection 和手机应用音频 SHALL 继续由手机端现有流程处理。
