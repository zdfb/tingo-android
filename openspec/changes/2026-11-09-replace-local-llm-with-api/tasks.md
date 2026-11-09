# Tasks

## 1. 端侧推理的移除

- [x] 1.1 删除 `cpp/llama_jni.cpp` 与 CMake 的 llama 目标、`jniLibs` 预编译库
- [x] 1.2 删除 `LlamaBridge`、`LlamaMinutesGenerator`、`assets/llm.json` 与内置 GGUF
- [x] 1.3 删除 llama 相关设备测试与基准（吞吐/调优/长上下文/端到端/长转写）
- [x] 1.4 清理 `build.gradle.kts` 的 GGUF 同步与校验、`AppContainer` 的模型装配

## 2. 远端纪要（TDD）

- [x] 2.1 `LlmConfig`：四项配置 + `isConfigured`，存取进 `AppSettings`
- [x] 2.2 `HttpJsonClient` 端口 + `UrlHttpJsonClient`（HttpURLConnection，超时与错误映射）
- [x] 2.3 `LlmRequests`：两种协议的请求体/鉴权头/响应解析
- [x] 2.4 `RemoteMinutesGenerator`：实现 `MinutesGenerator`，把 HTTP 错误映射成用户能懂的话

## 3. 界面与门禁

- [x] 3.1 设置页：四项输入 + 协议选择 + 保存，未填全时提示
- [x] 3.2 详情页：未配置/不可达时禁用生成按钮并说明原因，给"去设置"入口

## 4. 收尾

- [x] 4.1 规格：新增 `meeting-minutes`，归档 `on-device-minutes`
- [x] 4.2 README 与文档：架构、配置指引、删除真机吞吐表
- [x] 4.3 单测 + 设备测试全绿；双轴 code review
