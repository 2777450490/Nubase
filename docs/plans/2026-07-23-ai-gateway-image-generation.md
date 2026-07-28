# Nubase AI 网关生图接口实现说明

## 1. 交付结论

Nubase AI 网关已经加入文本生图能力，对外同时提供两种协议：

- `POST /v1/images/generations`：OpenAI-compatible 原生协议，方便 OpenAI SDK、现有客户端和 `curl` 直接接入。
- `POST /ai/v1/images/generations`：参考 innobase 的统一生图协议，返回稳定的 `outputs` 结构，隔离上游响应差异。

两个接口复用现有 AI 网关的数据面认证、项目租户上下文、模型路由、项目自定义上游、平台统一上游、故障转移、请求追踪和 usage 统计，不新增独立的生图密钥配置。

本次范围为 `text_to_image`。统一协议虽然保留了 `image_to_image` 请求结构，但当前会返回明确的 `400 invalid_request`，避免把未实现能力静默转成错误的文本生图请求。

## 2. 设计目标与边界

### 2.1 目标

1. 项目创建后，可以直接使用项目的 `service_role` key 调用生图接口。
2. 按请求中的 `model` 从项目 `ai_gateway.upstream_configs.supported_models` 选择上游。
3. 项目没有匹配的自定义上游时，沿用现有机制回退到平台统一上游。
4. 上游仍使用数据库中的 `base_url` 和 `auth_token`，调用方不需要也不能传递上游密钥。
5. OpenAI 原生协议保持请求和响应兼容；统一协议负责参数别名归一化和响应归一化。
6. 图片 Base64 和图片 URL 不进入网关持久化请求日志，也不进入故障诊断中的请求体日志。

### 2.2 本次不包含

- 图片编辑、参考图生图和 mask 编辑。
- SSE 流式生图。
- 按图片尺寸、质量和数量计价的销售价格模型。
- 网关代管图片文件或把 Base64 自动上传到 Storage/CDN。

## 3. 接口契约

### 3.1 OpenAI-compatible 接口

请求：

```http
POST /v1/images/generations
Authorization: Bearer <service_role_key>
Content-Type: application/json
```

```json
{
  "model": "gpt-image-2",
  "prompt": "A small red fox reading beside a window",
  "n": 1,
  "size": "1024x1024",
  "output_format": "png"
}
```

响应由上游原样返回，例如：

```json
{
  "created": 1784721600,
  "data": [
    {
      "b64_json": "<base64_image>"
    }
  ],
  "output_format": "png",
  "usage": {
    "input_tokens": 12,
    "output_tokens": 830,
    "total_tokens": 842
  }
}
```

### 3.2 Nubase 统一接口

请求：

```http
POST /ai/v1/images/generations
Authorization: Bearer <service_role_key>
Content-Type: application/json
```

```json
{
  "model": "gpt-image-2",
  "task": "text_to_image",
  "prompt": "A small red fox reading beside a window",
  "config": {
    "number_of_images": 1,
    "image_size": "1024x1024",
    "output_mime_type": "image/png"
  }
}
```

`config` 支持以下归一化：

| 统一参数 | 可接受别名 | 转发参数 |
| --- | --- | --- |
| 图片数量 | `n`, `number_of_images`, `numberOfImages`, `sampleCount` | `n` |
| 图片尺寸 | `size`, `image_size`, `imageSize`, `sampleImageSize` | `size` |
| 输出格式 | `output_format`, `outputFormat`, `output_mime_type`, `outputMimeType`, `mimeType` | `output_format` |
| 其他参数 | `quality`, `background`, `moderation`, `parameters` | 同名或展开转发 |

这些参数也可以放在请求顶层；DTO 会通过扩展字段机制收集到 `config`。

统一响应：

```json
{
  "id": "imggen_0123456789abcdef",
  "model": "gpt-image-2",
  "task": "text_to_image",
  "outputs": [
    {
      "type": "image",
      "mime_type": "image/png",
      "b64_json": "<base64_image>",
      "enhanced_prompt": "A refined prompt"
    }
  ],
  "usage": {
    "input_tokens": 12,
    "output_tokens": 830,
    "total_tokens": 842
  },
  "upstream": {
    "provider": "resolved-upstream-name",
    "action": "images/generations"
  }
}
```

上游返回图片 URL 时，统一响应使用 `outputs[].uri`；返回 Base64 时使用 `outputs[].b64_json`。

## 4. 请求链路

```text
Client
  -> GatewayApiKeyAuthFilter
  -> OpenAINativeController or ImageGenerationController
  -> ImageGenerationService (unified protocol only)
  -> OpenAINativeApiService
  -> project upstream selected by supported_models
  -> platform upstream fallback
  -> POST <base_url>/v1/images/generations
  -> usage tracking and redacted request logging
```

### 4.1 认证

接口位于 `/v1/` 和 `/ai/` 数据面前缀下，因此自动经过 `GatewayApiKeyAuthFilter`。支持：

- `Authorization: Bearer <service_role_key>`
- `x-api-key: <service_role_key>`
- 现有随机网关 key

`service_role` key 会解析项目 `app_code`，建立 `MultiTenancyContext`，再从项目租户库校验对应的 key 记录。

### 4.2 模型路由

生图路由不根据 `chat_completions_path` 拼接地址，而是固定调用：

```text
<normalized_base_url>/v1/images/generations
```

选择顺序沿用现有 OpenAI native 路由：

1. 指定 `x-upstream` 时，读取该项目数据库中的上游，并校验其 `supported_models` 是否包含请求模型。
2. 未指定时，优先选择项目 `ai_gateway.upstream_configs` 中声明支持该模型的活动上游。
3. 项目没有可用匹配项时，查询 `public.ai_gateway_platform_upstreams`。
4. 路由成功后，把上游名称写入 `GatewayRoutingContext`，供 usage 平台账本和统一响应使用。

模型可以写成 `gpt-image-2` 或 `openai/gpt-image-2`。带前缀形式用于客户端侧显式表达渠道，转发前会改写成上游模型名 `gpt-image-2`。

### 4.3 超时和故障转移

- 生图调用使用单请求 `300000ms` 超时，不修改普通聊天接口的超时。
- 单上游沿用现有两次尝试策略。
- 项目自定义上游失败后，沿用 `supported_models` 的项目内候选故障转移。

## 5. usage 与计费边界

生图请求会进入现有 `ApiUsageTrackingService`：

- 上游返回 `usage.input_tokens`、`usage.output_tokens` 或兼容字段时，写入项目 usage 日志、每日统计和平台 usage 账本。
- 上游没有返回 usage 时，仍记录请求状态和延迟，token 数为零。
- `api_key` 只按 hash 反查，持久化日志继续保存脱敏前缀。

当前中央销售计费仍是“每百万 input/output token”价格模型，无法正确表达图片数量、尺寸、质量对应的价格。为避免错误预占和错误扣费，`/v1/images/generations` 与 `/ai/v1/images/generations` 本次不进入 `BillingAdmissionFilter` 的余额预占流程，也没有给 `gpt-image-*` 写入伪造 token 单价。

因此当前准确表述是：**已记录上游返回的 token usage，但尚未进行图片销售额扣减**。后续应增加独立的 image price dimension，例如：

```text
model + operation + size + quality + output_format + effective_from
```

再把图片数量作为计费 quantity，接入中央 reservation/settlement。

## 6. 日志与数据安全

生图载荷通常远大于普通 JSON，也可能包含用户图片，因此实现了端点级递归脱敏：

- Base64 字段替换为 `<redacted:N chars>`。
- URL/URI 字段替换为 `<redacted-url>`。
- 同时覆盖成功日志、非 2xx 日志、网络异常日志和故障转移耗尽日志。
- 返回给客户端的真实响应不修改，只有持久化日志副本被脱敏。

覆盖的常见字段包括 `b64_json`、`base64`、`image_base64`、`imageBytes`、`bytesBase64Encoded`、参考图中的 `data`，以及 `url`、`uri`、`image_url`、`fileUri`、`gcsUri`。

## 7. 错误语义

| 场景 | HTTP 状态 | 错误类型 |
| --- | ---: | --- |
| JSON 非法、缺少 `model` 或 `prompt` | 400 | `invalid_request_error` / `invalid_request` |
| 请求流式生图 | 400 | `invalid_request_error` |
| 统一协议请求 `image_to_image` | 400 | `invalid_request` |
| 没有匹配模型的上游 | 502 | `upstream_error` |
| 上游非 2xx 或网络失败 | 502 | `upstream_error` |
| 上游成功但统一协议中没有任何图片 | 502 | `upstream_error` |

## 8. 代码变更清单

- `ImageGenerationRequest`：统一请求 DTO、参数扩展和校验。
- `ImageGenerationResponse`：统一响应 DTO。
- `ImageGenerationService`：参数归一化、原生请求构建、统一响应转换。
- `ImageGenerationController`：暴露 `/ai/v1/images/generations`。
- `OpenAINativeController`：暴露 `/v1/images/generations`。
- `OpenAINativeApiService`：模型路由、生图固定路径、300 秒超时、转发、usage 与日志脱敏。
- `application.yml`：增加默认模型配置 `NUBASE_AI_GATEWAY_IMAGE_DEFAULT_MODEL`。
- 三组测试：DTO 归一化、统一协议转换、MockWebServer 真实转发与日志脱敏。

## 9. 验证命令

### 9.1 自动化测试

```bash
mvn -q -Dtest=ImageGenerationRequestTest,ImageGenerationServiceTest,OpenAINativeImageGenerationTest,OpenAINativeApiServiceRoutingTest test
```

本次实现完成后的实际结果：

- 生图相关与路由测试：11 个通过，0 个失败，0 个错误。
- 完整 `mvn -q test`：907 个测试，891 个执行通过，16 个跳过，0 个失败，0 个错误。
- 本次涉及 Java 文件的 scoped Spotless check：通过。
- 仓库全量 Spotless check 仍会报告其他既有工作区文件的未使用 import；本次没有对这些无关文件执行自动改写。

### 9.2 OpenAI-compatible `curl`

```bash
curl -sS --max-time 320 \
  -X POST 'http://127.0.0.1:9999/v1/images/generations' \
  -H 'Authorization: Bearer <service_role_key>' \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "gpt-image-2",
    "prompt": "A small red fox reading beside a window, cinematic light",
    "n": 1,
    "size": "1024x1024",
    "output_format": "png"
  }' \
  -o /tmp/nubase-image-response.json
```

提取第一张 Base64 图片：

```bash
jq -r '.data[0].b64_json' /tmp/nubase-image-response.json \
  | base64 --decode \
  > /tmp/nubase-generated.png
```

### 9.3 Nubase 统一协议 `curl`

```bash
curl -sS --max-time 320 \
  -X POST 'http://127.0.0.1:9999/ai/v1/images/generations' \
  -H 'Authorization: Bearer <service_role_key>' \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "gpt-image-2",
    "task": "text_to_image",
    "prompt": "A small red fox reading beside a window, cinematic light",
    "config": {
      "number_of_images": 1,
      "image_size": "1024x1024",
      "output_mime_type": "image/png"
    }
  }' \
  -o /tmp/nubase-unified-image-response.json
```

提取统一响应图片：

```bash
jq -r '.outputs[0].b64_json' /tmp/nubase-unified-image-response.json \
  | base64 --decode \
  > /tmp/nubase-unified-generated.png
```

如果上游返回 URL 而非 Base64，可以查看：

```bash
jq -r '.outputs[0].uri // .data[0].url' /tmp/nubase-unified-image-response.json
```

## 10. 上线前检查

1. 确认 `public.ai_gateway_platform_upstreams` 或项目 `ai_gateway.upstream_configs` 至少有一个活动上游的 `supported_models` 包含 `gpt-image-2`。
2. 确认该上游的 `base_url` 加 `/v1/images/generations` 是真实可调用地址。
3. 重启 Nubase，使新 controller 和 `NUBASE_AI_GATEWAY_IMAGE_DEFAULT_MODEL` 生效。
4. 用项目 `service_role` key 执行最小 `curl`。
5. 检查响应头 `x-nubase-request-id`，再按该 ID 核对 usage、上游名称和延迟。
6. 检查 `logs/ai-gateway-logs`，确认图片字段只出现脱敏占位符，不出现完整 Base64 或 URL。
7. 在正式开放销售前，补齐图片维度的价格版本与余额预占/结算逻辑。
