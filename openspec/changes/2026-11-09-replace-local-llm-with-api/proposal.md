# Change: 纪要改走远端 LLM API，移除端侧大模型推理

## Why

端侧 0.6B 模型的实测结果不支持它作为默认路径：一小时会议的提示词按上下文裁剪到 2k token 后，
真机上首字要等 41 秒、生成 512 token 再等 30 秒；不裁剪（6.3k token）首字延迟 149 秒，
且注意力变长后解码掉到 4.9 tok/s，输出还经常缺章节。用户要的是"点了很快看到纪要"，
而这条路在手机上无论怎么调参数都到不了。

语音模型留在本地（转写离线依然可用，隐私与实时性都需要它）；只有"写纪要"这一件
语言理解任务改走远端 API——它本来也不需要实时性，换质量与速度是划算的。

## What Changes

- **删除端侧大模型推理**：删除 llama.cpp 的 JNI 封装、预编译动态库、内置 GGUF 资产、
  对应的桥接类、生成器、基准与设备测试；APK 体积因此减少约 400 MB
- **新增远端纪要**：按桌面版 `config.toml` 的同一套语义接入 LLM——`base_url` + `api_key` +
  `model` + `api`，支持 OpenAI 兼容（`chat/completions`）与 Anthropic（`messages`）两种协议
- **配置全留空**：不预置任何服务商地址与模型名，用户必须自己填；API key 存应用私有
  SharedPreferences（与界面语言同一处）
- **离线与未配置时禁用入口**：详情页"生成纪要"按钮在未配置或不可达时禁用，并说明原因、
  给出跳设置的入口——诚实失败，不让用户白等
- **保留**：转写（sherpa-onnx）、录音、库浏览、纪要的自检与重试、鱼骨图解析、Obsidian 导出的结构

## Impact

- 删除依赖：llama.cpp 构建产物（`jniLibs/*/libllama.so`、`libggml*.so`）、`cpp/llama_jni.cpp`、
  NDK 侧 CMake 目标、`assets/llm.json`、内置 GGUF
- 新增网络调用：`POST {base_url}/v1/chat/completions` 或 `POST {base_url}/v1/messages`；
  仍需 INTERNET 权限（已有）
- 规格：`on-device-minutes` 能力被 `meeting-minutes` 取代（原能力的"端侧/离线"表述不再成立）
- 文档：README 的真机吞吐表格删除，换成"远端纪要 + 本地转写"的架构说明与配置指引
- 迁移：已内置 GGUF 的旧安装升级后不再需要该文件，安装时清理
