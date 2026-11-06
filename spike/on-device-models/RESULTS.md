# 可行性验证：端侧模型再量化（实验）

目标：回答两个问题——识别模型能否再量化一档、0.6B 语言模型量化后能否直接在设备上跑起来。
机器：M1 Pro 16 GB，纯 CPU，4 线程；工具：`llama.cpp 0.9.0`（brew 安装，`llama-cli` / `llama-quantize` / `llama-server`）。
日期：2026-11-06。

## 一、语言模型：Qwen3-0.6B 再量化

### 过程

官方 GGUF 仓库 `Qwen/Qwen3-0.6B-GGUF` 只提供 **Q8_0**（610 MB，`hf-mirror.com` 可直连下载）。
llama.cpp 默认拒绝从已量化模型再量化（`requantizing from type q8_0 is disabled`），需要显式放开：

```bash
llama-quantize --allow-requantize Qwen3-0.6B-Q8_0.gguf Qwen3-0.6B-Q4_K_M.gguf Q4_K_M
llama-quantize --allow-requantize Qwen3-0.6B-Q8_0.gguf Qwen3-0.6B-Q4_0.gguf  Q4_0
```

### 体积与速度

同一段会议转写（约 185 prompt tokens），要求输出六章节纪要 + ```fishbone 代码块，
经 `llama-server` 的 OpenAI 兼容接口调用，`temperature=0`，跑 3 次取中位数：

| 量化档 | 体积 | 生成速度（M1 Pro，4 线程） | 六章节齐全 | fishbone 代码块 |
|---|---|---|---|---|
| Q8_0（官方原档） | 610 MB | 106.6 t/s | 3/3 | 3/3 |
| **Q4_K_M** | **378 MB** | **141.3 t/s** | 3/3 | 3/3 |
| Q4_0 | 364 MB | 146.0 t/s | 3/3 | 3/3 |

一份 223 token 的纪要生成耗时 **1.7 秒**（Q4_K_M）。文件从 Q8_0 压到 Q4_K_M 缩小 38%，速度还快了 32%。

### 质量观察

0.6B 在格式上可靠（六章节与代码块都出得来），但内容质量参差：

- Q8_0 与 Q4_K_M 倾向把原话按说话人照搬进"决议/待办/待定"，三节内容雷同
- Q4_0 反而会改写成摘要式句子（"会议决定实施首屏优化方案，下周一上线"），读起来更像纪要

这说明：**格式可靠 ≠ 内容可靠**，本地纪要必须做输出自检（章节齐全 + fishbone 可解析），
并在自检不过时重试；"决议/待办/待定"三节内容重复这类问题自检查不出来，需要提示词里点名要求
（例如"待办必须是带责任人的动作项"）。0.6B 是否够用要拿真实会议人工评一轮，再决定是否升到 1.7B。

### 端侧运行（已跑通，模拟器性能不可用）

`llama.cpp b12110` 用 NDK 30 交叉编译成 Android arm64-v8a（`bin/llama` + 一组 `lib*.so`），
推到模拟器（API 36 arm64）后**能加载 Q4_K_M 模型并生成中文**：

```
$ adb shell "cd /data/local/tmp/llama && LD_LIBRARY_PATH=. ./llama cli -m Qwen3-0.6B-Q4_K_M.gguf \
    -p '用一句话总结：张三说注册转化率掉了30%，李四说要先修首屏。' -n 48 --temp 0 -t 4 -st"
[Start thinking] 好的，用户让我用一句话总结张三和李四的对话……
[ Prompt: 3.3 t/s | Generation: 0.1 t/s ]
```

**性能数字不可用于决策**：模拟器是软件模拟 CPU（本机没有硬件虚拟化直通），实测
prompt 3.3 t/s、生成 0.1 t/s，与真机差一到两个数量级。这条证据只能说明"能跑"，
真机速度要拿真机测（按公开数据，骁龙 8 Gen2 级别跑 0.6B Q4 大致 15–25 t/s 量级，
需实测确认）。

### 端侧运行的剩余工作

1. 把 `libllama.so` 与一层 JNI（或官方 `llama.android` 封装）接进 APK，token 流接到 Kotlin
2. 真机实测速度与内存：Q4_K_M 权重 378 MB + KV cache，6 GB 机型要确认能加载；
   加载失败时按设计"本机暂不支持"降级
3. 模型随用随下（不塞进 APK）

## 二、识别模型：还能再量化吗

现有选择来自 `spike/sherpa/RESULTS.md`：FunASR Paraformer-large int8，经 sherpa-onnx 运行，
约 227 MB，真实会议与 PyTorch 版差约 4%。

**结论（就官方工具链而言）：int8 是现成产物的最低档，再往下的路要自己开。** 证据在模型仓库自带的官方量化脚本
（`csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09/quantize-model.py`）：

```python
quantize_dynamic(model_input="model.onnx", model_output="model.int8.onnx",
                 op_types_to_quantize=["MatMul"], per_channel=True,
                 weight_type=QuantType.QUInt8, ...)
```

- 官方只把 MatMul 权重动态量化为 UInt8；发布物就是 `model.int8.onnx`
- 再往 int4 走需要 ONNX Runtime 的 `MatMulNBits` 路径，它面向 transformer 的线性层；
  本机 `onnxruntime 1.24.2` 有这条路径，但**这一轮没有实测**改图（fp32 主干 450 MB 的下载与
  改图调参不在本轮预算内），所以这里只作为"未验证的下一步"，不作为结论
- 已能确定的是：官方发布物就是 int8，继续量化需要自己重写量化脚本并自测，
  而 sherpa-onnx 的运行时是否接受 int4 权重也需单独验证——这条探索的性价比明显低于
  **换更小的模型**（Paraformer-small / SenseVoice-small）

**对方案的影响**：ASR 侧不再投入量化实验，模型冻结为 Paraformer-large int8 一套（约 320 MB 含标点/声纹/VAD）；
真正的端侧压力来自语言模型，所以"ASR 与 LLM 不同时驻留"写进了设计（D6/风险）。

## 三、依赖可得性（本机网络）

| 依赖 | 来源 | 状态 |
|---|---|---|
| sherpa-onnx Android AAR | JitPack `com.github.k2-fsa:sherpa-onnx`，最新 `v2.0.1`（与 Rust crate 同版本） | 可达（`maven-metadata.xml` 200） |
| Paraformer int8 / 标点 / CAM++ / VAD 模型 | `hf-mirror.com` 镜像 | 可达 |
| Qwen3-0.6B GGUF | `hf-mirror.com` 镜像（官方仓库只有 Q8_0） | 可达，610 MB 约 4 分钟 |

## 四、复现

```bash
brew install llama.cpp
mkdir -p /tmp/models && cd /tmp/models
curl -L -o Qwen3-0.6B-Q8_0.gguf \
  https://hf-mirror.com/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf
llama-quantize --allow-requantize Qwen3-0.6B-Q8_0.gguf Qwen3-0.6B-Q4_K_M.gguf Q4_K_M
llama-server -m Qwen3-0.6B-Q4_K_M.gguf --port 8099 -t 4 -c 4096 --jinja
# 然后用 OpenAI 兼容接口 POST /v1/chat/completions，
# 请求体里带 "chat_template_kwargs": {"enable_thinking": false} 关掉思考模式
```

## 五、下一步

1. 交叉编译 llama.cpp 的 Android 库并接进 APK（任务 1.4）
2. sherpa-onnx AAR 接入 + 一次真实识别（任务 1.3）
3. 用真实会议的人工评分决定 0.6B 是否够用，不够则评估 Qwen3-1.7B Q4_K_M（约 1.1 GB）
