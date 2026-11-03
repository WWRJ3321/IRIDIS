# 本地 API

在 App 的 **设置 › API 服务** 中开启。默认只监听 `127.0.0.1:8686`；开启「允许局域网访问」后监听所有网卡，且强制使用 API Key。

Base URL：`http://127.0.0.1:8686/v1`（局域网地址在 API 页面显示）

## OpenAI 兼容

### `GET /v1/models`
列出手机上已安装的模型，`id` 为文件名。`GET /v1/models/{id}` 返回单个模型（不存在为 404）。

### `POST /v1/chat/completions`
| 字段 | 说明 |
|------|------|
| `model` | 已安装模型的文件名 / 显示名；`"default"` 或省略 = 当前已加载的模型（未加载则加载上次使用的模型）。名称不匹配任何已安装模型时**使用当前已加载的模型**（兼容写死 `gpt-4o-mini` 等名字的客户端），只有在没有已加载模型时才返回 404。指定另一个模型会切换 App 当前模型；若此时引擎正在生成（App 内对话或其他请求），返回 503 而不会打断它 |
| `messages` | `system` / `developer` / `user` / `assistant`；`content` 可为字符串或文本 part 数组 |
| `stream` | `true` 时返回 SSE |
| `stream_options.include_usage` | 流式结束前附带 usage |
| `temperature` `top_p` `top_k` `min_p` `repeat_penalty` | 采样参数，未提供时使用 App 设置 |
| `presence_penalty` `frequency_penalty` `seed` | 采样参数，未提供时使用 App 设置 |
| `max_tokens` / `max_completion_tokens` | 最大生成长度 |
| `stop` | 字符串或字符串数组（最多 16 个）；命中即停止生成，返回内容不含停止串，`finish_reason` 为 `stop`。提前停止时 `usage` 为估算值（`prompt_tokens` 为 0） |
| `enable_thinking` / `think` | `false` 关闭推理模型的思考过程 |

推理模型的思考内容在 `reasoning_content` 字段返回（与 DeepSeek API 一致），正文在 `content`。`stop` 只作用于正文。

不支持的字段会被忽略：`n`（始终 1 个结果）、`tools` / `tool_choice`（`tool` 角色消息会被丢弃）、`response_format`、`logprobs`。仅支持文本内容，图片 part 返回 400。

流式响应为 SSE：首个 chunk 为 `delta.role`，随后每个 chunk 一个 `delta.content` / `delta.reasoning_content`，最后一个 chunk 带 `finish_reason`（`stop` / `length`），可选 usage chunk，最后 `data: [DONE]`。生成中途出错时发送一条 `data: {"error":{...}}` 后接 `data: [DONE]`。

```bash
curl http://127.0.0.1:8686/v1/chat/completions \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"stream":true,"messages":[{"role":"user","content":"用一句话介绍你自己"}]}'
```

```python
from openai import OpenAI
client = OpenAI(base_url="http://192.168.1.23:8686/v1", api_key="sk-iris-...")
for chunk in client.chat.completions.create(
        model="default", stream=True,
        messages=[{"role": "user", "content": "你好"}]):
    print(chunk.choices[0].delta.content or "", end="")
```

## Ollama 兼容
- `GET /api/tags`
- `POST /api/chat`（默认 NDJSON 流式；`"stream": false` 返回单个对象；`options` 中可传采样参数，`num_predict` 为最大长度，`stop` / `seed` 同上）
- `/api/*` 的错误为 Ollama 格式：`{"error": "..."}`。
- 不提供 `/api/generate`、`/api/show`、`/api/version`、`/api/embeddings`。

## 其他
- `GET /health` —— 无需鉴权，返回当前加载的模型。
- 所有响应带 CORS 头，预检（OPTIONS）会放行请求声明的请求头。**浏览器 / 浏览器插件发起的请求（带 `Origin` 头）必须设置 API Key**，未设置时返回 403。
- 生成请求与 App 内对话共用一个模型并**排队执行**，不会互相打断；排队期间请求保持等待（不会返回 429）。
- 客户端断开连接（超时、取消、Ctrl-C）会立即取消该请求——无论正在排队还是正在生成，不会继续占用模型。请求发出后不要半关闭（shutdown write）连接，否则会被视为断开。
- 每个连接只处理一个请求（`Connection: close`）；请求体须带 `Content-Length`（不支持 chunked 上传，返回 411），上限 8 MB（413）；支持 `Expect: 100-continue`。流式响应以关闭连接结束。
- 同时最多处理 8 个连接，超出的连接会等待空位。

## 错误
```json
{"error": {"message": "...", "type": "invalid_request_error"}}
```
| 状态码 | 场景 |
|--------|------|
| 400 | 请求体不是 JSON / 缺少 messages / 含非文本内容 |
| 401 | API Key 错误或缺失 |
| 403 | 未设置 API Key 时来自浏览器（带 `Origin`）的请求 |
| 404 | 未知路径；或指定的模型不存在且当前没有已加载的模型 |
| 405 | 路径存在但方法不对（如 `GET /v1/chat/completions`） |
| 411 / 413 | chunked 请求体 / 请求体超过 8 MB |
| 502 | 推理模型只输出了思考、没有正文（已自动关闭思考重试一次） |
| 503 | 没有可用模型或模型加载失败；或要切换模型但引擎正忙 |
| 500 | 生成失败（`type` 为 `server_error`） |

## 说明（2.3.2）
- 消息内容目前只处理文本；`image_url` 等多模态内容不会传给模型（App 内对话里的 GGUF + mmproj 看图不经过 API）。
