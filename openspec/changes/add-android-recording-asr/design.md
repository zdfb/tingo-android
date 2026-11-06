# 设计：Android 录音与端侧转写

## 上下文

地基层（`add-android-port-foundation`，已交付）建立了：共享引擎（`--no-default-features` 交叉编译）、
UniFFI 桥接、会议库只读浏览、窄屏/宽屏外壳、双语与测试管线。本变更只加"录 → 转 → 纪要"这条链路，
不推翻既有结构。

已有一份桌面侧可行性验证（`spike/sherpa/RESULTS.md`）：ASR 选 FunASR Paraformer-large int8（经
sherpa-onnx 运行），配 CT-Transformer 标点、CAM++ 声纹、silero VAD，合计约 320 MB，真实会议
与 PyTorch 版本差异约 4%，70 倍实时。

## 偏差记录

实现过程中与初版规格不一致、且有意为之的地方，都在这里记一笔：

- **纪要章节数**：初版规格写"六个章节齐全 + 一个 fishbone 代码块"绝对成立。实现与桌面版一致：
  五个章节必备，「原因分析」只在会上真的分析过原因时出现。理由是"不编造"优先——没分析原因
  却硬凑鱼骨图，只能让模型编。规格已按此修订。
- **句子时间戳是插值而非实测**：一句话的时间范围由所在语音段按字符数加权分摊，不是逐字对齐的
  实测时间。Paraformer 给出的 token 级时间戳没有直接用于切句：标点模型插入的句号不落 token 边界，
  两套时间轴对齐的复杂度高于收益。误差在一句话内部，不影响"按句回听"的可用性。
- **"未完成录音"的处理方式**：初版规格要求下次启动时把未收尾的录音**标记**为"未完成"。实现改为
  启动时直接**修复**（补好 wav 头，使它可播放、可转写），列表不新增状态字段——引擎的
  `meta.json` 没有这个字段，为了一个标签改跨版本格式不划算。规格已按此修订。
- **系统内录的验证边界**：`MediaProjection` + `AudioPlaybackCapture` 已实现（`SystemAudioCapture`），
  但这条通路**无法在自动化测试里验证真实音频**：模拟器没有真实播放源，会议软件也普遍禁止被抓。
  因此把可测的部分全部下沉到纯函数：`SystemAudioSupport.decide`（系统版本/授权/开关三态）、
  `AudioMix`（立体声降单声道、48k→16k 重采样、双轨混音削顶）、`TrackMerger`（`mic.wav`+`system.wav`
  → `录音.wav`），共 13 个单测。真机上的完整链路（授权→落盘→合成）仍需人工验收一次，
  README 的"已知限制"里写明了这条。

## 决策

### D1. 推理放在 Kotlin 侧，Rust 只做纯逻辑

理由：sherpa-onnx 官方提供 Android AAR（含 arm64-v8a / x86_64 的 .so），Kotlin 直接调用可以省掉
一层 JNI 封装；而纪要解析、鱼骨图解析、说话人聚类这类纯逻辑已经在 Rust 里有测试覆盖，继续留在 Rust。
边界与地基层一致：Rust 负责"文件和文本怎么理解"，Kotlin 负责"音频和模型怎么跑"。

### D2. 录音用 `AudioRecord` + 前台服务，不用 `MediaRecorder`

`AudioRecord` 能拿到原始 PCM，便于实时算音量、做 VAD 与"边录边转"；`MediaRecorder` 只能出编码文件，
后续要么再解码要么交给识别器二次读盘。前台服务用 `foregroundServiceType="microphone"`，锁屏保活。

### D3. 系统内录"尽力而为"，不做承诺

`AudioPlaybackCapture` 只能采到 `USAGE_MEDIA` / `USAGE_GAME` / `USAGE_UNKNOWN`；会议类应用
（腾讯会议、钉钉、Zoom）用的是 `USAGE_VOICE_COMMUNICATION`，按设计就采不到。因此：
- 提供开关，失败即自动回落麦克风并解释原因
- 真正可行的替代（区域录屏、`REMOTE_SUBMIX` + 特权权限、WebView 内自托管客户端）写进 README，
  不在本变更里承诺

### D4. 转写流水线：VAD → 识别 → 标点 → 声纹 → 聚类

顺序固定，每一步都能单独跑测试：
1. silero VAD 切出语音段（带起止毫秒）
2. Paraformer int8 逐段识别（限制单段最长 30 秒，超长二次切分）
3. CT-Transformer 标点恢复，按标点切句，句边界继承段时间
4. CAM++ 逐段取声纹
5. 聚类（移植桌面版谱聚类，自动估人数）→ 同一说话人的相邻段合并成 turn
6. 写入 `transcript.json`（与桌面 v0.5.2 同构）

### D5. 模型管理：按需下载、可校验、可删除

- 模型清单（文件名、体积、sha256）随 APK 打包成 JSON，下载后校验
- 下载到应用私有目录 `files/tingo/models/`，用 HTTP Range 续传
- 设置页显示占用与"删除模型"入口
- 模型与 APK 解耦：APK 不带模型，避免 300 MB+ 的安装包

### D6. 端侧纪要：llama.cpp + Qwen3-0.6B 量化（实验）

- 运行：`llama.cpp` 交叉编译成 Android 库（`libllama.so`），用 minimal JNI 或现成的
  `llama.android` 封装；模型用 GGUF，Q4_K_M 优先，内存不足时降级到 Q4_0 或直接不可用
- 输入：转写稿按 token 预算截断（保留开头 + 每个说话人的若干段）；提示词要求
  五个必备章节齐全，「原因分析」只在会上真的分析过原因时才出现（见偏差记录）
- 输出：写盘前自检章节与 fishbone 代码块，自检失败重试一次，再失败则提示手动编辑
- 失败即降级：录音、转写、浏览不受影响

### D7. 可取消

转写与纪要生成都在协程里跑，用 `ensureActive()` 在段与段之间检查取消；取消时不留半截 JSON
（先写临时文件再原子改名）。

### D8. 测试策略

- Kotlin JVM 单测：状态机（录音、转写、纪要）、wav 头回填、续传逻辑、输出自检
- 设备级测试：麦克风权限拒绝、前台服务存活、wav 可播放、转写冒烟（用打包进测试的短音频 + 小模型）、
  取消后 1 秒内停止
- 基准：`spike/on-device-models/RESULTS.md` 记录量化档位的体积/速度/效果

## 风险

- **[AAR 可用性]** sherpa-onnx 的 Android AAR 在 JitPack，国内网络可能慢或不可达 → 先验证可达并
  写好备用方案（把 AAR 作为本地文件放在 `app/android/libs/` 并注明来源与校验值）
- **[内存]** 6 GB 内存设备上 ASR + VAD + 标点同时驻留约 1 GB，叠加 LLM 会吃紧 → LLM 与 ASR 不同时驻留，
  模型按阶段加载与释放
- **[模型许可]** 模型许可要单独确认并在设置页标注来源
- **[内录预期落差]** 用户会期望"录到对方声音" → 界面必须直接说明限制，README 给可行替代
