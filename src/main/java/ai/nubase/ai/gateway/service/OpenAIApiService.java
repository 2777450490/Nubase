package ai.nubase.ai.gateway.service;

import ai.nubase.ai.gateway.billing.GatewayRequestContext;
import ai.nubase.ai.gateway.converter.ClaudeToOpenAIConverter;
import ai.nubase.ai.gateway.converter.OpenAIToClaudeConverter;
import ai.nubase.ai.gateway.dto.ApiUsageRecord;
import ai.nubase.ai.gateway.dto.TokenUsage;
import ai.nubase.ai.gateway.dto.openai.OpenAIRequest;
import ai.nubase.ai.gateway.dto.openai.OpenAIResponse;
import ai.nubase.ai.gateway.dto.openai.OpenAIStreamChunk;
import ai.nubase.ai.gateway.entity.UpstreamConfig;
import ai.nubase.common.config.OpenAIConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI API Service
 * Handles requests to OpenAI API and converts responses to Claude format
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAIApiService {

    private static final Logger openAIResponseLog = LoggerFactory.getLogger("OpenAIResponseLogger");

    private final OpenAIConfig openAIConfig;
    private final ClaudeToOpenAIConverter claudeToOpenAIConverter;
    private final OpenAIToClaudeConverter openAIToClaudeConverter;
    private final ObjectMapper objectMapper;
    private final ApiUsageTrackingService usageTrackingService;
    private final ApiRequestLogService requestLogService;
    private final UpstreamConfigService upstreamConfigService;
    private final AiGatewayStreamingHttpClientProvider streamingHttpClientProvider;

    private OkHttpClient httpClient;

    /**
     * Initialize HTTP client with configured timeout
     */
    private OkHttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(openAIConfig.getTimeout(), TimeUnit.MILLISECONDS)
                    .readTimeout(openAIConfig.getTimeout(), TimeUnit.MILLISECONDS)
                    .writeTimeout(openAIConfig.getTimeout(), TimeUnit.MILLISECONDS)
                    .build();
        }
        return httpClient;
    }

    /**
     * Get upstream configuration info
     *
     * @param upstreamName upstream name (optional)
     * @return upstream info
     */
    private UpstreamInfo getUpstreamInfo(String upstreamName, ai.nubase.common.enums.ApiProvider provider,
                                         String model) {
        try {
            UpstreamConfig config;

            if (upstreamName != null && !upstreamName.isEmpty()) {
                config = upstreamConfigService.getByName(upstreamName);
                if (!upstreamConfigService.allowsModel(config, model)) {
                    throw new IllegalArgumentException("upstream model mismatch: upstream=" + config.getName()
                            + ", model=" + model);
                }
                log.info("Using specified upstream: {}", upstreamName);
            } else {
                config = upstreamConfigService.selectForProviderAndModel(provider, model);
                log.info("[upstream_config]Using default upstream: {} (provider={})", config.getName(), provider);
            }

            upstreamConfigService.updateLastUsedAt(config.getName());

            return new UpstreamInfo(
                    config.getName(),
                    config.getBaseUrl(),
                    config.getAuthToken(),
                    config.getTimeoutMs());
        } catch (Exception e) {
            log.warn("Unable to get upstream config from database; using config file: errorType={}",
                    errorType(e));
            return new UpstreamInfo(
                    "config-file",
                    openAIConfig.getBaseUrl(),
                    openAIConfig.getAuthToken(),
                    openAIConfig.getTimeout());
        }
    }

    /**
     * Upstream configuration info
     */
    @SuppressWarnings("unused") // timeout kept for future customization
    private static class UpstreamInfo {
        final String name;
        final String baseUrl;
        final String authToken;
        final int timeout;

        UpstreamInfo(String name, String baseUrl, String authToken, int timeout) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.authToken = authToken;
            this.timeout = timeout;
        }
    }

    /**
     * 处理 OpenAI API 非流式请求
     * 支持上游故障转移：如果主上游在所有重试后仍然失败，
     * 自动尝试同 provider 类型的其他活跃上游
     *
     * @param claudeRequestJson Claude 格式的请求体
     * @param upstreamName      上游名称（可选）
     * @param clientApiKey      客户端 API Key，用于使用量统计
     * @param headers           请求头
     * @return Claude 格式的响应
     * @throws IOException 如果所有上游均失败
     */
    public String handleNonStreamingRequest(String claudeRequestJson, String upstreamName,
                                            String clientApiKey, Map<String, String> headers,
                                            ai.nubase.common.enums.ApiProvider provider) throws IOException {
        if (provider == null) {
            provider = ai.nubase.common.enums.ApiProvider.OPENAI;
        }
        String model = extractModelFromRequest(claudeRequestJson);
        UpstreamInfo upstream = getUpstreamInfo(upstreamName, provider, model);

        // 首先尝试主上游
        try {
            return executeNonStreamingOpenAIRequest(claudeRequestJson, clientApiKey, headers, upstream);
        } catch (IOException primaryException) {
            log.warn("⚠️ 主上游 '{}' 请求失败（provider=OPENAI），尝试故障转移...",
                    upstream.name);

            // 尝试同 provider 类型的其他上游
            List<String> triedUpstreams = new ArrayList<>();
            triedUpstreams.add(upstream.name);

            List<UpstreamConfig> failoverCandidates = upstreamConfigService
                    .getFailoverUpstreamsByProviderAndModel(provider, model, triedUpstreams);

            for (UpstreamConfig fallback : failoverCandidates) {
                try {
                    UpstreamInfo fallbackUpstream = new UpstreamInfo(
                            fallback.getName(), fallback.getBaseUrl(),
                            fallback.getAuthToken(), fallback.getTimeoutMs());

                    log.info("[upstream_error_transfer]：尝试上游 '{}' (priority={}, provider=OPENAI)",
                            fallback.getName(), fallback.getPriority());

                    upstreamConfigService.updateLastUsedAt(fallback.getName());

                    String result = executeNonStreamingOpenAIRequest(
                            claudeRequestJson, clientApiKey, headers, fallbackUpstream);

                    log.info("[upstream_error_transfer]✅ 故障转移成功，使用上游 '{}'", fallback.getName());
                    return result;
                } catch (IOException failoverException) {
                    log.warn("Failover upstream '{}' failed: errorType={}",
                            fallback.getName(), errorType(failoverException));
                    triedUpstreams.add(fallback.getName());
                }
            }

            // 所有上游均已耗尽
            log.error("❌ provider OPENAI 所有上游均已耗尽，已尝试: {}", triedUpstreams);
            throw primaryException;
        }
    }

    /**
     * 向指定 OpenAI 上游执行非流式请求（含重试逻辑）
     */
    private String executeNonStreamingOpenAIRequest(String claudeRequestJson, String clientApiKey,
                                                    Map<String, String> headers, UpstreamInfo upstream) throws IOException {
        String url = upstream.baseUrl + "/v1/chat/completions";
        long startTime = System.currentTimeMillis();
        String requestId = GatewayRequestContext.currentOrNewString();

        // 将 Claude 请求转换为 OpenAI 格式
        OpenAIRequest openAIRequest = claudeToOpenAIConverter.convertRequest(claudeRequestJson);
        String model = openAIRequest.getModel();
        String openAIRequestJson = objectMapper.writeValueAsString(openAIRequest);

        log.info("OpenAI-compatible request started: requestId={}, model={}, upstream={}, bodyBytes={}",
                requestId, model, upstream.name, utf8Length(openAIRequestJson));

        // 构建 HTTP 请求
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(openAIRequestJson, MediaType.parse("application/json")))
                .addHeader("Authorization", buildAuthorizationHeader(upstream.authToken))
                .addHeader("Content-Type", "application/json");

        // 添加自定义请求头（排除敏感字段）
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

                if (!response.isSuccessful()) {
                    if (attempt < maxAttempts) {
                        log.warn("⚠️ [{}] OpenAI attempt {}/{} failed with status {}, retrying after 3000ms...",
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
                        log.error("OpenAI-compatible request failed: requestId={}, attempts={}, status={}, "
                                        + "requestBytes={}, responseBytes={}",
                                requestId, maxAttempts, response.code(),
                                utf8Length(openAIRequestJson), utf8Length(responseBody));

                        trackApiUsage(clientApiKey, requestId, model, "/v1/chat/completions", "POST",
                                response.code(), null, duration, null, headers,
                                "OpenAI API error: status=" + response.code());

                        requestLogService.logRequest(requestId, maskApiKey(clientApiKey), "POST",
                                "/v1/chat/completions", model, headers, claudeRequestJson,
                                response.code(), responseBody, duration, TokenUsage.empty(),
                                "OpenAI API error");

                        throw new IOException("OpenAI API request failed with status " + response.code());
                    }
                }

                // Parse OpenAI response
                OpenAIResponse openAIResponse = objectMapper.readValue(responseBody, OpenAIResponse.class);

                // Convert to Claude format
                String claudeResponse = openAIToClaudeConverter.convertResponse(openAIResponse, model);

                // Extract token usage
                TokenUsage tokenUsage = openAIToClaudeConverter.convertUsage(openAIResponse.getUsage());
                logOpenAIUsageDiagnostics("non_stream_response", requestId, "/v1/chat/completions", model,
                        upstream.name, tokenUsage);

                openAIResponseLog.info("📥 [{}] Response: {} - Duration: {} ms, Tokens: {}/{}/{}, bodyBytes={}",
                        requestId, response.code(), duration,
                        tokenUsage.getInputTokens(), tokenUsage.getOutputTokens(), tokenUsage.getTotalTokens(),
                        utf8Length(claudeResponse));

                // Track usage (非流式: TTFT = null)
                trackApiUsage(clientApiKey, requestId, model, "/v1/chat/completions", "POST",
                        response.code(), claudeResponse, duration, null, headers, null);

                // Log request
                requestLogService.logRequest(requestId, maskApiKey(clientApiKey), "POST",
                        "/v1/chat/completions", model, headers, claudeRequestJson,
                        response.code(), claudeResponse, duration, tokenUsage, null);

                return claudeResponse;

            } catch (IOException e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    log.warn("OpenAI-compatible request attempt failed: requestId={}, attempt={}/{}, "
                                    + "errorType={}; retrying",
                            requestId, attempt, maxAttempts, errorType(e));
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Retry interrupted", ie);
                    }
                } else {
                    // Last attempt failed
                    long duration = System.currentTimeMillis() - startTime;
                    log.error("OpenAI-compatible request exhausted attempts: requestId={}, attempts={}, "
                                    + "errorType={}, durationMs={}",
                            requestId, maxAttempts, errorType(e), duration);

                    trackApiUsage(clientApiKey, requestId, model, "/v1/chat/completions", "POST",
                            500, null, duration, null, headers, errorType(e));

                    requestLogService.logRequest(requestId, maskApiKey(clientApiKey), "POST",
                            "/v1/chat/completions", model, headers, claudeRequestJson,
                            500, null, duration, TokenUsage.empty(), errorType(e));

                    throw e;
                }
            }
        }

        // Should never reach here, but required for compilation
        throw lastException != null ? lastException
                : new IOException("Unexpected error: retry loop exited without exception");
    }

    /**
     * Handle streaming request to OpenAI API
     *
     * @param claudeRequestJson Claude format request body
     * @param upstreamName      upstream name (optional)
     * @param clientApiKey      client API key for usage tracking
     * @param headers           request headers
     * @param emitter           SSE emitter for streaming response
     */
    public void handleStreamingRequest(String claudeRequestJson, String upstreamName,
                                       String clientApiKey, Map<String, String> headers,
                                       SseEmitter emitter, ai.nubase.common.enums.ApiProvider provider) {
        if (provider == null) {
            provider = ai.nubase.common.enums.ApiProvider.OPENAI;
        }
        String initialModel = extractModelFromRequest(claudeRequestJson);
        UpstreamInfo upstream = getUpstreamInfo(upstreamName, provider, initialModel);
        String url = upstream.baseUrl + "/v1/chat/completions";
        long startTime = System.currentTimeMillis();
        String requestId = GatewayRequestContext.currentOrNewString();

        try {
            // Convert Claude request to OpenAI format
            OpenAIRequest openAIRequest = claudeToOpenAIConverter.convertRequest(claudeRequestJson);
            String model = openAIRequest.getModel();

            // Ensure streaming is enabled
            openAIRequest.setStream(true);

            log.info("OpenAI-compatible streaming request started: requestId={}, model={}, upstream={}",
                    requestId, model, upstream.name);

            String openAIRequestJson = objectMapper.writeValueAsString(openAIRequest);

            // 流式状态的原子标志和累加器
            AtomicBoolean isFirstChunk = new AtomicBoolean(true);
            AtomicBoolean isLastChunk = new AtomicBoolean(false);
            AtomicBoolean hasToolCall = new AtomicBoolean(false);
            AtomicInteger contentBlockIndex = new AtomicInteger(0);
            AtomicReference<TokenUsage> accumulatedUsage = new AtomicReference<>(TokenUsage.empty());
            // TTFT: 首事件抵达时刻
            java.util.concurrent.atomic.AtomicLong firstEventAt = new java.util.concurrent.atomic.AtomicLong(0L);

            // Build HTTP request
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(openAIRequestJson, MediaType.parse("application/json")))
                    .addHeader("Authorization", buildAuthorizationHeader(upstream.authToken))
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

            Request request = requestBuilder.build();

            EventSourceListener listener = new EventSourceListener() {
                private volatile boolean streamCompleted = false;

                @Override
                public void onOpen(EventSource eventSource, Response response) {
                    log.info("✅ OpenAI SSE connection established, Request ID: {}", requestId);
                }

                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
                    if (streamCompleted) {
                        return;
                    }
                    firstEventAt.compareAndSet(0L, System.currentTimeMillis());

                    try {
                        // Check for [DONE] marker
                        if ("[DONE]".equals(data)) {
                            log.info("Received [DONE] marker");
                            if (!streamCompleted) {
                                streamCompleted = true;
                                completeStream(eventSource);
                            }
                            return;
                        }

                        // Parse OpenAI chunk
                        OpenAIStreamChunk chunk = objectMapper.readValue(data, OpenAIStreamChunk.class);

                        // Accumulate usage if present
                        if (chunk.getUsage() != null) {
                            TokenUsage usage = openAIToClaudeConverter.convertUsage(chunk.getUsage());
                            accumulatedUsage.set(usage);
                            logOpenAIUsageDiagnostics("stream_chunk", requestId, "/v1/chat/completions", model,
                                    upstream.name, usage);
                            openAIResponseLog.info("📊 [{}] OpenAI stream usage chunk - Tokens: {}/{}/{}",
                                    requestId,
                                    usage.getInputTokens(),
                                    usage.getOutputTokens(),
                                    usage.getTotalTokens());
                        }

                        // Convert to Claude SSE events
                        OpenAIToClaudeConverter.SseEvent[] claudeEvents = openAIToClaudeConverter.convertStreamChunk(
                                chunk, model, isFirstChunk, isLastChunk, hasToolCall, contentBlockIndex);

                        // Send events to client with proper SSE event type
                        for (OpenAIToClaudeConverter.SseEvent event : claudeEvents) {
                            emitter.send(SseEmitter.event()
                                    .name(event.eventType())
                                    .data(event.data()));
                        }

                        // Check if this was the last chunk
                        if (isLastChunk.get() && !streamCompleted) {
                            streamCompleted = true;
                            completeStream(eventSource);
                        }
                    } catch (Exception e) {
                        log.error("Error processing OpenAI SSE event: requestId={}, errorType={}",
                                requestId, errorType(e));
                        streamCompleted = true;
                        emitter.completeWithError(e);
                        eventSource.cancel();
                    }
                }

                @Override
                public void onClosed(EventSource eventSource) {
                    log.info("🔒 OpenAI SSE connection closed, Request ID: {}", requestId);
                    if (!streamCompleted) {
                        streamCompleted = true;
                        emitter.complete();
                    }
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    if (streamCompleted) {
                        return;
                    }

                    streamCompleted = true;
                    String errorMsg = t != null ? errorType(t) : "SSE connection closed unexpectedly";
                    log.error("❌ OpenAI SSE connection failed, Request ID: {}, Error: {}",
                            requestId, errorMsg);

                    long duration = System.currentTimeMillis() - startTime;
                    int statusCode = response != null ? response.code() : 500;
                    String errorBody = null;

                    if (response != null && response.body() != null) {
                        try {
                            errorBody = response.body().string();
                            log.error("OpenAI SSE error response: requestId={}, status={}, responseBytes={}",
                                    requestId, response.code(), utf8Length(errorBody));

                            // Send error event to client
                            String errorEvent = String.format(
                                    "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"%s\"}}",
                                    errorBody.replace("\"", "\\\""));
                            emitter.send(SseEmitter.event().name("error").data(errorEvent));
                        } catch (IOException e) {
                            log.error("Unable to read or send OpenAI SSE error response: requestId={}, errorType={}",
                                    requestId, errorType(e));
                        }
                    }

                    long fea = firstEventAt.get();
                    Long ttftErr = fea > 0L ? fea - startTime : null;
                    trackApiUsage(clientApiKey, requestId, model, "/v1/chat/completions", "POST",
                            statusCode, null, duration, ttftErr, headers, errorMsg);

                    requestLogService.logRequest(requestId, maskApiKey(clientApiKey), "POST",
                            "/v1/chat/completions", model, headers, claudeRequestJson,
                            statusCode, errorBody, duration, TokenUsage.empty(), errorMsg);

                    Throwable error = t != null ? t : new IOException("OpenAI SSE failed with status " + statusCode);
                    emitter.completeWithError(error);
                    eventSource.cancel();
                }

                private void completeStream(EventSource eventSource) {
                    long duration = System.currentTimeMillis() - startTime;
                    TokenUsage finalUsage = accumulatedUsage.get();

                    log.info("🏁 [{}] Streaming response completed - Duration: {} ms, Tokens: {}/{}/{}",
                            requestId, duration,
                            finalUsage.getInputTokens(),
                            finalUsage.getOutputTokens(),
                            finalUsage.getTotalTokens());
                    log.info("api_usage.openai_proxy_stream.final requestId={} endpoint={} model={} upstream={} tokens input={} output={} total={} cacheCreation={} cacheRead={}",
                            requestId, "/v1/chat/completions", model, upstream.name,
                            finalUsage.getInputTokens(),
                            finalUsage.getOutputTokens(),
                            finalUsage.getTotalTokens(),
                            finalUsage.getCacheCreationInputTokens(),
                            finalUsage.getCacheReadInputTokens());
                    if (finalUsage.getInputTokens() == 0 && finalUsage.getOutputTokens() == 0) {
                        log.warn("api_usage.zero_tokens_on_openai_stream requestId={} endpoint={} model={} upstream={} - no usage chunk observed from upstream",
                                requestId, "/v1/chat/completions", model, upstream.name);
                    }

                    long feaOk = firstEventAt.get();
                    Long ttftOk = feaOk > 0L ? feaOk - startTime : null;
                    trackApiUsageWithTokenUsage(clientApiKey, requestId, model, "/v1/chat/completions", "POST",
                            200, finalUsage, duration, ttftOk, headers, null);

                    requestLogService.logRequest(requestId, maskApiKey(clientApiKey), "POST",
                            "/v1/chat/completions", model, headers, claudeRequestJson,
                            200, "{\"type\":\"message_stream\",\"status\":\"completed\"}",
                            duration, finalUsage, null);

                    emitter.complete();
                    eventSource.cancel();
                }
            };

            // Create EventSource
            EventSource eventSource = streamingHttpClientProvider.newEventSource(
                    request,
                    listener,
                    openAIConfig.getTimeout(),
                    openAIConfig.getTimeout(),
                    openAIConfig.getTimeout(),
                    requestId,
                    "openai-compatible",
                    upstream.name);

            // Handle emitter lifecycle
            emitter.onTimeout(() -> {
                log.warn("⏱️ SSE emitter timeout, Request ID: {}", requestId);
                long duration = System.currentTimeMillis() - startTime;

                trackApiUsage(clientApiKey, requestId, model, "/v1/chat/completions", "POST",
                        408, null, duration, null, headers, "Request timeout");

                eventSource.cancel();
                emitter.complete();
            });

            emitter.onCompletion(() -> {
                log.info("✅ SSE emitter completed, Request ID: {}", requestId);
                eventSource.cancel();
            });

            emitter.onError((e) -> {
                log.error("OpenAI SSE emitter error: requestId={}, errorType={}", requestId, errorType(e));
                eventSource.cancel();
            });

        } catch (Exception e) {
            log.error("Failed to initiate OpenAI streaming request: requestId={}, errorType={}",
                    requestId, errorType(e));
            emitter.completeWithError(e);
        }
    }

    /**
     * Build Authorization header for OpenAI API
     * Adds "Bearer " prefix if not present
     */
    private String buildAuthorizationHeader(String authToken) {
        if (authToken == null || authToken.isEmpty()) {
            throw new IllegalArgumentException("OpenAI auth token is missing");
        }

        // Add Bearer prefix if not already present
        if (authToken.toLowerCase().startsWith("bearer ")) {
            return authToken;
        } else {
            return "Bearer " + authToken;
        }
    }

    /**
     * Track API usage
     */
    private void trackApiUsage(String apiKey, String requestId, String model, String endpoint,
                               String method, int statusCode, String responseBody,
                               long durationMs, Long firstTokenLatencyMs,
                               Map<String, String> headers, String errorMessage) {
        try {
            TokenUsage tokenUsage = TokenUsage.empty();
            if (responseBody != null) {
                tokenUsage = usageTrackingService.extractTokenUsage(responseBody);
            }
            trackApiUsageWithTokenUsage(apiKey, requestId, model, endpoint, method, statusCode,
                    tokenUsage, durationMs, firstTokenLatencyMs, headers, errorMessage);
        } catch (Exception e) {
            log.error("Failed to track API usage: requestId={}, errorType={}", requestId, errorType(e));
        }
    }

    private void trackApiUsageWithTokenUsage(String apiKey, String requestId, String model, String endpoint,
                                             String method, int statusCode, TokenUsage tokenUsage,
                                             long durationMs, Long firstTokenLatencyMs,
                                             Map<String, String> headers, String errorMessage) {
        try {
            if (tokenUsage == null) {
                tokenUsage = TokenUsage.empty();
            }
            log.info("api_usage.openai_proxy.track requestId={} endpoint={} model={} status={} tokens input={} output={} total={} cacheCreation={} cacheRead={} ttftMs={} error={}",
                    requestId, endpoint, model, statusCode,
                    tokenUsage.getInputTokens(),
                    tokenUsage.getOutputTokens(),
                    tokenUsage.getTotalTokens(),
                    tokenUsage.getCacheCreationInputTokens(),
                    tokenUsage.getCacheReadInputTokens(),
                    firstTokenLatencyMs,
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
                    .firstTokenLatencyMs(firstTokenLatencyMs)
                    .errorMessage(errorMessage)
                    .requestMetadata(usageTrackingService.createRequestMetadata(
                            headers != null ? headers.get("user-agent") : null, headers))
                    .build();

            usageTrackingService.trackUsage(record);
        } catch (Exception e) {
            log.error("Failed to track API usage: requestId={}, errorType={}", requestId, errorType(e));
        }
    }

    private void logOpenAIUsageDiagnostics(String phase, String requestId, String endpoint, String model,
                                           String upstream, TokenUsage tokenUsage) {
        if (tokenUsage == null) {
            return;
        }
        boolean empty = tokenUsage.getInputTokens() == 0
                && tokenUsage.getOutputTokens() == 0
                && tokenUsage.getCacheCreationInputTokens() == 0
                && tokenUsage.getCacheReadInputTokens() == 0;
        log.info("api_usage.openai_proxy.parsed phase={} requestId={} endpoint={} model={} upstream={} input={} output={} total={} cacheCreation={} cacheRead={} empty={}",
                phase, requestId, endpoint, model, upstream,
                tokenUsage.getInputTokens(),
                tokenUsage.getOutputTokens(),
                tokenUsage.getTotalTokens(),
                tokenUsage.getCacheCreationInputTokens(),
                tokenUsage.getCacheReadInputTokens(),
                empty);
    }

    /**
     * Mask API key for logging
     */
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }

    private String extractModelFromRequest(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(requestJson);
            if (root.has("model") && !root.get("model").isNull()) {
                return root.get("model").asText();
            }
        } catch (Exception e) {
            log.debug("Unable to extract model from OpenAI-compatible request: errorType={}", errorType(e));
        }
        return null;
    }

    static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static String errorType(Throwable throwable) {
        return throwable == null ? "Unknown" : throwable.getClass().getSimpleName();
    }

}
