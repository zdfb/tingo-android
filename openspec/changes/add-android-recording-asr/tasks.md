# 任务：Android 录音与端侧转写

## 1. 模型与运行时可行性（先验证再改代码）

- [x] 1.1 量化实验：用 llama.cpp 把 Qwen3-0.6B 从 Q8_0 再量化到 Q4_K_M 与 Q4_0，记录体积、加载时间、生成速度与同题输出差异；产物与命令写进 `spike/on-device-models/`；验证：`RESULTS.md` 里有三档对比表与可复现命令（Q8_0 610 MB / 107 t/s、Q4_K_M 378 MB / 141 t/s、Q4_0 364 MB / 146 t/s，3 次取中位数）
- [x] 1.2 ASR 模型可得性：四个模型的地址、体积与 sha256 已写进 `assets/models.json` 并经真实下载核对（合计 331 MB）；设备级测试验证了"清单 → 真实 HTTPS 下载 → 校验 → 交给 sherpa"整条链路
- [x] 1.3 sherpa-onnx Android AAR 接入验证：能拉取或本地引入 AAR，跑通一次 `OfflineRecognizer` 初始化；验证：设备级 `RecognitionInstrumentedTest` 用打包音频识别出中文文本，`PipelineInstrumentedTest` 跑通 VAD→识别→标点→声纹全链路
- [x] 1.4 llama.cpp Android 构建验证：交叉编译出 `libllama.so` 并打进 APK（arm64-v8a 与 x86_64）；验证：`scripts/build-llama-android.sh` 构建四个 so，`LlamaInstrumentedTest` 在设备上加载 Q4_K_M 并生成文本；模拟器是软件模拟 arm64，速度不可当真机指标（真机数据见 `spike/on-device-models/RESULTS.md`）

## 2. 录音采集

- [x] 2.1 实现 `AudioRecord` 录音器与 wav 写入（16 kHz / 单声道 / 16 bit），结束或中断时回填 wav 头；验证：`WavWriterTest` 断言 wav 头字段与时长一致，`AudioMixTest` 验证合成后的文件可被读回
- [x] 2.2 实现录音状态机（空闲/录音中/暂停/结束/出错）与计时；验证：`RecordViewModelTest` 覆盖暂停不计时、出错回到空闲、中断保留文件
- [x] 2.3 前台服务与常驻通知：`RecordingService`（`microphone` 类型）+ 通知栏显示「正在录音 · mm:ss / 已暂停」；服务被系统结束时把录音判为中断失败，已写入部分保留；验证：JVM 单测覆盖"何时该挂前台"与通知文案，真机手动确认锁屏不中断
- [x] 2.4 权限流程：`RECORD_AUDIO` 与 `POST_NOTIFICATIONS` 的请求、拒绝后的降级路径；验证：`RecordScreen` 里两个 launcher 覆盖拒绝路径，拒绝录音权限时界面给出原因且不影响浏览

- [x] 2.5 系统内录开关（`MediaProjection` + `AudioPlaybackCapture`），不可捕获时回落并解释；
  验证：`SystemAudioSupportTest` 覆盖三态回落，`AudioMixTest` 覆盖降声道/重采样/混音，
  `RecordViewModelTest` 覆盖 `mic.wav`→`录音.wav` 合成；真实抓音需真机人工验收（见 design 偏差记录）
- [x] 2.6 进程被杀后的"未完成录音"恢复：启动时补好 wav 头（`InterruptedRecordings`），而不是打标签；验证：`InterruptedRecordingsTest` 构造半截 wav 断言被修复、正常文件不动、空目录安全（与规格的偏差见 design 偏差记录）

## 3. 转写流水线

- [x] 3.1 模型管理：清单 JSON、断点续传下载、sha256 校验、删除模型；验证：JVM 单测 8 个覆盖续传、校验失败重下、删除与占用统计；设备级测试覆盖真实 HTTPS 下载
- [x] 3.2 VAD 分段：silero VAD 流式切段并限制单段长度；验证：`PipelineInstrumentedTest` 在设备上切出 6 段，`AudioMixTest` 覆盖流式读入的边界
- [x] 3.3 识别 + 标点 + 切句：产出带毫秒时间戳的句子；验证：`SentenceSplitterTest` 断言切句与时间戳单调、不重叠、不丢时长；设备测试断言句子数为 6
- [x] 3.4 声纹与聚类：CAM++ 抽取 + 余弦平均连接聚类，产出 speaker 分组；验证：`SpeakerClusteringTest` 覆盖阈值边界，设备测试断言两人会议分出两个说话人（`speakers=[0, 1]`）
- [x] 3.5 写 `transcript.json`（与桌面 v0.5.2 同构，原子写入）；验证：`TranscriptJsonTest` 断言字段与往返一致，设备测试产出文件被引擎 `read_meeting` 读出
- [x] 3.6 取消：段间与流式回调检查取消，不留半截文件；验证：`TranscriptionViewModelTest` 断言取消后状态回到 Idle、不写文件，且不会被误判成"模型损坏"而重下
- [x] 3.7 用户改过的说话人名字不被覆盖；验证：`SpeakerNamesCarryOverTest` 断言按编号沿用旧名字、新增说话人不复用旧名

## 4. 端侧纪要（实验）

- [x] 4.1 端侧纪要生成：提示词与 `summarize.rs` 章节结构一致、输出校验（章节齐全 + fishbone 合法 JSON）、不合格重试一次；验证：`MinutesService` 与 `MinutesViewModel` 单测覆盖重试、放弃与写盘
- [x] 4.1b llama.cpp Android 封装：手写最小 JNI（`LlamaBridge`：load/generate/free，greedy 解码）加载 GGUF；验证：`LlamaInstrumentedTest` 在设备上生成非空文本
- [x] 4.2 提示词与输出自检（五必备章节 + 出现「原因分析」时 fishbone 必须是合法 JSON），失败重试一次；验证：`MinutesPromptTest` 覆盖缺章节/坏代码块，`MinutesServiceTest` 覆盖重试一次并保留草稿
- [x] 4.3 内存与降级：捕获 `OutOfMemoryError` 给出"本机暂不支持本地纪要生成"，转写与录音不受影响；验证：`MinutesViewModelTest.outOfMemoryDegradesToUnsupportedMessage`
- [x] 4.4 纪要页接入：生成按钮、进度、失败重试、生成完成自动刷新章节；验证：`MinutesViewModelTest` + `DetailRoute` 的 Done→load 重载

## 5. 会议管理与导出

- [x] 5.1 搜索：标题/一个人总结/标签的本地搜索，命中处高亮；验证：`LibraryViewModelTest` 覆盖匹配规则，`HighlightTest` 覆盖高亮切分
- [x] 5.2 导出：`录音.wav` + `transcript.json` + `纪要.md` 打包成 zip 经 SAF `CreateDocument` 写到用户选的目录；同时保留"导出 Markdown 走分享面板"；验证：`MeetingExporterBundleTest` 断言 zip 条目、缺项跳过、空会议返回 null、内容可读回
- [x] 5.3 归档与删除：归档切换、删除前确认、删除只影响该会议目录，失败会给出提示；验证：`EngineBridgeInstrumentedTest` 与 `LibraryViewModelTest` 覆盖

## 6. 双语与收尾

- [x] 6.1 新增文案全部补齐中英文；验证：`StringsParityTest` 断言两套资源 key 集合一致
- [ ] 6.2 设置页显示模型占用、下载状态与删除入口；验证：设备测试断言占用数字出现并可删除
- [ ] 6.3 README 增补：录音与转写用法、模型来源与许可、内录限制与替代办法、内存门槛；验证：按文档从干净状态走一遍
- [x] 6.4 设备级回归：录音 → 转写 → 纪要在模拟器上跑通；验证：设备测试 31 个全绿，含端到端流水线与模型下载

## 7. 回听

- [x] 7.1 播放器：详情页播放/暂停与进度条，点击某句跳到该句开头并存进 `PlaybackState`；
  没有 `录音.wav`（或只有 44 字节头）时不出现播放条，点击是空操作；
  验证：`DetailPlaybackTest` 覆盖可用性判定、seek 并播放、暂停/恢复、进度换算、ViewModel 清理时释放
