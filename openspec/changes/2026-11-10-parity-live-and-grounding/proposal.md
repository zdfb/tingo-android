# Change: 边录边转、鱼骨图核对、说话人聚类对齐（桌面端功能对齐第二批）

## Why

第一批做完了"会议库当资料库用"。这一批补齐对照表 `docs/android-parity.md` #4 与 #9 里
剩下的实质差距——它们都影响**产出本身对不对**，而不是界面好不好看：

- 桌面边录边转，Android 要等录完才有字（用户无法确认麦克风是否真的在工作）
- 桌面的鱼骨图会给每条分支核对原文（"引用 3 段 68 字" / "无原文依据"），
  Android 只画时间戳胶囊，等于把编出来的原因和有原话的原因画成一样
- 两边的说话人聚类算法不同（桌面谱聚类 / Android 平均连接），同一份声纹给出的人数不一样

## What Changes

- **边录边转**：`LiveTranscriber` 把麦克风样本按 Silero VAD 切段、逐段用同一个离线模型识别，
  录音页显示最近 3 句（不可编辑，标注"最终以录完后的转写为准"）。
  采集线程只入队、不推理；识别跟不上时丢新样本而不是排队（录音是主线）。
- **录完自动链**：录音结束 → 进这场会议 → 自动转写 → 若已配模型服务且还没有纪要则接着写纪要；
  规则抽成纯函数 `AutoChain.shouldAutoStartMinutes`，条件不满足就撤掉标记、不会重复尝试。
- **鱼骨图核对**：`FishboneGrounding` 逐条照抄桌面 `fishbone.ts`（+999ms 容差、20 字算"依据很少"、
  重复引用只算一次、没有转写时一律"无原文依据"）；卡片给摘要与每条分支结论，
  时间戳可点回听，分支可展开"谁说、什么时候、说了什么"。
- **说话人聚类**：换成桌面 `cluster.rs` 的谱聚类（n<20 算一人、上限 15、合并 0.78、剪枝 0.022、
  k-means 固定种子）；纯 Kotlin 的 Jacobi 分解上限 256 段，其余按余弦归档。
- **只删录音**：详情页溢出菜单新增「只删录音」（移到废纸篓，转写与纪要保留）；
  同时修正 FFI `delete_audio` 原来直接 `remove_file` 的语义错（桌面是移到废纸篓）。
- **保存并重新生成纪要**：改完说话人名字可直接重新生成（`TextInputDialog` 支持次要动作）。

## Impact

- 新增 `transcription/AsrModels.kt`（四个模型的共享创建）、`transcription/LiveAsr.kt`、
  `recording/LiveTranscriber.kt`、`ui/detail/AutoChain.kt`、`ui/detail/FishboneGrounding.kt`
- `SpeakerClustering.kt` 重写（公开 API 不变，构造参数从 `threshold` 改为 `maxSpeakers/limit`）
- `MicRecorder.onSamples`、`RecordViewModel.liveLines`、`DetailViewModel.deleteRecording/hasAudio`
- 引擎：`app/engine-ffi/src/lib.rs` 新增 `delete_audio`，批量 `delete_audio` 改为进回收站
- 规格：`local-transcription` 增加"边录边出字"与"谱聚类"两条需求，
  `meeting-minutes` 增加"鱼骨图核对原文"一条需求，`meeting-library-read` 增加"只删录音"一条需求
- 风险：边录边转与录音同进程抢 CPU（识别跟不上时丢样本，不阻塞录音）；
  谱聚类子采样上限 256 段，与桌面 1500 的结果可能不同（已在类注释与对照表记录）
