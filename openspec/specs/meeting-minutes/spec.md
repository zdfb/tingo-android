# meeting-minutes Specification

## Purpose

纪要生成定义"把转写稿变成结构化会议纪要"这件事：提示词约束的章节结构、鱼骨图摘要的产出、
生成结果的自检与重试，以及在没有可用模型服务时的诚实失败。转写仍在本机完成，
只有这一步需要网络。

## Requirements

### Requirement: 远端生成纪要

应用 SHALL 通过用户配置的 LLM 服务把 `transcript.json` 变成 `纪要.md`（Markdown，必备
`## 一句话总结`、`## 议题与讨论`、`## 决议`、`## 待办`、`## 待定问题与风险` 五个章节；
会上确实分析了某个问题的原因时，MUST 另有 `## 原因分析` 章节与一个 ```fishbone 代码块，
否则该节整节省略——与桌面版一致：没有原因分析却硬画鱼骨图只能靠编，而"不编造"是硬约束）。

支持的协议 SHALL 与桌面版一致：OpenAI 兼容（`{base_url}/v1/chat/completions`）与
Anthropic（`{base_url}/v1/messages`）。该功能的可用性 MUST NOT 影响录音、转写与浏览。

#### Scenario: 已配置且可达

- **WHEN** 四项配置填全、模型服务可达，用户点"生成纪要"
- **THEN** 应用调用远端服务并写出 `纪要.md`，详情页按章节展示，鱼骨图被解析成图

#### Scenario: 未配置

- **WHEN** `base_url`、`api_key`、`model` 任一为空
- **THEN** 详情页的"生成纪要"按钮禁用，并说明"尚未配置模型服务"，给出跳转设置页的入口

#### Scenario: 网络不可达

- **WHEN** 请求超时或无法连接
- **THEN** 提示"连不上模型服务"并可重试；不写 `纪要.md`，会议保持"未生成纪要"

#### Scenario: 服务端拒绝

- **WHEN** 服务返回 401/402/429 等错误
- **THEN** 界面显示服务端返回的错误说明（如密钥无效、余额不足、限流），而不是笼统的"失败"

### Requirement: 输出可校验

生成结果 SHALL 在写入前自检：五个必备章节齐全；出现 `## 原因分析` 时，其中的 fishbone
代码块必须是含 `problem` 与 `branches` 的合法 JSON；自检不通过时 MUST 重试一次，
仍不通过则标注"自动生成失败，可手动编辑"而不是写入坏文件。

#### Scenario: 模型输出缺少章节

- **WHEN** 模型输出的纪要缺少"待办"章节
- **THEN** 应用重试一次；仍缺则提示用户并保留原文供手动编辑，不写入缺章节的纪要

### Requirement: 凭据存储

API key SHALL 存放在应用私有存储（SharedPreferences），MUST NOT 出现在日志、导出包或
`纪要.md` 中。

#### Scenario: 导出会议包

- **WHEN** 用户导出会议
- **THEN** 导出内容不包含 `api_key`，也不包含任何请求头

### Requirement: 默认不预置服务商

应用 MUST NOT 预置任何服务商地址、模型名或密钥；首次使用的用户需要自己填写四项配置。

#### Scenario: 全新安装

- **WHEN** 用户首次打开设置页
- **THEN** 四个输入框都是空的，页面说明需要自行填写模型服务信息
