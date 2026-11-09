# Design: 远端纪要

## 协议与请求体

两种协议共用一个端口 `HttpJsonClient`，只在"请求体怎么拼、响应怎么取、鉴权头叫什么"上分叉：

| 协议 | 鉴权头 | 路径 | 取文本的位置 |
|---|---|---|---|
| OpenAI 兼容 | `Authorization: Bearer <key>` | `{base}/v1/chat/completions` | `choices[0].message.content` |
| Anthropic | `x-api-key: <key>` + `anthropic-version: 2023-06-01` | `{base}/v1/messages` | `content[0].text` |

`base_url` 允许带或不带尾部 `/`，也允许用户直接填到 `/v1`（DeepSeek 的常见写法）；
拼接时统一剥掉尾部斜杠再补路径，避免出现 `//v1`。

## 错误处理

- HTTP 非 2xx：读 error stream 里的 JSON，取出 `error.message` 塞进异常消息——
  用户的 key 过期或余额不足时，屏幕上的话必须能直接指向问题
- 网络不可达 / 超时：抛 `LlmUnavailable`，界面文案是"连不上模型服务"，与"配置错误"区分开
- 响应里没有正文（模型拒答、被截断）：如实报错，不写空的 `纪要.md`

## 配置项

`base_url` / `api_key` / `model` / `api`（`openai` | `anthropic`）四项，全部默认空。
`isConfigured` 的判断只要求三项非空（`api` 有默认 `openai`），避免"填了地址没填模型"这种半配置状态
跑起来再报错。

## 为什么不复用桌面版的配置同步

桌面版把配置存在 `~/.config/tingo/config.toml`，手机拿不到那个文件。用户要在手机上重填一次，
所以界面必须把四项都露出来，而不是"扫码同步"这类额外机制。
