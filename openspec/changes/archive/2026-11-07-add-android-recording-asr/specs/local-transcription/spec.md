# local-transcription

## ADDED Requirements

### Requirement: 离线转写

应用 SHALL 在设备本地完成语音转写，全程不依赖网络；转写结果 MUST 写入会议目录的
`transcript.json`，字段与桌面版 v0.5.2 兼容（`source`、`duration_ms`、`sentences`、
`speaker_names`），以便同一场会议在桌面与手机上都可打开。

#### Scenario: 录完立刻转写

- **WHEN** 录音结束且模型已就绪
- **THEN** 应用在后台完成转写并写入 `transcript.json`，会议列表显示"已有转写"

#### Scenario: 断网转写

- **WHEN** 设备处于飞行模式且模型已下载
- **THEN** 转写正常完成，不出现任何网络请求

### Requirement: 模型按需下载与校验

首次使用前 SHALL 下载模型（识别、标点、声纹、VAD 合计约 320 MB），下载 MUST 支持断点续传与
进度显示，完成后 MUST 校验文件大小/摘要；校验失败 MUST 重新下载而不是带病运行。

#### Scenario: 首次使用

- **WHEN** 用户第一次点"转写"
- **THEN** 界面显示模型下载进度与总体积，完成后自动继续转写

#### Scenario: 下载被中断

- **WHEN** 下载到一半网络断开
- **THEN** 下次继续时从中断处续传，不从头开始

#### Scenario: 模型文件损坏

- **WHEN** 模型文件校验失败
- **THEN** 该文件被删除并重新下载，界面说明"模型文件损坏，正在重新下载"

### Requirement: VAD 分段与长音频

转写 SHALL 先用 VAD 切分语音段再送入识别，长录音（≥1 小时）MUST 分块处理并保持时间戳
相对录音起点，且 MUST NOT 一次性把整段音频读入内存。

#### Scenario: 一小时会议

- **WHEN** 转写一段 60 分钟的录音
- **THEN** 内存占用保持平稳（不随录音总长线性增长），产出的时间戳单调递增且与音频对得上

### Requirement: 说话人区分

转写 SHALL 为每个语音段抽 CAM++ 声纹并聚类，把同一说话人的段落合并为一个 turn，并在
`speaker_names` 里给出默认名（`说话人 1`…）；用户改过的名字 MUST 被保留，不被重新转写覆盖。

#### Scenario: 两人会议

- **WHEN** 转写一段两人对话
- **THEN** 结果里出现两个不同说话人，且同一人的发言被合并到同一个 turn

#### Scenario: 用户改过名字之后再转写

- **WHEN** 用户把"说话人 1"改成"林舟"后重新转写
- **THEN** 结果仍叫"林舟"

### Requirement: 标点与断句

识别结果 SHALL 经标点模型恢复标点与大小写，句子边界 MUST 与时间戳边界一致
（一句话一个 `sentence`，起止毫秒覆盖该句音频）。

#### Scenario: 产出句子级时间戳

- **WHEN** 转写完成
- **THEN** 每个 `sentence` 都有 `start_ms` / `end_ms`，界面点击时间戳能定位到该句

### Requirement: 转写可取消

转写 SHALL 可被用户取消；取消后 MUST 在 1 秒内停止，已完成的段落 MUST 保留，且不留下半截 JSON。

#### Scenario: 取消转写

- **WHEN** 用户在转写进行中按"取消"
- **THEN** 一秒内停止推理，`transcript.json` 要么不存在要么内容完整，界面回到"未转写"状态
