# Nubase AI 网关 Seedance 视频生成接口实现说明

## 1. 交付结论

Nubase AI 网关已经加入异步视频生成接口，协议参考 innobase 的 ZenMux Vertex AI 长任务实现，对外提供：

- `POST /ai/v1/videos/generations`：创建视频生成任务。
- `POST /ai/v1/videos:generate`：创建任务的兼容别名。
- `POST /ai/v1/videos/operations:fetch`：轮询任务状态并取得最终视频。

模型被服务端固定为：

```text
bytedance/doubao-seedance-2.0
```

客户端可以省略 `model`，也可以使用 `seedance`、`seeddance`、`doubao-seedance-2.0` 或完整模型名；服务端最终都会归一化成完整模型名。传入其他模型会直接返回 `400 invalid_request`，不会根据客户端输入拼接任意上游资源路径。

接口复用现有项目 `service_role` key、随机网关 key、租户上下文、request ID、usage 统计和请求日志系统。视频上游直接读取 Spring 配置中的 `zenmux.*`，生产环境由 `ZENMUX_API_KEY` 等环境变量注入，不读取数据库上游配置，也不会路由到项目自定义上游或其他 provider。

## 2. 为什么使用异步接口

Seedance 的 Vertex AI-compatible 接口是长任务协议：

1. 调用 `predictLongRunning` 创建任务。
2. 上游返回 `operation.name`。
3. 客户端定期调用 `fetchPredictOperation`。
4. `done=true` 后读取视频 URI 或 Base64。

网关不会在第一个 HTTP 请求中持续等待视频完成。这样可以避免单请求占用连接数分钟，也能让客户端明确区分排队、生成、完成和失败状态。

创建请求是非幂等操作。网关不会在网络超时后自动重试创建任务，否则可能在上游已经成功创建任务但响应丢失时生成两个视频并产生两次成本。轮询请求由客户端按需要重试。

## 3. 创建视频任务

### 3.1 请求

```http
POST /ai/v1/videos/generations
Authorization: Bearer <service_role_key>
Content-Type: application/json
```

最小文本生成视频请求：

```json
{
  "prompt": "A paper boat crossing a quiet lake at sunrise",
  "config": {
    "duration_seconds": 6,
    "resolution": "720p",
    "aspect_ratio": "16:9",
    "generate_audio": true
  }
}
```

首帧图片生成视频请求：

```json
{
  "model": "seedance",
  "prompt": "The camera slowly moves toward the mountain",
  "image": {
    "data": "<base64_image>",
    "mime_type": "image/png"
  },
  "config": {
    "duration_seconds": 6,
    "resolution": "720p",
    "aspect_ratio": "smart"
  }
}
```

`image`、`video` 和 `last_frame` 都接受：

- `data` 或 Base64 别名，例如 `base64`、`imageBytes`、`videoBytes`、`bytesBase64Encoded`。
- `uri` 或 URI 别名，例如 `gcsUri`、`fileUri`。
- `mime_type` 或 `mimeType`。

也支持 innobase 同形状的 `reference_images`、原始 `instances` 和原始 `parameters`。即使使用原始实例，目标模型资源仍由服务端固定，客户端不能改变模型路径。

### 3.2 参数归一化

| 客户端参数 | 上游参数 |
| --- | --- |
| `number_of_videos`, `numberOfVideos` | `sampleCount` |
| `output_gcs_uri`, `outputGcsUri` | `storageUri` |
| `duration_seconds` | `durationSeconds` |
| `aspect_ratio` | `aspectRatio` |
| `person_generation` | `personGeneration` |
| `negative_prompt` | `negativePrompt` |
| `enhance_prompt` | `enhancePrompt` |
| `generate_audio` | `generateAudio` |
| `compression_quality` | `compressionQuality` |
| `resize_mode` | `resizeMode` |

Seedance 约束在请求进入上游前校验：

- `durationSeconds`：4 到 15 秒。
- `resolution`：`480p` 或 `720p`。
- `aspectRatio`：`21:9`、`16:9`、`4:3`、`1:1`、`3:4`、`9:16` 或 `smart`。
- `smart` 会按 innobase 行为从请求参数中省略，由 Seedance 自动选择比例。
- `webhook_config` 当前不支持，会返回 400。

### 3.3 上游请求

当数据库 `base_url` 为 `https://zenmux.ai/api` 时，网关请求：

```http
POST https://zenmux.ai/api/vertex-ai/v1/publishers/bytedance/models/doubao-seedance-2.0:predictLongRunning
```

当 `base_url` 已经以 `/vertex-ai` 结尾时，不会重复追加路径。

### 3.4 创建响应

```json
{
  "name": "publishers/bytedance/models/doubao-seedance-2.0/operations/op-1",
  "done": false,
  "model": "bytedance/doubao-seedance-2.0",
  "upstream": "zenmux"
}
```

`model` 和 `upstream` 由网关补充。轮询时可以把 `upstream` 原样传回；服务端会校验其必须为 `zenmux`。为兼容早期接口响应，轮询也接受旧值 `zenmux-openai-api`。

## 4. 轮询视频任务

### 4.1 请求

```http
POST /ai/v1/videos/operations:fetch
Authorization: Bearer <service_role_key>
Content-Type: application/json
```

```json
{
  "operation_name": "publishers/bytedance/models/doubao-seedance-2.0/operations/op-1",
  "upstream": "zenmux"
}
```

也接受以下 operation 字段形状：

```json
{
  "operation": {
    "name": "publishers/bytedance/models/doubao-seedance-2.0/operations/op-1"
  }
}
```

`operation_name` 必须属于固定的 Seedance 模型资源。其他模型的 operation、HTTP URL、包含查询参数或路径穿越片段的值会在本地拒绝，不会用于拼接上游 URL。

### 4.2 生成中响应

```json
{
  "name": "publishers/bytedance/models/doubao-seedance-2.0/operations/op-1",
  "done": false,
  "metadata": {},
  "model": "bytedance/doubao-seedance-2.0",
  "upstream": "zenmux"
}
```

### 4.3 完成响应

```json
{
  "name": "publishers/bytedance/models/doubao-seedance-2.0/operations/op-1",
  "done": true,
  "response": {
    "generatedVideos": [
      {
        "uri": "https://example.com/generated.mp4",
        "videoBase64": "<base64_video>",
        "mimeType": "video/mp4"
      }
    ]
  },
  "model": "bytedance/doubao-seedance-2.0",
  "upstream": "zenmux"
}
```

返回给客户端的视频 URI/Base64 保持完整；只有写入网关日志的副本会脱敏。

## 5. 认证与路由

接口位于 `/ai/` 数据面前缀下，自动经过 `GatewayApiKeyAuthFilter`。支持：

- `Authorization: Bearer <service_role_key>`
- `x-api-key: <service_role_key>`
- 已有随机网关 key

路由固定为：

1. API key 读取 `zenmux.api-key`，对应环境变量 `ZENMUX_API_KEY`。
2. Vertex AI proxy 根地址读取 `zenmux.base-url`，对应环境变量 `ZENMUX_BASE_URL`，默认值为 `https://zenmux.ai/api/vertex-ai`。
3. 视频请求超时读取 `zenmux.video-timeout-ms`，对应环境变量 `ZENMUX_VIDEO_TIMEOUT_MS`，默认值为 300 秒。
4. `x-upstream` 或轮询 body 中的 `upstream` 一旦指定，只允许 `zenmux`；为兼容早期返回值，也接受 `zenmux-openai-api`。
5. 视频运行时不读取 `public.ai_gateway_platform_upstreams`，数据库中也不需要 ZenMux 上游行。

`src/main/resources/application.yml` 与 `application-dev.yml` 只保留环境变量占位符，真实 token 只写入本地或服务器上的 `nubase.env`，不能提交到 Git。

## 6. usage、成本和中央计费边界

创建和每次轮询都会写入：

- 项目 `api_usage_logs`。
- 项目每日 usage 统计。
- 平台中央 usage 账本。
- 上游名称、来源、状态码、延迟和 request ID。

如果上游返回 `usageMetadata` 或兼容 `usage`，网关会提取 token；普通 Seedance 长任务响应通常不返回语言模型 token usage，因此一般记录为 0。

当前中央销售计费模型只支持每百万 input/output token，无法准确表达视频的时长、分辨率、音频、数量等价格维度。本次视频接口不进入 `BillingAdmissionFilter` 的余额预占，也不为 Seedance 写入伪造 token 单价。

因此当前准确状态是：**请求与上游使用记录已经落账，但视频销售金额尚未扣减**。正式销售前应增加类似以下价格维度：

```text
model + operation + duration_seconds + resolution + generate_audio + effective_from
```

## 7. 日志安全

视频和首尾帧可能非常大，也可能包含用户敏感内容。实现会在持久化日志前递归脱敏：

- Base64 字段替换为 `<redacted:N chars>`。
- `uri`、`gcsUri`、`fileUri`、`url`、`download_url` 替换为 `<redacted-url>`。
- 覆盖创建请求、轮询响应、上游非 2xx 和网络异常日志。
- 日志只保存脱敏副本，不修改返回给调用方的真实响应。
- `Authorization`、`x-api-key`、`x-upstream` 不转发给上游；上游认证头只由数据库中的 token 构建。

## 8. 错误语义

| 场景 | HTTP 状态 | 错误类型 |
| --- | ---: | --- |
| 请求为空、缺少输入、参数越界 | 400 | `invalid_request` |
| 客户端传入非 Seedance 模型 | 400 | `invalid_request` |
| operation 不属于固定模型 | 400 | `invalid_request` |
| 指定非 ZenMux 上游或 ZenMux 上游不支持固定模型 | 400 | `invalid_request` |
| ZenMux 平台上游未配置 | 502 | `upstream_error` |
| 上游非 2xx、超时或响应非法 | 502 | `upstream_error` |

## 9. 最小 `curl` 验证

### 9.1 创建任务

```bash
curl -sS --max-time 320 \
  -X POST 'http://127.0.0.1:9999/ai/v1/videos/generations' \
  -H 'Authorization: Bearer <service_role_key>' \
  -H 'Content-Type: application/json' \
  -d '{
    "prompt": "A paper boat crossing a quiet lake at sunrise",
    "config": {
      "duration_seconds": 6,
      "resolution": "720p",
      "aspect_ratio": "16:9",
      "generate_audio": true
    }
  }' \
  -o /tmp/nubase-video-operation.json
```

查看 operation：

```bash
jq . /tmp/nubase-video-operation.json
```

### 9.2 轮询任务

```bash
OPERATION_NAME=$(jq -r '.name' /tmp/nubase-video-operation.json)
UPSTREAM_NAME=$(jq -r '.upstream' /tmp/nubase-video-operation.json)

jq -n \
  --arg operation_name "$OPERATION_NAME" \
  --arg upstream "$UPSTREAM_NAME" \
  '{operation_name: $operation_name, upstream: $upstream}' \
  > /tmp/nubase-video-fetch.json

curl -sS --max-time 320 \
  -X POST 'http://127.0.0.1:9999/ai/v1/videos/operations:fetch' \
  -H 'Authorization: Bearer <service_role_key>' \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/nubase-video-fetch.json \
  -o /tmp/nubase-video-result.json
```

查看状态和视频地址：

```bash
jq '{done, error, videos: .response.generatedVideos}' /tmp/nubase-video-result.json
```

如果上游返回 Base64：

```bash
jq -r '.response.generatedVideos[0].videoBase64' /tmp/nubase-video-result.json \
  | base64 --decode \
  > /tmp/nubase-generated.mp4
```

如果上游返回 URI：

```bash
jq -r '.response.generatedVideos[0].uri' /tmp/nubase-video-result.json
```

建议每隔约 10 秒轮询一次，不要使用无间隔循环。

## 10. 代码变更清单

- `VideoGenerationRequest`：固定模型、媒体输入、统一参数和原始 Vertex 参数 DTO。
- `VideoOperationFetchRequest`：operation 和固定 ZenMux 上游 DTO。
- `VideoGenerationService`：请求构建、参数校验、固定 ZenMux 平台路由、Vertex URL、调用、usage 和脱敏。
- `VideoGenerationController`：创建和轮询两个数据面接口。
- `V13__enable_seeddance_video_upstream.sql`：给现有 ZenMux 平台上游补充固定模型能力。
- `VideoGenerationRequestTest`：固定模型和请求输入校验。
- `VideoGenerationServiceTest`：真实 HTTP 请求形状、固定 ZenMux 路由、非 ZenMux 拒绝、轮询和日志脱敏。

## 11. 自动化验证

针对性测试：

```bash
mvn -q -Dtest=VideoGenerationRequestTest,VideoGenerationServiceTest test
```

本次实现完成后的实际结果：

- 视频相关测试：10 个通过，0 个失败，0 个错误。
- 本次涉及 Java 文件的 scoped Spotless check：通过。
- 完整 `mvn -q test`：923 个测试，907 个执行通过，16 个跳过，0 个失败，0 个错误。

## 12. 上线检查

1. 确认服务器 `/root/nubase/nubase.env` 中存在非空 `ZENMUX_API_KEY`，文件权限为 `600`。
2. 确认应用使用 `prod` profile，运行进程能读取 `ZENMUX_API_KEY`。
3. 确认 `ZENMUX_BASE_URL` 为 `https://zenmux.ai/api/vertex-ai` 或使用默认值。
4. 使用项目 `service_role` key 创建任务。
5. 保存响应中的 `name` 和 `upstream`，轮询直至 `done=true`。
6. 按 `x-nubase-request-id` 检查 usage 和请求日志。
7. 确认日志中不出现完整图片 Base64、视频 Base64 或视频下载 URL。
8. 正式收费前补齐视频维度价格和中央 reservation/settlement。
