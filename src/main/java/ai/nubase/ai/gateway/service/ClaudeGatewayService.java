package ai.nubase.ai.gateway.service;

import ai.nubase.ai.gateway.billing.GatewayRequestContext;
import ai.nubase.ai.gateway.dto.ApiUsageRecord;
import ai.nubase.ai.gateway.dto.TokenUsage;
import ai.nubase.ai.gateway.platform.GatewayRoutingContext;
import ai.nubase.ai.gateway.platform.PlatformUpstream;
import ai.nubase.ai.gateway.platform.PlatformUpstreamService;
import ai.nubase.ai.gateway.util.SensitiveHeaderSanitizer;
import ai.nubase.common.config.AnthropicConfig;
import ai.nubase.common.util.ApiKeyLogMask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Claude API 网关服务
 * 负责代理转发请求到 Claude API
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeGatewayService {

    private static final Logger claudeResponseLog = LoggerFactory.getLogger("ClaudeResponseLogger");
    private static final Set<String> LOGGABLE_MESSAGE_ROLES = Set.of("user", "assistant");
    private static final Set<String> LOGGABLE_CONTENT_BLOCK_TYPES = Set.of(
            "text",
            "tool_result",
            "tool_use",
            "image",
            "document",
            "thinking",
            "redacted_thinking"
    );
    private static final Set<String> LOGGABLE_SSE_EVENT_TYPES = Set.of(
            "message_start",
            "content_block_start",
            "content_block_delta",
            "content_block_stop",
            "message_delta",
            "message_stop",
            "ping",
            "error"
    );

    private final AnthropicConfig anthropicConfig;
    private final ObjectMapper objectMapper;
    private final ApiUsageTrackingService usageTrackingService;
    private final ApiRequestLogService requestLogService;
    private final UpstreamConfigService upstreamConfigService;
    private final PlatformUpstreamService platformUpstreamService;
    private final ContextPruneService contextPruneService;
    private final AiGatewayStreamingHttpClientProvider streamingHttpClientProvider;
    private OkHttpClient httpClient;

    /**
     * 初始化 HTTP 客户端，使用配置的超时时间
     */
    private OkHttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(anthropicConfig.getTimeout(), TimeUnit.MILLISECONDS)
                    .readTimeout(anthropicConfig.getTimeout(), TimeUnit.MILLISECONDS)
                    .writeTimeout(anthropicConfig.getTimeout(), TimeUnit.MILLISECONDS)
                    .build();
        }
        return httpClient;
    }

    /**
     * 根据上游名称获取配置
     * 如果未指定上游名称，使用默认上游
     *
     * @param upstreamName 上游名称（可选）
     * @return 上游配置信息
     */
    private UpstreamInfo getUpstreamInfo(String upstreamName, ai.nubase.common.enums.ApiProvider provider) {
        // 1) 优先使用项目自定义上游（各租户库 ai_gateway.upstream_configs）。
        try {
            ai.nubase.ai.gateway.entity.UpstreamConfig config;
            if (upstreamName != null && !upstreamName.isEmpty()) {
                config = upstreamConfigService.getByName(upstreamName);
                log.info("使用项目自定义指定上游: {}", upstreamName);
            } else {
                config = upstreamConfigService.getDefaultByProvider(provider);
                log.info("[upstream_config]使用项目自定义默认上游: {} (provider={})", config.getName(), provider);
            }
            upstreamConfigService.updateLastUsedAt(config.getName());
            GatewayRoutingContext.set(GatewayRoutingContext.Source.CUSTOM, config.getName());
            return new UpstreamInfo(
                    config.getName(),
                    config.getBaseUrl(),
                    config.getAuthToken(),
                    config.getTimeoutMs(),
                    config.getMaxInputTokens());
        } catch (Exception tenantMiss) {
            log.info("项目无可用自定义上游，回退平台统一配置: exception={}",
                    exceptionType(tenantMiss));
        }

        // 2) 回退平台统一配置（元数据库 public.ai_gateway_platform_upstreams）。
        try {
            PlatformUpstream p = null;
            if (upstreamName != null && !upstreamName.isEmpty()) {
                p = platformUpstreamService.getByName(upstreamName)
                        .orElseGet(() -> platformUpstreamService.getDefaultByProvider(provider).orElse(null));
            } else {
                p = platformUpstreamService.getDefaultByProvider(provider).orElse(null);
            }
            if (p != null) {
                log.info("[upstream_config]使用平台统一上游: {} (provider={})", p.getName(), provider);
                GatewayRoutingContext.set(GatewayRoutingContext.Source.PLATFORM, p.getName());
                return new UpstreamInfo(
                        p.getName(),
                        p.getBaseUrl(),
                        p.getAuthToken(),
                        p.getTimeoutMs() == null ? anthropicConfig.getTimeout() : p.getTimeoutMs(),
                        p.getMaxInputTokens());
            }
        } catch (Exception platformMiss) {
            log.warn("平台统一上游解析失败: exception={}", exceptionType(platformMiss));
        }

        // 3) 最后回退到环境默认配置（历史行为，视为平台来源）。
        log.warn("无可用项目/平台上游，回退环境默认配置");
        GatewayRoutingContext.set(GatewayRoutingContext.Source.PLATFORM, "config-file");
        return new UpstreamInfo(
                "config-file",
                anthropicConfig.getBaseUrl(),
                anthropicConfig.getAuthToken(),
                anthropicConfig.getTimeout());
    }

    /**
     * 上游配置信息
     */
    private static class UpstreamInfo {
        final String name;
        final String baseUrl;
        final String authToken;
        final int timeout;
        final Integer maxInputTokens;

        UpstreamInfo(String name, String baseUrl, String authToken, int timeout) {
            this(name, baseUrl, authToken, timeout, null);
        }

        UpstreamInfo(String name, String baseUrl, String authToken, int timeout, Integer maxInputTokens) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.authToken = authToken;
            this.timeout = timeout;
            this.maxInputTokens = maxInputTokens;
        }
    }

    /**
     * 转发 GET 请求到 Claude API
     *
     * @param path         API 端点路径 (例如: "/v1/models")
     * @param headers      附加的请求头
     * @param clientApiKey 客户端 API Key，用于使用量统计
     * @return Claude API 的响应
     */
    public String forwardGetRequest(String path, Map<String, String> headers, String clientApiKey) throws IOException {
        String url = anthropicConfig.getBaseUrl() + path;
        long startTime = System.currentTimeMillis();
        String requestId = GatewayRequestContext.currentOrNewString();

        log.info("📤 [{}] GET {}", requestId, path);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .get()
                .addHeader("x-api-key", anthropicConfig.getAuthToken());

        // 添加自定义请求头
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (!key.equalsIgnoreCase("Authorization") && !key.equalsIgnoreCase("x-api-key")) {
                    requestBuilder.addHeader(key, value);
                }
            });
        }

        Request request = requestBuilder.build();

        // Retry logic: attempt up to 2 times with 3000ms delay
        int maxAttempts = 2;
        int attempt = 0;
        IOException lastException = null;

        while (attempt < maxAttempts) {
            attempt++;
            try (Response response = getHttpClient().newCall(request).execute()) {
                long duration = System.currentTimeMillis() - startTime;
                String responseBody = response.body() != null ? response.body().string() : "{}";

                claudeResponseLog.info(
                        "claude.get.response requestId={} status={} durationMs={} bodyBytes={}",
                        requestId, response.code(), duration, bodyBytes(responseBody));

                if (!response.isSuccessful()) {
                    if (attempt < maxAttempts) {
                        log.warn("⚠️ [{}] Attempt {}/{} failed with status {}, retrying after 3000ms...",
                                requestId, attempt, maxAttempts, response.code());
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Retry interrupted", ie);
                        }
                        continue; // Retry
                    } else {
                        // Last attempt failed
                        String failureSummary = safeFailureSummary(response.code(), responseBody, null);
                        log.error("claude.get.failed requestId={} attempts={} {}",
                                requestId, maxAttempts, failureSummary);

                        trackApiUsage(clientApiKey, requestId, null, path, "GET",
                                response.code(), responseBody, duration, headers, failureSummary);

                        requestLogService.logRequest(requestId, ApiKeyLogMask.mask(clientApiKey), "GET", path,
                                null, headers, null, response.code(), responseBody, duration,
                                TokenUsage.empty(), failureSummary);

                        throw new IOException("Claude upstream request failed: " + failureSummary);
                    }
                }

                // Success
                trackApiUsage(clientApiKey, requestId, null, path, "GET",
                        response.code(), responseBody, duration, headers, null);

                requestLogService.logRequest(requestId, ApiKeyLogMask.mask(clientApiKey), "GET", path,
                        null, headers, null, response.code(), responseBody, duration,
                        TokenUsage.empty(), null);

                return responseBody;

            } catch (IOException e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    log.warn("⚠️ [{}] Attempt {}/{} threw exception={}, retrying after 3000ms...",
                            requestId, attempt, maxAttempts, exceptionType(e));
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Retry interrupted", ie);
                    }
                } else {
                    // Last attempt failed
                    long duration = System.currentTimeMillis() - startTime;
                    String failureSummary = safeFailureSummary(500, null, e);
                    log.error("❌ [{}] All {} attempts failed: {} - 耗时: {} ms",
                            requestId, maxAttempts, failureSummary, duration);

                    trackApiUsage(clientApiKey, requestId, null, path, "GET",
                            500, null, duration, headers, failureSummary);

                    requestLogService.logRequest(requestId, ApiKeyLogMask.mask(clientApiKey), "GET", path,
                            null, headers, null, 500, null, duration,
                            TokenUsage.empty(), failureSummary);

                    throw e;
                }
            }
        }

        // Should never reach here, but required for compilation
        throw lastException != null ? lastException
                : new IOException("Unexpected error: retry loop exited without exception");
    }

    /**
     * 转发非流式请求到 Claude API
     * 支持上游故障转移：如果主上游在所有重试后仍然失败，
     * 自动尝试同 provider 类型的其他活跃上游
     *
     * @param path         API 端点路径 (例如: "/v1/messages")
     * @param requestBody  JSON 格式的请求体
     * @param headers      附加的请求头
     * @param clientApiKey 客户端 API Key，用于使用量统计
     * @param upstreamName 上游配置名称（可选，为 null 时使用默认上游）
     * @return Claude API 的响应
     */
    public String forwardRequest(String path, String requestBody, Map<String, String> headers,
                                 String clientApiKey, String upstreamName, ai.nubase.common.enums.ApiProvider provider) throws IOException {
        // 获取上游配置
        // 如果未指定 provider，默认使用 CLAUDE（向后兼容）
        if (provider == null) {
            provider = ai.nubase.common.enums.ApiProvider.CLAUDE;
        }
        UpstreamInfo upstream = getUpstreamInfo(upstreamName, provider);

        String nonStreamRequestId = GatewayRequestContext.currentOrNewString();
        ContextPruneService.PruneResult prune = contextPruneService.pruneIfNeeded(
                requestBody, upstream.maxInputTokens, nonStreamRequestId);
        if (prune.pruned) {
            log.info("context_prune.applied requestId={} upstream={} {}",
                    nonStreamRequestId, upstream.name, prune.summary);
            requestBody = prune.body;
        }

        // 从请求中提取模型名称
        String model = usageTrackingService.extractModelFromRequest(requestBody);

        // 从请求头中提取 x-turn-id 并添加到请求体的 metadata.user_id
        String turnId = headers != null ? headers.get("x-turn-id") : null;
        if (turnId != null && !turnId.isEmpty()) {
            try {
                JsonNode jsonNode = objectMapper.readTree(requestBody);
                com.fasterxml.jackson.databind.node.ObjectNode objectNode = (com.fasterxml.jackson.databind.node.ObjectNode) jsonNode;

                com.fasterxml.jackson.databind.node.ObjectNode metadata = objectNode.has("metadata")
                        ? (com.fasterxml.jackson.databind.node.ObjectNode) objectNode.get("metadata")
                        : objectNode.putObject("metadata");
                metadata.put("user_id", turnId);

                requestBody = objectMapper.writeValueAsString(objectNode);
                log.info("Added x-turn-id to metadata.user_id for requestId={}", nonStreamRequestId);
            } catch (Exception e) {
                log.warn("Failed to add x-turn-id to request metadata: exception={}", exceptionType(e));
            }
        }

        // 首先尝试主上游
        try {
            return executeNonStreamingRequest(path, requestBody, headers, clientApiKey, upstream, model);
        } catch (IOException primaryException) {
            log.warn("⚠️ 主上游 '{}' 请求失败（provider={}），尝试故障转移...",
                    upstream.name, provider);

            // 尝试同 provider 类型的其他上游
            List<String> triedUpstreams = new ArrayList<>();
            triedUpstreams.add(upstream.name);

            List<ai.nubase.ai.gateway.entity.UpstreamConfig> failoverCandidates = upstreamConfigService
                    .getFailoverUpstreams(provider, triedUpstreams);

            for (ai.nubase.ai.gateway.entity.UpstreamConfig fallback : failoverCandidates) {
                try {
                    UpstreamInfo fallbackUpstream = new UpstreamInfo(
                            fallback.getName(), fallback.getBaseUrl(),
                            fallback.getAuthToken(), fallback.getTimeoutMs(),
                            fallback.getMaxInputTokens());

                    log.info("[upstream_error_transfer]：尝试上游 '{}' (priority={}, provider={})",
                            fallback.getName(), fallback.getPriority(), provider);

                    upstreamConfigService.updateLastUsedAt(fallback.getName());

                    String result = executeNonStreamingRequest(
                            path, requestBody, headers, clientApiKey, fallbackUpstream, model);

                    log.info("[upstream_error_transfer]✅ 故障转移成功，使用上游 '{}'", fallback.getName());
                    return result;
                } catch (IOException failoverException) {
                    log.warn("⚠️ 故障转移上游 '{}' 也失败了: exception={}",
                            fallback.getName(), exceptionType(failoverException));
                    triedUpstreams.add(fallback.getName());
                }
            }

            // 所有上游均已耗尽
            log.error("❌ provider {} 所有上游均已耗尽，已尝试: {}", provider, triedUpstreams);
            throw primaryException;
        }
    }

    /**
     * 向指定上游执行非流式请求（含重试逻辑）
     */
    private String executeNonStreamingRequest(String path, String requestBody, Map<String, String> headers,
                                              String clientApiKey, UpstreamInfo upstream, String model) throws IOException {
        String url = upstream.baseUrl + path;
        long startTime = System.currentTimeMillis();
        String requestId = GatewayRequestContext.currentOrNewString();

        log.info("agent_log [{}] POST {} - 模型: {}, 上游: {}",
                requestId, path, model, upstream.name);
        log.info("上游配置: name={}, timeout={}ms, maxInputTokens={}",
                upstream.name, upstream.timeout, upstream.maxInputTokens);
        log.info("agent_log.req_stats requestId={} {}", requestId, summarizeRequestBody(requestBody));

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                .addHeader("x-api-key", upstream.authToken)
                .addHeader("Content-Type", "application/json");

        // 添加自定义请求头
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (!key.equalsIgnoreCase("Authorization") &&
                        !key.equalsIgnoreCase("x-api-key") &&
                        !key.equalsIgnoreCase("x-upstream")) {
                    requestBuilder.addHeader(key, value);
                }
            });
        }

        Request request = requestBuilder.build();

        // Retry logic: attempt up to 2 times with 3000ms delay
        int maxAttempts = 2;
        int attempt = 0;
        IOException lastException = null;

        while (attempt < maxAttempts) {
            attempt++;
            try (Response response = getHttpClient().newCall(request).execute()) {
                long duration = System.currentTimeMillis() - startTime;
                String responseBody = response.body() != null ? response.body().string() : "{}";

                // 提取 token 使用量用于日志记录
                TokenUsage tokenUsage = TokenUsage.empty();
                if (responseBody != null) {
                    tokenUsage = usageTrackingService.extractTokenUsage(responseBody);
                }

                claudeResponseLog.info("=======================================");
                claudeResponseLog.info("[api_response] [{}] 响应: {} - 耗时: {} ms, Tokens: {}/{}/{}, 上游: {}",
                        requestId, response.code(), duration,
                        tokenUsage.getInputTokens(), tokenUsage.getOutputTokens(), tokenUsage.getTotalTokens(),
                        upstream.name);
                logClaudeUsageDiagnostics("non_stream_response", requestId, path, model, upstream.name,
                        tokenUsage);
                claudeResponseLog.info("claude.response_body_stats requestId={} bodyBytes={}",
                        requestId, bodyBytes(responseBody));
                claudeResponseLog.info("========================================");

                if (!response.isSuccessful()) {
                    if (attempt < maxAttempts) {
                        log.warn("⚠️ [{}] Attempt {}/{} failed with status {}, retrying after 3000ms...",
                                requestId, attempt, maxAttempts, response.code());
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Retry interrupted", ie);
                        }
                        continue; // Retry
                    } else {
                        // Last attempt failed, track and throw
                        String failureSummary = safeFailureSummary(response.code(), responseBody, null);
                        log.error("❌ [{}] All {} attempts failed: {}",
                                requestId, maxAttempts, failureSummary);

                        trackApiUsage(clientApiKey, requestId, model, path, "POST",
                                response.code(), responseBody, duration, headers, failureSummary);

                        requestLogService.logRequest(requestId, ApiKeyLogMask.mask(clientApiKey), "POST", path,
                                model, headers, requestBody, response.code(), responseBody, duration,
                                tokenUsage, failureSummary);

                        throw new IOException("Claude upstream request failed: " + failureSummary);
                    }
                }

                // Success - track usage and return
                trackApiUsage(clientApiKey, requestId, model, path, "POST",
                        response.code(), responseBody, duration, headers, null);

                requestLogService.logRequest(requestId, ApiKeyLogMask.mask(clientApiKey), "POST", path,
                        model, headers, requestBody, response.code(), responseBody, duration,
                        tokenUsage, null);

                return responseBody;

            } catch (IOException e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    log.warn("⚠️ [{}] Attempt {}/{} threw exception={}, retrying after 3000ms...",
                            requestId, attempt, maxAttempts, exceptionType(e));
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Retry interrupted", ie);
                    }
                } else {
                    // Last attempt failed
                    long duration = System.currentTimeMillis() - startTime;
                    String failureSummary = safeFailureSummary(500, null, e);
                    log.error("❌ [{}] All {} attempts failed: {} - 耗时: {} ms, 上游: {}",
                            requestId, maxAttempts, failureSummary, duration, upstream.name);

                    trackApiUsage(clientApiKey, requestId, model, path, "POST",
                            500, null, duration, headers, failureSummary);

                    requestLogService.logRequest(requestId, ApiKeyLogMask.mask(clientApiKey), "POST", path,
                            model, headers, requestBody, 500, null, duration,
                            TokenUsage.empty(), failureSummary);

                    throw e;
                }
            }
        }

        // Should never reach here, but required for compilation
        throw lastException != null ? lastException
                : new IOException("Unexpected error: retry loop exited without exception");
    }

    /**
     * 转发 token 计数请求到 Claude API
     * POST /v1/messages/count_tokens
     *
     * @param requestBody  JSON 格式的请求体（包含 messages 等参数）
     * @param headers      附加的请求头
     * @param clientApiKey 客户端 API Key，用于使用量统计
     * @return Claude API 的响应（包含 token 计数信息）
     */
    public String forwardCountTokensRequest(String requestBody, Map<String, String> headers, String clientApiKey)
            throws IOException {
        String url = anthropicConfig.getBaseUrl() + "/v1/messages/count_tokens";
        long startTime = System.currentTimeMillis();
        String requestId = GatewayRequestContext.currentOrNewString();

        log.info("========================================");
        log.info("🔢 转发 Count Tokens 请求到 Claude API");
        log.info("请求ID: {}", requestId);
        log.info("Endpoint: /v1/messages/count_tokens");
        if (headers != null && !headers.isEmpty()) {
            Map<String, String> sanitizedHeaders =
                    SensitiveHeaderSanitizer.sanitizeForPersistence(headers);
            if (!sanitizedHeaders.isEmpty()) {
                log.info("count_tokens.request_headers requestId={} headers={}", requestId, sanitizedHeaders);
            }
        }
        log.info("count_tokens.req_stats requestId={} {}", requestId, summarizeRequestBody(requestBody));
        log.info("========================================");

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                .addHeader("x-api-key", anthropicConfig.getAuthToken())
                .addHeader("Content-Type", "application/json");

        // 添加自定义请求头
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (!key.equalsIgnoreCase("Authorization") && !key.equalsIgnoreCase("x-api-key")) {
                    requestBuilder.addHeader(key, value);
                }
            });
        }

        Request request = requestBuilder.build();

        // Retry logic: attempt up to 2 times with 3000ms delay
        int maxAttempts = 2;
        int attempt = 0;
        IOException lastException = null;

        while (attempt < maxAttempts) {
            attempt++;
            try (Response response = getHttpClient().newCall(request).execute()) {
                long duration = System.currentTimeMillis() - startTime;
                String responseBody = response.body() != null ? response.body().string() : "{}";

                log.info("========================================");
                log.info("🔢 收到 Count Tokens 响应");
                log.info("请求ID: {}", requestId);
                log.info("状态码: {}", response.code());
                log.info("耗时: {} ms", duration);
                log.info("响应体大小: {} bytes", bodyBytes(responseBody));
                log.info("========================================");

                if (!response.isSuccessful()) {
                    if (attempt < maxAttempts) {
                        log.warn("⚠️ [{}] Count Tokens attempt {}/{} failed with status {}, retrying after 3000ms...",
                                requestId, attempt, maxAttempts, response.code());
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Retry interrupted", ie);
                        }
                        continue; // Retry
                    } else {
                        // Last attempt failed
                        String failureSummary = safeFailureSummary(response.code(), responseBody, null);
                        log.error("❌ [{}] All {} Count Tokens attempts failed: {}",
                                requestId, maxAttempts, failureSummary);
                        throw new IOException("Count Tokens upstream request failed: " + failureSummary);
                    }
                }

                // count_tokens 请求不计入使用量统计（或按需要决定是否计入）
                // 这里仅记录为日志，不调用 trackApiUsage

                return responseBody;

            } catch (IOException e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    log.warn("⚠️ [{}] Count Tokens attempt {}/{} threw exception={}, retrying after 3000ms...",
                            requestId, attempt, maxAttempts, exceptionType(e));
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Retry interrupted", ie);
                    }
                } else {
                    // Last attempt failed
                    long duration = System.currentTimeMillis() - startTime;
                    log.error("❌ [{}] All {} Count Tokens attempts failed: 耗时 {} ms, {}",
                            requestId, maxAttempts, duration, safeFailureSummary(500, null, e));
                    throw e;
                }
            }
        }

        // Should never reach here, but required for compilation
        throw lastException != null ? lastException
                : new IOException("Unexpected error: retry loop exited without exception");
    }

    /**
     * 转发 multipart/form-data 文件上传到 Claude Files API (POST /v1/files)。
     * Claude Code 用户附件 / MCP 上传 PDF 走这里。
     * <p>
     * 不消耗 token, QuotaFilter 不拦; 但仍记录 request log 和 api_usage_log (cost_usd=0)。
     */
    public String forwardFileUpload(String path, MultipartFile file, Map<String, String> headers,
                                    String clientApiKey) throws IOException {
        UpstreamInfo upstream = getUpstreamInfo(null, ai.nubase.common.enums.ApiProvider.CLAUDE);
        String url = upstream.baseUrl + path;
        long startTime = System.currentTimeMillis();
        String requestId = GatewayRequestContext.currentOrNewString();

        log.info("📤 [{}] POST {} (multipart) - filenamePresent={}, size={}, content-type={}, upstream={}",
                requestId, path, file.getOriginalFilename() != null, file.getSize(), file.getContentType(),
                upstream.name);

        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";

        RequestBody filePart = RequestBody.create(file.getBytes(), MediaType.parse(contentType));
        MultipartBody multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, filePart)
                .build();

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(multipart)
                .addHeader("x-api-key", upstream.authToken);

        // anthropic-beta: files-api-2025-04-14 必须由客户端透传; 这里只转发, 不强加
        // Content-Type / Content-Length 由 OkHttp 自动设置 (含 multipart boundary), 不能手动覆盖
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (!key.equalsIgnoreCase("Authorization") &&
                        !key.equalsIgnoreCase("x-api-key") &&
                        !key.equalsIgnoreCase("Content-Type") &&
                        !key.equalsIgnoreCase("Content-Length") &&
                        !key.equalsIgnoreCase("Host")) {
                    requestBuilder.addHeader(key, value);
                }
            });
        }

        Request request = requestBuilder.build();

        try (Response response = getHttpClient().newCall(request).execute()) {
            long duration = System.currentTimeMillis() - startTime;
            String responseBody = response.body() != null ? response.body().string() : "{}";

            log.info("📥 [{}] {} - 耗时: {} ms", requestId, response.code(), duration);

            String failureSummary = response.isSuccessful()
                    ? null
                    : safeFailureSummary(response.code(), responseBody, null);

            // 文件上传不消耗 token, 但仍记录调用以便审计
            trackApiUsage(clientApiKey, requestId, null, path, "POST",
                    response.code(), null, duration, headers,
                    failureSummary);

            requestLogService.logRequest(requestId, ApiKeyLogMask.mask(clientApiKey), "POST", path,
                    null, headers,
                    "{\"_multipart\":true,\"filename\":\"" + filename.replace("\"", "\\\"")
                            + "\",\"size\":" + file.getSize() + "}",
                    response.code(), responseBody, duration,
                    TokenUsage.empty(),
                    failureSummary);

            if (!response.isSuccessful()) {
                throw new IOException("Claude Files upload failed: " + failureSummary);
            }

            return responseBody;
        }
    }

    /**
     * 流式转发 Claude Files API 的二进制下载 (GET /v1/files/{id}/content)。
     * 直接把上游响应体 pipe 到 HttpServletResponse, 避免把整个文件读入内存。
     * <p>
     * 不消耗 token; 主要 Claude Code 拉 code-execution / skills 生成的产物时用。
     */
    public void forwardFileDownload(String path, Map<String, String> headers, String clientApiKey,
                                    HttpServletResponse downstream) throws IOException {
        UpstreamInfo upstream = getUpstreamInfo(null, ai.nubase.common.enums.ApiProvider.CLAUDE);
        String url = upstream.baseUrl + path;
        long startTime = System.currentTimeMillis();
        String requestId = GatewayRequestContext.currentOrNewString();

        log.info("📤 [{}] GET {} (binary download) - upstream={}", requestId, path, upstream.name);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .get()
                .addHeader("x-api-key", upstream.authToken);

        if (headers != null) {
            headers.forEach((key, value) -> {
                if (!key.equalsIgnoreCase("Authorization") &&
                        !key.equalsIgnoreCase("x-api-key") &&
                        !key.equalsIgnoreCase("Host")) {
                    requestBuilder.addHeader(key, value);
                }
            });
        }

        Response response = getHttpClient().newCall(requestBuilder.build()).execute();
        long duration = System.currentTimeMillis() - startTime;

        ResponseBody body = response.body();
        try {
            int status = response.code();
            downstream.setStatus(status);

            if (!response.isSuccessful()) {
                String errorBody = body != null ? body.string() : "";
                String failureSummary = safeFailureSummary(status, errorBody, null);
                log.error("❌ [{}] download 失败: {}", requestId, failureSummary);
                downstream.setContentType("application/json");
                trackApiUsage(clientApiKey, requestId, null, path, "GET",
                        status, null, duration, headers, failureSummary);
                requestLogService.logRequest(requestId, ApiKeyLogMask.mask(clientApiKey), "GET", path,
                        null, headers, null, status, errorBody, duration,
                        TokenUsage.empty(), failureSummary);
                downstream.getOutputStream().write(errorBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return;
            }

            // 复制关键响应头
            String upstreamContentType = response.header("Content-Type");
            if (upstreamContentType != null) {
                downstream.setContentType(upstreamContentType);
            } else if (body != null && body.contentType() != null) {
                downstream.setContentType(body.contentType().toString());
            }
            String contentDisposition = response.header("Content-Disposition");
            if (contentDisposition != null) {
                downstream.setHeader("Content-Disposition", contentDisposition);
            }
            long contentLength = body != null ? body.contentLength() : -1;
            if (contentLength >= 0) {
                downstream.setContentLengthLong(contentLength);
            }

            // 流式 pipe 上游 → 客户端
            try (InputStream in = body != null ? body.byteStream() : InputStream.nullInputStream();
                 OutputStream out = downstream.getOutputStream()) {
                in.transferTo(out);
                out.flush();
            }

            log.info("📥 [{}] {} - 耗时: {} ms, content-type={}", requestId, status, duration, upstreamContentType);

            trackApiUsage(clientApiKey, requestId, null, path, "GET",
                    status, null, duration, headers, null);
            requestLogService.logRequest(requestId, ApiKeyLogMask.mask(clientApiKey), "GET", path,
                    null, headers, null, status, "{\"_binary\":true}", duration,
                    TokenUsage.empty(), null);
        } finally {
            response.close();
        }
    }

    /**
     * 通用 HTTP 方法转发 (GET / DELETE / POST / PUT / PATCH)。
     * 兜底 proxyRequest 使用此方法保留客户端原始 HTTP method, 避免把 GET/DELETE 误转成 POST。
     * <p>
     * 适用于不消耗 token 的元数据 / 管理类调用 (Files list/metadata/delete、batches list/cancel 等)。
     * 没有失败重试和 failover —— 这类调用幂等性弱、上游问题应直接暴露给客户端。
     */
    public String forwardGenericRequest(String method, String path, String requestBody,
                                        Map<String, String> headers, String clientApiKey,
                                        ai.nubase.common.enums.ApiProvider provider) throws IOException {
        if (provider == null) {
            provider = ai.nubase.common.enums.ApiProvider.CLAUDE;
        }
        UpstreamInfo upstream = getUpstreamInfo(null, provider);
        String url = upstream.baseUrl + path;
        long startTime = System.currentTimeMillis();
        String requestId = GatewayRequestContext.currentOrNewString();
        String upperMethod = method == null ? "GET" : method.toUpperCase(Locale.ROOT);

        log.info("📤 [{}] {} {} - upstream={}", requestId, upperMethod, path, upstream.name);

        Request.Builder rb = new Request.Builder()
                .url(url)
                .addHeader("x-api-key", upstream.authToken);

        boolean expectsBody = "POST".equals(upperMethod)
                || "PUT".equals(upperMethod)
                || "PATCH".equals(upperMethod);
        boolean hasBody = requestBody != null && !requestBody.isEmpty();

        switch (upperMethod) {
            case "GET" -> rb.get();
            case "HEAD" -> rb.head();
            case "DELETE" -> {
                if (hasBody && !"{}".equals(requestBody)) {
                    rb.delete(RequestBody.create(requestBody, MediaType.parse("application/json")));
                    rb.addHeader("Content-Type", "application/json");
                } else {
                    rb.delete();
                }
            }
            case "POST", "PUT", "PATCH" -> {
                String body = hasBody ? requestBody : "{}";
                RequestBody reqBody = RequestBody.create(body, MediaType.parse("application/json"));
                if ("POST".equals(upperMethod)) rb.post(reqBody);
                else if ("PUT".equals(upperMethod)) rb.put(reqBody);
                else rb.patch(reqBody);
                rb.addHeader("Content-Type", "application/json");
            }
            default -> throw new IOException("Unsupported HTTP method: " + method);
        }

        if (headers != null) {
            headers.forEach((key, value) -> {
                if (!key.equalsIgnoreCase("Authorization") &&
                        !key.equalsIgnoreCase("x-api-key") &&
                        !key.equalsIgnoreCase("x-upstream") &&
                        !key.equalsIgnoreCase("Content-Type") &&
                        !key.equalsIgnoreCase("Content-Length") &&
                        !key.equalsIgnoreCase("Host")) {
                    rb.addHeader(key, value);
                }
            });
        }

        try (Response response = getHttpClient().newCall(rb.build()).execute()) {
            long duration = System.currentTimeMillis() - startTime;
            String responseBody = response.body() != null ? response.body().string() : "{}";

            log.info("📥 [{}] {} {} - {} - 耗时: {} ms",
                    requestId, upperMethod, path, response.code(), duration);

            String failureSummary = response.isSuccessful()
                    ? null
                    : safeFailureSummary(response.code(), responseBody, null);

            trackApiUsage(clientApiKey, requestId, null, path, upperMethod,
                    response.code(), null, duration, headers,
                    failureSummary);

            requestLogService.logRequest(requestId, ApiKeyLogMask.mask(clientApiKey), upperMethod, path,
                    null, headers, expectsBody ? requestBody : null,
                    response.code(), responseBody, duration,
                    TokenUsage.empty(),
                    failureSummary);

            if (!response.isSuccessful()) {
                throw new IOException("Claude API " + upperMethod + " " + path
                        + " 失败: " + response.code() + " - " + responseBody);
            }
            return responseBody;
        }
    }

    /**
     * 转发事件日志批处理请求到 Claude API
     *
     * @param requestBody JSON 格式的请求体（事件批次）
     * @param headers     附加的请求头
     * @return Claude API 的响应
     */
    public String forwardEventLoggingRequest(String requestBody, Map<String, String> headers) throws IOException {
        String url = anthropicConfig.getBaseUrl() + "/v1/event_logging/batch";

        log.info("转发事件日志批处理请求到 Claude API");
        log.info("事件日志批次大小: {} 字节", requestBody.length());

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                .addHeader("x-api-key", anthropicConfig.getAuthToken())
                .addHeader("Content-Type", "application/json");

        // 添加自定义请求头
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (!key.equalsIgnoreCase("Authorization") && !key.equalsIgnoreCase("x-api-key")) {
                    requestBuilder.addHeader(key, value);
                }
            });
        }

        Request request = requestBuilder.build();

        try (Response response = getHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "无错误详情";
                String failureSummary = safeFailureSummary(response.code(), errorBody, null);
                log.error("事件日志请求失败: {}", failureSummary);

                // 事件日志失败 - 记录但不失败
                if (response.code() >= 500) {
                    // 服务器错误 - 记录但不失败
                    log.warn("Claude API 服务器错误（事件日志，非关键）: {}", response.code());
                } else if (response.code() == 404) {
                    // 端点未找到 - 可能不支持
                    log.warn("事件日志端点未找到 - 功能可能不可用");
                } else if (response.code() >= 400) {
                    log.error("事件日志请求客户端错误: status={} bodyBytes={}",
                            response.code(), bodyBytes(errorBody));
                }

                throw new IOException("Event logging upstream request failed: " + failureSummary);
            }

            String responseBody = response.body() != null ? response.body().string() : "{}";
            log.info("事件日志批次处理成功: bodyBytes={}", bodyBytes(responseBody));
            return responseBody;
        } catch (IOException e) {
            log.error("事件日志请求过程中发生 IOException: exception={}", exceptionType(e));
            throw e;
        }
    }

    /**
     * 使用 SSE 转发流式请求到 Claude API
     *
     * @param path         API 端点路径
     * @param requestBody  JSON 格式的请求体
     * @param headers      附加的请求头
     * @param clientApiKey 客户端 API Key，用于使用量统计
     * @param upstreamName 上游配置名称（可选，为 null 时使用默认上游）
     * @param emitter      用于流式响应的 SSE 发射器
     */
    public void forwardStreamingRequest(String path, String requestBody, Map<String, String> headers,
                                        String clientApiKey, String upstreamName, SseEmitter emitter,
                                        ai.nubase.common.enums.ApiProvider provider) {
        if (provider == null) {
            provider = ai.nubase.common.enums.ApiProvider.CLAUDE;
        }
        final ai.nubase.common.enums.ApiProvider resolvedProvider = provider;
        UpstreamInfo primaryUpstream = getUpstreamInfo(upstreamName, resolvedProvider);
        long startTime = System.currentTimeMillis();
        String requestId = GatewayRequestContext.currentOrNewString();

        // Streaming SSE callbacks fire on OkHttp dispatcher threads, which do NOT carry the request
        // thread's ThreadLocals. Capture tenant context + routing source here (request thread) so the
        // usage tracking at stream end can restore them — otherwise both the per-tenant and platform
        // ledger writes would run without a resolved tenant/appCode.
        final ai.nubase.common.context.MultiTenancyContext.ContextData capturedContext =
                ai.nubase.common.context.MultiTenancyContext.getContext();
        final GatewayRoutingContext.Routing capturedRouting = GatewayRoutingContext.get();

        ContextPruneService.PruneResult prune = contextPruneService.pruneIfNeeded(
                requestBody, primaryUpstream.maxInputTokens, requestId);
        if (prune.pruned) {
            log.info("context_prune.applied requestId={} upstream={} {}",
                    requestId, primaryUpstream.name, prune.summary);
            requestBody = prune.body;
        }

        String model = usageTrackingService.extractModelFromRequest(requestBody);

        log.info("========================================");
        log.info("agent_log [{}] POST {} (stream) - 模型: {}, 上游: {}",
                requestId, path, model, primaryUpstream.name);
        log.info("上游配置: name={}, timeout={}ms, maxInputTokens={}",
                primaryUpstream.name, primaryUpstream.timeout, primaryUpstream.maxInputTokens);
        final String requestStats = summarizeRequestBody(requestBody);
        log.info("agent_log.req_stats requestId={} {}", requestId, requestStats);
        log.info("========================================");

        final String originalRequestBody = requestBody;
        String turnId = headers != null ? headers.get("x-turn-id") : null;
        String streamingRequestBody = requestBody;

        try {
            JsonNode jsonNode = objectMapper.readTree(streamingRequestBody);
            com.fasterxml.jackson.databind.node.ObjectNode objectNode = (com.fasterxml.jackson.databind.node.ObjectNode) jsonNode;

            if (!objectNode.has("stream") || !objectNode.get("stream").asBoolean()) {
                log.info("向请求体添加 'stream: true'");
                objectNode.put("stream", true);
            }

            if (turnId != null && !turnId.isEmpty()) {
                com.fasterxml.jackson.databind.node.ObjectNode metadata = objectNode.has("metadata")
                        ? (com.fasterxml.jackson.databind.node.ObjectNode) objectNode.get("metadata")
                        : objectNode.putObject("metadata");
                metadata.put("user_id", turnId);
                log.info("Added x-turn-id to metadata.user_id for requestId={}", requestId);
            }

            streamingRequestBody = objectMapper.writeValueAsString(objectNode);
        } catch (Exception e) {
            log.warn("无法解析请求体以添加 stream 标志或 metadata: exception={}", exceptionType(e));
        }

        final String preparedRequestBody = streamingRequestBody;
        final List<UpstreamInfo> upstreamAttempts = buildStreamingUpstreamAttempts(primaryUpstream, resolvedProvider);
        final AtomicReference<TokenUsage> accumulatedUsage = new AtomicReference<>(TokenUsage.empty());
        final AtomicReference<EventSource> activeEventSource = new AtomicReference<>();
        final AtomicBoolean clientStreamStarted = new AtomicBoolean(false);
        final AtomicBoolean emitterCompleted = new AtomicBoolean(false);
        // TTFT: 第一次进入 onEvent 时刻 - startTime, 用 AtomicLong(0) 表示"还没收到首事件"
        final java.util.concurrent.atomic.AtomicLong firstEventAt = new java.util.concurrent.atomic.AtomicLong(0L);
        // Keep only fixed event categories for stream diagnostics.
        final Map<String, Integer> eventTypeCounts = new java.util.concurrent.ConcurrentHashMap<>();

        class StreamingAttemptRunner {
            void start(int attemptIndex) {
                if (emitterCompleted.get()) {
                    return;
                }

                UpstreamInfo selectedUpstream = upstreamAttempts.get(attemptIndex);
                if (attemptIndex > 0) {
                    log.info("[upstream_error_transfer] trying streaming upstream '{}' (provider={}, attempt={}/{})",
                            selectedUpstream.name, resolvedProvider, attemptIndex + 1, upstreamAttempts.size());
                    upstreamConfigService.updateLastUsedAt(selectedUpstream.name);
                }

                Request request = buildClaudeStreamingRequest(path, preparedRequestBody, headers, selectedUpstream);

                EventSourceListener listener = new EventSourceListener() {
                    private volatile boolean streamCompleted = false;

                    @Override
                    public void onOpen(EventSource eventSource, Response response) {
                        log.info("✅ SSE connection established, requestId: {}, status: {}, upstream: {}",
                                requestId, response.code(), selectedUpstream.name);
                        log.info("upstream.response_headers requestId={} upstream={} status={} headers={}",
                                requestId, selectedUpstream.name, response.code(),
                                formatResponseHeadersForLogging(response.headers()));
                    }

                    @Override
                    public void onEvent(EventSource eventSource, String id, String type, String data) {
                        if (streamCompleted || emitterCompleted.get()) {
                            return;
                        }

                        clientStreamStarted.set(true);
                        // 首个事件抵达时刻 (compareAndSet 保证只赋一次)
                        firstEventAt.compareAndSet(0L, System.currentTimeMillis());

                        String typeKey = safeEventType(type);
                        eventTypeCounts.merge(typeKey, 1, Integer::sum);

                        try {
                            claudeResponseLog.info(
                                    "claude.sse_event requestId={} type={} dataBytes={}",
                                    requestId, typeKey, bodyBytes(data));

                            TokenUsage eventUsage = usageTrackingService.extractTokenUsageFromStreamEvent(data);
                            if (eventUsage != null) {
                                logClaudeUsageDiagnostics(
                                        "stream_event", requestId, path, model, selectedUpstream.name, eventUsage);
                                TokenUsage current = accumulatedUsage.get();
                                int inputTokens = Math.max(current.getInputTokens(), eventUsage.getInputTokens());
                                int outputTokens = Math.max(current.getOutputTokens(), eventUsage.getOutputTokens());
                                int cacheCreationInputTokens = Math.max(current.getCacheCreationInputTokens(),
                                        eventUsage.getCacheCreationInputTokens());
                                int cacheReadInputTokens = Math.max(current.getCacheReadInputTokens(),
                                        eventUsage.getCacheReadInputTokens());
                                accumulatedUsage.set(TokenUsage.builder()
                                        .inputTokens(inputTokens)
                                        .outputTokens(outputTokens)
                                        .totalTokens(inputTokens + outputTokens)
                                        .cacheCreationInputTokens(cacheCreationInputTokens)
                                        .cacheReadInputTokens(cacheReadInputTokens)
                                        .build());
                                claudeResponseLog.info("🔢 [{}] Token usage updated: input={}, output={}, total={}, cacheCreation={}, cacheRead={}",
                                        requestId, accumulatedUsage.get().getInputTokens(),
                                        accumulatedUsage.get().getOutputTokens(),
                                        accumulatedUsage.get().getTotalTokens(),
                                        accumulatedUsage.get().getCacheCreationInputTokens(),
                                        accumulatedUsage.get().getCacheReadInputTokens());
                            }

                            SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event().data(data);

                            if (id != null && !id.isEmpty()) {
                                eventBuilder.id(id);
                            }
                            if (type != null && !type.isEmpty()) {
                                eventBuilder.name(type);
                            }

                            emitter.send(eventBuilder);

                            boolean isStreamEnd = isStreamEndEvent(type, data);
                            if (isStreamEnd && !streamCompleted) {
                                streamCompleted = true;
                                completeStream(eventSource);
                            }
                        } catch (IOException e) {
                            log.error("Failed to send SSE event to client: exception={}", exceptionType(e));
                            streamCompleted = true;
                            completeWithError(e);
                            eventSource.cancel();
                        }
                    }

                    @Override
                    public void onClosed(EventSource eventSource) {
                        long duration = System.currentTimeMillis() - startTime;
                        int totalEvents = eventTypeCounts.values().stream().mapToInt(Integer::intValue).sum();
                        log.info("🔒 SSE connection closed, requestId: {}, upstream: {}, duration: {}ms, eventCount: {}, eventTypes: {}",
                                requestId, selectedUpstream.name, duration, totalEvents, eventTypeCounts);
                        if (streamCompleted || emitterCompleted.get()) {
                            return;
                        }

                        streamCompleted = true;
                        String closeMessage = "SSE connection closed before first event";
                        if (!clientStreamStarted.get() && startNextIfAvailable(attemptIndex, selectedUpstream, closeMessage)) {
                            return;
                        }

                        // Record only aggregate stream shape when the upstream closes early.
                        if (clientStreamStarted.get()) {
                            log.warn("claude_stream.closed_without_stream_end requestId={} upstream={} model={} duration={}ms eventCount={} eventTypes={} reqStats=[{}]",
                                    requestId, selectedUpstream.name, model, duration,
                                    totalEvents, eventTypeCounts, requestStats);
                        }

                        completeEmitter();
                    }

                    @Override
                    public void onFailure(EventSource eventSource, Throwable t, Response response) {
                        if (streamCompleted || emitterCompleted.get()) {
                            return;
                        }

                        streamCompleted = true;
                        int statusCode = response != null ? response.code() : 500;
                        String errorBody = readResponseBody(response);
                        String failureSummary = safeFailureSummary(statusCode, errorBody, t);

                        log.error("❌ SSE connection failed, requestId: {}, upstream: {}, {}",
                                requestId, selectedUpstream.name, failureSummary);

                        recordFailedAttempt(statusCode, errorBody, failureSummary);

                        if (!clientStreamStarted.get()
                                && startNextIfAvailable(attemptIndex, selectedUpstream, failureSummary)) {
                            eventSource.cancel();
                            return;
                        }

                        sendErrorEvent(errorBody);
                        Throwable error = t != null ? t : new IOException("SSE failed with status " + statusCode);
                        completeWithError(error);
                        eventSource.cancel();
                    }

                    private boolean isStreamEndEvent(String type, String data) {
                        if ("message_stop".equals(type)) {
                            return true;
                        }

                        if (data != null) {
                            return data.contains("\"type\":\"message_stop\"") ||
                                    (data.contains("\"type\":\"message_delta\"") && data.contains("\"stop_reason\""));
                        }

                        return false;
                    }

                    private void completeStream(EventSource eventSource) {
                        long duration = System.currentTimeMillis() - startTime;
                        TokenUsage finalUsage = accumulatedUsage.get();

                        log.info("🏁 [{}] Streaming response completed - duration: {} ms, Tokens: {}/{}/{}",
                                requestId, duration,
                                finalUsage.getInputTokens(),
                                finalUsage.getOutputTokens(),
                                finalUsage.getTotalTokens());

                        if (finalUsage.getCacheCreationInputTokens() > 0 || finalUsage.getCacheReadInputTokens() > 0) {
                            log.info("   Cache - creation: {}, read: {}",
                                    finalUsage.getCacheCreationInputTokens(),
                                    finalUsage.getCacheReadInputTokens());
                        }

                        // Record only aggregate stream shape for zero-token diagnostics.
                        boolean usageEmpty = finalUsage.getInputTokens() == 0
                                && finalUsage.getOutputTokens() == 0
                                && finalUsage.getCacheCreationInputTokens() == 0
                                && finalUsage.getCacheReadInputTokens() == 0;
                        if (usageEmpty) {
                            int totalEvents = eventTypeCounts.values().stream().mapToInt(Integer::intValue).sum();
                            log.warn("api_usage.zero_tokens_on_claude_stream requestId={} upstream={} model={} path={} duration={}ms eventCount={} eventTypes={} reqStats=[{}]",
                                    requestId, selectedUpstream.name, model, path, duration,
                                    totalEvents, eventTypeCounts, requestStats);
                        }

                        log.info("api_usage.claude_stream.final requestId={} endpoint={} model={} upstream={} tokens input={} output={} total={} cacheCreation={} cacheRead={}",
                                requestId, path, model, selectedUpstream.name,
                                finalUsage.getInputTokens(),
                                finalUsage.getOutputTokens(),
                                finalUsage.getTotalTokens(),
                                finalUsage.getCacheCreationInputTokens(),
                                finalUsage.getCacheReadInputTokens());

                        long firstAt = firstEventAt.get();
                        Long ttft = firstAt > 0L ? firstAt - startTime : null;
                        runWithCapturedContext(capturedContext, capturedRouting, () ->
                                trackApiUsageWithTokens(clientApiKey, requestId, model, path, "POST",
                                        200, finalUsage, duration, ttft, headers));

                        requestLogService.logRequest(requestId, ApiKeyLogMask.mask(clientApiKey), "POST", path,
                                model, headers, originalRequestBody, 200,
                                "{\"type\":\"message_stream\",\"status\":\"completed\"}",
                                duration, finalUsage, null);

                        completeEmitter();
                        eventSource.cancel();
                    }

                    private void recordFailedAttempt(int statusCode, String errorBody, String failureSummary) {
                        long duration = System.currentTimeMillis() - startTime;
                        runWithCapturedContext(capturedContext, capturedRouting, () ->
                                trackApiUsage(clientApiKey, requestId, model, path, "POST",
                                        statusCode, null, duration, headers, failureSummary));

                        requestLogService.logRequest(requestId, ApiKeyLogMask.mask(clientApiKey), "POST", path,
                                model, headers, originalRequestBody, statusCode, errorBody, duration,
                                TokenUsage.empty(), failureSummary);
                    }

                    private String readResponseBody(Response response) {
                        if (response == null || response.body() == null) {
                            return null;
                        }

                        try {
                            return response.body().string();
                        } catch (IOException e) {
                            log.error("Unable to read error response: exception={}", exceptionType(e));
                            return null;
                        }
                    }

                    private void sendErrorEvent(String errorBody) {
                        if (errorBody == null) {
                            return;
                        }

                        try {
                            String errorEvent = String.format(
                                    "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"%s\"}}",
                                    errorBody.replace("\"", "\\\""));
                            emitter.send(SseEmitter.event()
                                    .name("error")
                                    .data(errorEvent));
                        } catch (IOException e) {
                            log.error("Unable to send error event: exception={}", exceptionType(e));
                        }
                    }
                };

                EventSource eventSource = streamingHttpClientProvider.newEventSource(
                        request,
                        listener,
                        anthropicConfig.getTimeout(),
                        0,
                        anthropicConfig.getTimeout(),
                        requestId,
                        "claude-compatible",
                        selectedUpstream.name);
                EventSource previous = activeEventSource.getAndSet(eventSource);
                if (previous != null && previous != eventSource) {
                    previous.cancel();
                }
            }

            private boolean startNextIfAvailable(int attemptIndex, UpstreamInfo failedUpstream, String reason) {
                int nextAttemptIndex = attemptIndex + 1;
                if (nextAttemptIndex >= upstreamAttempts.size() || emitterCompleted.get()) {
                    return false;
                }

                UpstreamInfo nextUpstream = upstreamAttempts.get(nextAttemptIndex);
                log.warn("[upstream_error_transfer] streaming upstream '{}' failed before first event, "
                                + "switching to '{}' (provider={}, reason={})",
                        failedUpstream.name, nextUpstream.name, resolvedProvider, reason);
                start(nextAttemptIndex);
                return true;
            }

            private void completeEmitter() {
                if (emitterCompleted.compareAndSet(false, true)) {
                    emitter.complete();
                }
            }

            private void completeWithError(Throwable error) {
                if (emitterCompleted.compareAndSet(false, true)) {
                    emitter.completeWithError(error);
                }
            }

            private void cancelActiveEventSource() {
                EventSource current = activeEventSource.get();
                if (current != null) {
                    current.cancel();
                }
            }
        }

        StreamingAttemptRunner streamingAttemptRunner = new StreamingAttemptRunner();
        streamingAttemptRunner.start(0);

        emitter.onTimeout(() -> {
            log.warn("⏱️ SSE 发射器超时，请求ID: {}, 耗时: {} ms",
                    requestId, System.currentTimeMillis() - startTime);
            long duration = System.currentTimeMillis() - startTime;
            String failureSummary = safeFailureSummary(408, null, null);

            trackApiUsage(clientApiKey, requestId, model, path, "POST",
                    408, null, duration, headers, failureSummary);

            requestLogService.logRequest(requestId, ApiKeyLogMask.mask(clientApiKey), "POST", path,
                    model, headers, originalRequestBody, 408, null, duration,
                    TokenUsage.empty(), failureSummary);

            streamingAttemptRunner.cancelActiveEventSource();
            if (emitterCompleted.compareAndSet(false, true)) {
                emitter.complete();
            }
        });

        emitter.onCompletion(() -> {
            log.info("✅ SSE 发射器已完成，请求ID: {}", requestId);
            emitterCompleted.set(true);
            streamingAttemptRunner.cancelActiveEventSource();
        });

        emitter.onError((e) -> {
            String failureSummary = safeFailureSummary(500, null, e);
            log.error("❌ SSE 发射器错误，请求ID: {}, {}", requestId, failureSummary);
            long duration = System.currentTimeMillis() - startTime;

            trackApiUsage(clientApiKey, requestId, model, path, "POST",
                    500, null, duration, headers, failureSummary);

            emitterCompleted.set(true);
            streamingAttemptRunner.cancelActiveEventSource();
        });
    }

    private List<UpstreamInfo> buildStreamingUpstreamAttempts(
            UpstreamInfo primaryUpstream, ai.nubase.common.enums.ApiProvider provider) {
        List<UpstreamInfo> upstreamAttempts = new ArrayList<>();
        upstreamAttempts.add(primaryUpstream);

        try {
            List<String> triedUpstreams = new ArrayList<>();
            triedUpstreams.add(primaryUpstream.name);
            List<ai.nubase.ai.gateway.entity.UpstreamConfig> failoverCandidates =
                    upstreamConfigService.getFailoverUpstreams(provider, triedUpstreams);

            for (ai.nubase.ai.gateway.entity.UpstreamConfig fallback : failoverCandidates) {
                upstreamAttempts.add(new UpstreamInfo(
                        fallback.getName(),
                        fallback.getBaseUrl(),
                        fallback.getAuthToken(),
                        fallback.getTimeoutMs(),
                        fallback.getMaxInputTokens()));
            }
        } catch (Exception e) {
            log.warn("Unable to load streaming failover upstreams for provider {}: exception={}",
                    provider, exceptionType(e));
        }

        return upstreamAttempts;
    }

    private Request buildClaudeStreamingRequest(
            String path, String requestBody, Map<String, String> headers, UpstreamInfo upstream) {
        Request.Builder requestBuilder = new Request.Builder()
                .url(upstream.baseUrl + path)
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                .addHeader("x-api-key", upstream.authToken)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream");

        if (headers != null) {
            headers.forEach((key, value) -> {
                if (!key.equalsIgnoreCase("Authorization") &&
                        !key.equalsIgnoreCase("x-api-key") &&
                        !key.equalsIgnoreCase("x-upstream")) {
                    requestBuilder.addHeader(key, value);
                }
            });
        }

        return requestBuilder.build();
    }

    /**
     * 在还原了请求线程租户上下文（及网关路由来源）的情况下执行用量统计。用于流式回调这类运行在
     * OkHttp 派发线程、天然没有 ThreadLocal 的场景；非流式路径本就在请求线程上，可直接调用。
     * 仅在当前线程无上下文时设置并在结束后清理，避免污染真正携带上下文的线程。
     */
    private void runWithCapturedContext(ai.nubase.common.context.MultiTenancyContext.ContextData ctx,
                                        GatewayRoutingContext.Routing routing, Runnable tracking) {
        boolean contextApplied = false;
        try {
            if (ctx != null && !ai.nubase.common.context.MultiTenancyContext.hasContext()) {
                ai.nubase.common.context.MultiTenancyContext.setContext(ctx);
                contextApplied = true;
            }
            if (routing != null && GatewayRoutingContext.get() == null) {
                GatewayRoutingContext.set(routing.source(), routing.upstreamName());
            }
            tracking.run();
        } finally {
            if (contextApplied) {
                ai.nubase.common.context.MultiTenancyContext.clear();
                GatewayRoutingContext.clear();
            }
        }
    }

    /**
     * 跟踪 API 使用量
     */
    private void trackApiUsage(String apiKey, String requestId, String model, String endpoint,
                               String method, int statusCode, String responseBody,
                               long durationMs, Map<String, String> headers, String errorMessage) {
        try {
            TokenUsage tokenUsage = TokenUsage.empty();
            if (responseBody != null) {
                tokenUsage = usageTrackingService.extractTokenUsage(responseBody);
            }

            log.info("api_usage.claude.track requestId={} endpoint={} model={} status={} tokens input={} output={} total={} cacheCreation={} cacheRead={} error={}",
                    requestId, endpoint, model, statusCode,
                    tokenUsage.getInputTokens(),
                    tokenUsage.getOutputTokens(),
                    tokenUsage.getTotalTokens(),
                    tokenUsage.getCacheCreationInputTokens(),
                    tokenUsage.getCacheReadInputTokens(),
                    errorMessage);

            ApiUsageRecord record = ApiUsageRecord.builder()
                    .apiKey(apiKey)
                    .requestId(requestId)
                    .model(model)
                    .endpoint(endpoint)
                    .method(method)
                    .statusCode(statusCode)
                    .tokenUsage(tokenUsage)
                    .durationMs(durationMs)
                    .errorMessage(errorMessage)
                    .requestMetadata(usageTrackingService.createRequestMetadata(
                            headers != null ? headers.get("user-agent") : null, headers))
                    .build();

            usageTrackingService.trackUsage(record);
        } catch (Exception e) {
            log.error("跟踪 API 使用量失败: exception={}", exceptionType(e));
        }
    }

    /**
     * 使用预提取的 token 使用量跟踪 API 使用情况
     */
    private void trackApiUsageWithTokens(String apiKey, String requestId, String model, String endpoint,
                                         String method, int statusCode, TokenUsage tokenUsage,
                                         long durationMs, Long firstTokenLatencyMs,
                                         Map<String, String> headers) {
        try {
            log.info("api_usage.claude.track requestId={} endpoint={} model={} status={} tokens input={} output={} total={} cacheCreation={} cacheRead={} ttftMs={}",
                    requestId, endpoint, model, statusCode,
                    tokenUsage.getInputTokens(),
                    tokenUsage.getOutputTokens(),
                    tokenUsage.getTotalTokens(),
                    tokenUsage.getCacheCreationInputTokens(),
                    tokenUsage.getCacheReadInputTokens(),
                    firstTokenLatencyMs);

            ApiUsageRecord record = ApiUsageRecord.builder()
                    .apiKey(apiKey)
                    .requestId(requestId)
                    .model(model)
                    .endpoint(endpoint)
                    .method(method)
                    .statusCode(statusCode)
                    .tokenUsage(tokenUsage)
                    .durationMs(durationMs)
                    .firstTokenLatencyMs(firstTokenLatencyMs)
                    .requestMetadata(usageTrackingService.createRequestMetadata(
                            headers != null ? headers.get("user-agent") : null, headers))
                    .build();

            usageTrackingService.trackUsage(record);
        } catch (Exception e) {
            log.error("跟踪 API 使用量失败: exception={}", exceptionType(e));
        }
    }

    private void logClaudeUsageDiagnostics(String phase, String requestId, String endpoint, String model,
                                           String upstream, TokenUsage tokenUsage) {
        if (tokenUsage == null) {
            return;
        }
        boolean empty = tokenUsage.getInputTokens() == 0
                && tokenUsage.getOutputTokens() == 0
                && tokenUsage.getCacheCreationInputTokens() == 0
                && tokenUsage.getCacheReadInputTokens() == 0;
        log.info("api_usage.claude.parsed phase={} requestId={} endpoint={} model={} upstream={} input={} output={} total={} cacheCreation={} cacheRead={} empty={}",
                phase, requestId, endpoint, model, upstream,
                tokenUsage.getInputTokens(),
                tokenUsage.getOutputTokens(),
                tokenUsage.getTotalTokens(),
                tokenUsage.getCacheCreationInputTokens(),
                tokenUsage.getCacheReadInputTokens(),
                empty);
    }

    static String safeFailureSummary(int statusCode, String responseBody, Throwable error) {
        return "status=" + statusCode
                + " exception=" + exceptionType(error)
                + " bodyBytes=" + bodyBytes(responseBody);
    }

    static String safeEventType(String eventType) {
        return eventType != null && LOGGABLE_SSE_EVENT_TYPES.contains(eventType)
                ? eventType
                : "other";
    }

    static int bodyBytes(String body) {
        return body == null ? 0 : body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static String exceptionType(Throwable error) {
        if (error == null) {
            return "none";
        }
        String simpleName = error.getClass().getSimpleName();
        return simpleName.isEmpty() ? error.getClass().getName() : simpleName;
    }

    /**
     * 摘要请求体的关键形状，用于排查"200 + 0 tokens"等上游静默失败。
     * 关注点：体积、消息数、role 分布、content block 类型分布、tool_result 大小、
     * 系统提示是否存在、按 chars/4 估算的输入 token。
     */
    String summarizeRequestBody(String requestBody) {
        if (requestBody == null) {
            return "bodyBytes=0";
        }
        int bodyBytes = requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        try {
            JsonNode root = objectMapper.readTree(requestBody);
            int messageCount = 0;
            Map<String, Integer> roles = new java.util.LinkedHashMap<>();
            Map<String, Integer> blocks = new java.util.LinkedHashMap<>();
            long toolResultBytes = 0;
            long totalTextBytes = 0;

            JsonNode messages = root.get("messages");
            if (messages != null && messages.isArray()) {
                messageCount = messages.size();
                for (JsonNode msg : messages) {
                    String role = safeDiagnosticCategory(
                            msg.path("role").asText(""), LOGGABLE_MESSAGE_ROLES);
                    roles.merge(role, 1, Integer::sum);
                    JsonNode content = msg.get("content");
                    if (content == null) continue;
                    if (content.isTextual()) {
                        blocks.merge("text", 1, Integer::sum);
                        totalTextBytes += content.asText().length();
                    } else if (content.isArray()) {
                        for (JsonNode block : content) {
                            String rawType = block.path("type").asText("");
                            String safeType = safeDiagnosticCategory(
                                    rawType,
                                    LOGGABLE_CONTENT_BLOCK_TYPES);
                            blocks.merge(safeType, 1, Integer::sum);
                            if ("text".equals(rawType)) {
                                totalTextBytes += block.path("text").asText("").length();
                            } else if ("tool_result".equals(rawType)) {
                                JsonNode tc = block.get("content");
                                int len = tc == null ? 0 : tc.toString().length();
                                toolResultBytes += len;
                                totalTextBytes += len;
                            } else if ("tool_use".equals(rawType)) {
                                JsonNode input = block.get("input");
                                if (input != null) totalTextBytes += input.toString().length();
                            }
                        }
                    }
                }
            }

            boolean hasSystem = false;
            int systemBytes = 0;
            JsonNode system = root.get("system");
            if (system != null && !system.isNull()) {
                hasSystem = true;
                if (system.isTextual()) {
                    systemBytes = system.asText().length();
                } else if (system.isArray()) {
                    for (JsonNode b : system) {
                        systemBytes += b.path("text").asText("").length();
                    }
                }
                totalTextBytes += systemBytes;
            }

            long approxTokens = totalTextBytes / 4;
            return String.format(
                    "bodyBytes=%d messageCount=%d roles=%s contentBlocks=%s toolResultBytes=%d hasSystem=%s systemBytes=%d approxInputTokens=~%d",
                    bodyBytes, messageCount, roles, blocks, toolResultBytes, hasSystem, systemBytes, approxTokens);
        } catch (Exception e) {
            return "stats_parse_failed bodyBytes=" + bodyBytes;
        }
    }

    private static String safeDiagnosticCategory(String value, Set<String> allowlist) {
        return allowlist.contains(value) ? value : "other";
    }

    /**
     * Formats only explicitly approved upstream response headers for diagnostic logging.
     */
    static String formatResponseHeadersForLogging(okhttp3.Headers headers) {
        if (headers == null || headers.size() == 0) return "{}";
        StringJoiner values = new StringJoiner(", ", "{", "}");
        for (int i = 0; i < headers.size(); i++) {
            String name = headers.name(i);
            if (SensitiveHeaderSanitizer.isSafeForResponseLogging(name)) {
                // Upstream values are untrusted and may echo credentials or user content.
                values.add(name);
            }
        }
        return values.toString();
    }

}
