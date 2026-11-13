# Tasks

- [x] 建立 `wear` Wear OS module、Manifest、Compose 入口和共享签名配置。
- [x] 定义 `WearProtocol` 帧格式、控制消息、状态消息和版本字段。
- [x] 先写协议编解码、序号幂等和缺帧测试，再实现协议代码。
- [ ] 先写 `WearRecorderPort` 的状态机测试，再接入手表麦克风和本地分片文件。
- [x] 先写手机 `WearStreamReceiver` 的内存通道测试，再接入 `ChannelClient`。
- [ ] 先写 `WearTranscriptionBridge` 的实时结果测试，再接入 `LiveAsr` 和 `RecordViewModel`。
- [ ] 实现手表开始/暂停/继续/结束界面、前台通知和断线提示。
- [ ] 实现手机端最近转写、连接状态和录音来源标记。
- [ ] 实现断线缓存、缺帧补传和结束后的原子入库。
- [ ] 完成 Wear OS 模拟器与真实手表的连接、锁屏录音、断线恢复和最终转写验收。
- [ ] 运行 JVM/Compose 相关测试和 Debug 构建。
- [ ] 以 `origin/main` 为审查基点执行 Standards / Spec 双轴 code review，修复后复审至两轴无未决问题。
