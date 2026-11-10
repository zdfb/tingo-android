# Tasks

- [x] 抽出 `AsrModels`，转写管线与实时转写共用同一份模型配置
- [x] `LiveAsr` 接口 + `LiveTranscriber`（512 采样窗口对齐、200ms 最短段、尾音补零收尾、关闭后丢弃、关掉即释放模型）
- [x] `MicRecorder.onSamples`（16 bit → 浮点，采集线程上只入队）
- [x] `RecordViewModel.liveLines`（最近 3 条）+ 录音页实时文字面板 + 提示"最终以转写为准"
- [x] 录完自动进会议并自动转写（`pendingAutoTranscribe`）
- [x] 转写完成后按规则自动写纪要（`AutoChain.shouldAutoStartMinutes`，6 条用例）
- [x] `FishboneGrounding`（时间戳解析、+999ms 容差、去重、20 字门限、摘要统计，11 条用例）
- [x] 鱼骨卡片：摘要 + 每条分支结论 + 时间戳可点回听
- [x] 鱼骨分支展开引用原话（时间戳 + 说话人 + 原话）
- [x] 说话人聚类换成桌面谱聚类（12 条用例；300 段子采样一次 ~0.2s 是本地观察值，没有断言）
- [x] FFI `delete_audio(id, skip_ids)` + 批量 `delete_audio`（真删、忙碌拒绝、没录音报错；5 条 Rust 用例，含"删过录音的会议仍能整场删除/恢复"回归）
- [x] 详情页「只删录音」（确认框明说不可恢复 + 腾出空间提示 + 删完播放条消失）
- [x] 改名对话框「保存并重新生成纪要」
- [ ] 真机人工验收：实时文字、鱼骨展开、只删录音确认框（等设备解锁）
- [ ] 对照表 #9 的"卡片内 Excalidraw 按钮"（导出已在同页菜单，入口位置不同）
