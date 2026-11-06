# Change: Android 录音与端侧转写

## Why

地基层只解决了"看"：手机上能浏览电脑录的会议，但录不了一场新会议。要成为真正可用的
Android 客户端，必须在手机上完成"录 → 转写 → 纪要"这条主链路，且全程离线。

同时验证一条实验路线：把识别模型再量化一档、把 0.6B 的小语言模型量化后端侧直接运行，
看它们能不能在没有网络的手机上撑起"转写 + 纪要"。

## What Changes

- **录音采集**：麦克风录音（16 kHz 单声道 PCM），前台服务保活，支持暂停/继续；
  另提供"尽力而为"的系统内录（`AudioPlaybackCapture`），失败时自动回落到麦克风并说明原因
- **端侧转写**：引入 sherpa-onnx Android AAR，在 Kotlin 侧完成 VAD 切分、Paraformer int8
  识别、CT-Transformer 标点、CAM++ 声纹抽取与聚类；模型首次使用时下载并校验
- **端侧纪要（实验）**：用 llama.cpp 的 Android 构建加载量化后的 Qwen3-0.6B（Q4_K_M），
  由本地模型把转写稿写成结构化纪要（含一句话总结与鱼骨图代码块）；失败或模型缺失时
  退回"只出转写稿"，不阻塞主流程
- **实验分支产物**：量化配方与体积/效果对比写进 `spike/on-device-models/`，
  结论用于决定默认模型与最低内存门槛

## Impact

- 新增依赖：sherpa-onnx Android AAR（JNI + Kotlin API）、llama.cpp Android 构建产物、
  模型下载与校验所需的网络与存储
- 新增权限：`RECORD_AUDIO`（录音）、`FOREGROUND_SERVICE`（录音保活）、
  `FOREGROUND_SERVICE_MICROPHONE`、`POST_NOTIFICATIONS`（前台通知）；
  仍**不申请**全盘存储、定位、通讯录
- 引擎侧：`app/engine` 的转写/纪要逻辑复用既有纯逻辑（纪要解析、鱼骨图解析、聚类），
  但 ASR 推理与 LLM 推理都在 Kotlin 侧完成，Rust 不再承担神经网络推理
- 磁盘与内存：模型合计约 320 MB（ASR 一套）+ 400 MB 上下（Qwen3-0.6B Q4_K_M），
  需要"按需下载、可删除"
