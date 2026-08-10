package ai.nubase.ai.gateway.service;

import ai.nubase.ai.gateway.billing.GatewayRequestContext;
import ai.nubase.ai.gateway.dto.ApiUsageRecord;
import ai.nubase.ai.gateway.dto.TokenUsage;
import ai.nubase.ai.gateway.dto.VideoGenerationRequest;
import ai.nubase.ai.gateway.dto.VideoOperationFetchRequest;
import ai.nubase.ai.gateway.platform.GatewayRoutingContext;
import ai.nubase.ai.gateway.util.GatewayKeyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class VideoGenerationService {

    public static final String SEEDANCE_MODEL = VideoGenerationRequest.SEEDANCE_MODEL;
    public static final String ZENMUX_UPSTREAM_NAME = "zenmux";
    private static final String LEGACY_ZENMUX_UPSTREAM_NAME = "zenmux-openai-api";

    private static final String MODEL_RESOURCE = "publishers/bytedance/models/doubao-seedance-2.0";
    private static final String CREATE_PATH = "/ai/v1/videos/generations";
    private static final String FETCH_PATH = "/ai/v1/videos/operations:fetch";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long MAX_CALL_TIMEOUT_MS = 300_000L;
    private static final Set<String> RESOLUTIONS = Set.of("480p", "720p");
    private static final Set<String> ASPECT_RATIOS = Set.of("21:9", "16:9", "4:3", "1:1", "3:4", "9:16");
    private static final Map<String, String> PARAMETER_ALIASES = Map.ofEntries(
            Map.entry("number_of_videos", "sampleCount"),
            Map.entry("numberOfVideos", "sampleCount"),
            Map.entry("output_gcs_uri", "storageUri"),
            Map.entry("outputGcsUri", "storageUri"),
            Map.entry("duration_seconds", "durationSeconds"),
            Map.entry("aspect_ratio", "aspectRatio"),
            Map.entry("person_generation", "personGeneration"),
            Map.entry("negative_prompt", "negativePrompt"),
            Map.entry("enhance_prompt", "enhancePrompt"),
            Map.entry("generate_audio", "generateAudio"),
            Map.entry("compression_quality", "compressionQuality"),
            Map.entry("resize_mode", "resizeMode"));

    private final ObjectMapper objectMapper;
    private final ApiUsageTrackingService usageTrackingService;
    private final ApiRequestLogService requestLogService;
    private final String zenmuxApiKey;
    private final String zenmuxBaseUrl;
    private final int zenmuxVideoTimeoutMs;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(MAX_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(MAX_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build();

    public VideoGenerationService(
            ObjectMapper objectMapper,
            ApiUsageTrackingService usageTrackingService,
            ApiRequestLogService requestLogService,
            @Value("${zenmux.api-key:}") String zenmuxApiKey,
            @Value("${zenmux.base-url:https://zenmux.ai/api/vertex-ai}") String zenmuxBaseUrl,
            @Value("${zenmux.video-timeout-ms:300000}") int zenmuxVideoTimeoutMs) {
        this.objectMapper = objectMapper;
        this.usageTrackingService = usageTrackingService;
        this.requestLogService = requestLogService;
        this.zenmuxApiKey = zenmuxApiKey;
        this.zenmuxBaseUrl = zenmuxBaseUrl;
        this.zenmuxVideoTimeoutMs = zenmuxVideoTimeoutMs;
    }

    public JsonNode submit(
            VideoGenerationRequest request,
            String clientApiKey,
            Map<String, String> headers,
            String upstreamName) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        request.normalizeAndValidate();
        ObjectNode body = buildGenerationBody(request);
        validateGenerationBody(body);
        ResolvedUpstream upstream = resolveUpstream(upstreamName);
        String url = buildVertexBaseUrl(upstream.baseUrl())
                + "/v1/" + MODEL_RESOURCE + ":predictLongRunning";
        return executeJson(CREATE_PATH, url, body, clientApiKey, headers, upstream, "POST");
    }

    public JsonNode fetch(
            VideoOperationFetchRequest request,
            String clientApiKey,
            Map<String, String> headers,
            String headerUpstreamName) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String operationName = validateOperationName(request.resolveOperationName());
        String upstreamName = resolveRequestedUpstream(request.getUpstream(), headerUpstreamName);
        ResolvedUpstream upstream = resolveUpstream(upstreamName);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("operationName", operationName);
        String operationResource = operationName.substring(0, operationName.lastIndexOf("/operations/"));
        String url = buildVertexBaseUrl(upstream.baseUrl())
                + "/v1/" + operationResource + ":fetchPredictOperation";
        return executeJson(FETCH_PATH, url, body, clientApiKey, headers, upstream, "POST");
    }

    private ObjectNode buildGenerationBody(VideoGenerationRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        Map<String, Object> config = request.getConfig() == null
                ? new HashMap<>()
                : new HashMap<>(request.getConfig());

        if (request.hasRawInstances()) {
            body.set("instances", objectMapper.valueToTree(request.getInstances()));
            removeFirst(config, "image");
            removeFirst(config, "video");
            removeFirst(config, "last_frame", "lastFrame");
            removeFirst(config, "reference_images", "referenceImages");
        } else {
            ArrayNode instances = body.putArray("instances");
            ObjectNode instance = instances.addObject();
            if (request.getPrompt() != null) {
                instance.put("prompt", request.getPrompt());
            }
            boolean hasImage = putMediaInput(instance, "image", request.getImage());
            boolean hasVideo = putMediaInput(instance, "video", request.getVideo());
            boolean hasLastFrame = putMediaInput(instance, "lastFrame", request.getLastFrame());
            boolean hasReferences = putReferenceImages(instance, request.getReferenceImages());
            if (!hasImage) {
                putMediaInput(instance, "image", mediaInputFromValue(removeFirst(config, "image")));
            } else {
                removeFirst(config, "image");
            }
            if (!hasVideo) {
                putMediaInput(instance, "video", mediaInputFromValue(removeFirst(config, "video")));
            } else {
                removeFirst(config, "video");
            }
            if (!hasLastFrame) {
                putMediaInput(instance, "lastFrame",
                        mediaInputFromValue(removeFirst(config, "last_frame", "lastFrame")));
            } else {
                removeFirst(config, "last_frame", "lastFrame");
            }
            if (!hasReferences) {
                putReferenceImages(instance,
                        removeFirst(config, "reference_images", "referenceImages"));
            } else {
                removeFirst(config, "reference_images", "referenceImages");
            }
        }

        Object webhookConfig = removeFirst(config, "webhook_config", "webhookConfig");
        if (webhookConfig != null) {
            throw new IllegalArgumentException("webhook_config is not supported");
        }
        Object labels = removeFirst(config, "labels");
        if (labels != null) {
            body.set("labels", objectMapper.valueToTree(labels));
        }

        ObjectNode parameters = objectMapper.createObjectNode();
        Object nestedParameters = removeFirst(config, "parameters");
        if (nestedParameters instanceof Map<?, ?> nestedMap) {
            putMap(parameters, nestedMap);
        }
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if (entry.getValue() == null || isIgnoredConfigKey(entry.getKey())) {
                continue;
            }
            String key = PARAMETER_ALIASES.getOrDefault(entry.getKey(), entry.getKey());
            parameters.set(key, objectMapper.valueToTree(entry.getValue()));
        }
        if (request.getParameters() != null) {
            putMap(parameters, request.getParameters());
        }
        removeSmartAspectRatio(parameters);
        if (!parameters.isEmpty()) {
            body.set("parameters", parameters);
        }
        return body;
    }

    private void validateGenerationBody(ObjectNode body) {
        JsonNode parameters = body.path("parameters");
        Integer durationSeconds = optionalInteger(parameters, "durationSeconds");
        if (durationSeconds != null && (durationSeconds < 4 || durationSeconds > 15)) {
            throw new IllegalArgumentException("durationSeconds must be between 4 and 15 for " + SEEDANCE_MODEL);
        }
        String resolution = optionalText(parameters, "resolution");
        if (resolution != null && !RESOLUTIONS.contains(resolution.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("resolution must be one of [480p, 720p] for " + SEEDANCE_MODEL);
        }
        String aspectRatio = optionalText(parameters, "aspectRatio");
        if (aspectRatio != null && !ASPECT_RATIOS.contains(aspectRatio)) {
            throw new IllegalArgumentException(
                    "aspectRatio must be one of [21:9, 16:9, 4:3, 1:1, 3:4, 9:16] or smart for "
                            + SEEDANCE_MODEL);
        }
    }

    private JsonNode executeJson(
            String publicPath,
            String url,
            JsonNode requestBody,
            String clientApiKey,
            Map<String, String> headers,
            ResolvedUpstream upstream,
            String method) throws IOException {
        String requestId = GatewayRequestContext.currentOrNewString();
        String requestJson = objectMapper.writeValueAsString(requestBody);
        long startedAt = System.currentTimeMillis();
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestJson, JSON))
                .header("Authorization", authorization(upstream.authToken()))
                .header("Content-Type", "application/json");
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (value != null
                        && !name.equalsIgnoreCase("Authorization")
                        && !name.equalsIgnoreCase("x-api-key")
                        && !name.equalsIgnoreCase("x-upstream")) {
                    builder.header(name, value);
                }
            });
        }

        Call call = httpClient.newCall(builder.build());
        call.timeout().timeout(Math.min(MAX_CALL_TIMEOUT_MS, Math.max(1, upstream.timeoutMs())),
                TimeUnit.MILLISECONDS);
        boolean recorded = false;
        int upstreamStatus = 0;
        int responseBodyBytes = 0;
        String responseBody = null;
        try (Response response = call.execute()) {
            long durationMs = System.currentTimeMillis() - startedAt;
            upstreamStatus = response.code();
            byte[] responseBytes = response.body() == null ? new byte[0] : response.body().bytes();
            responseBodyBytes = responseBytes.length;
            responseBody = new String(responseBytes, StandardCharsets.UTF_8);
            if (!response.isSuccessful()) {
                UpstreamHttpException upstreamException =
                        new UpstreamHttpException(upstreamStatus, responseBodyBytes);
                recordCall(publicPath, method, requestId, clientApiKey, headers, requestJson,
                        upstreamStatus, responseBody, durationMs, upstream, upstreamException.safeSummary());
                recorded = true;
                throw upstreamException;
            }

            JsonNode parsed = objectMapper.readTree(responseBody);
            if (!(parsed instanceof ObjectNode objectResponse)) {
                throw new UpstreamHttpException(upstreamStatus, responseBodyBytes);
            }
            if (CREATE_PATH.equals(publicPath) && textOrNull(objectResponse.path("name")) == null) {
                throw new UpstreamHttpException(upstreamStatus, responseBodyBytes);
            }
            objectResponse.put("model", SEEDANCE_MODEL);
            objectResponse.put("upstream", upstream.name());
            String enrichedResponse = objectMapper.writeValueAsString(objectResponse);
            recordCall(publicPath, method, requestId, clientApiKey, headers, requestJson,
                    response.code(), enrichedResponse, durationMs, upstream, null);
            recorded = true;
            return objectResponse;
        } catch (IOException exception) {
            UpstreamHttpException safeException = exception instanceof UpstreamHttpException upstreamException
                    ? upstreamException
                    : new UpstreamHttpException(upstreamStatus, responseBodyBytes);
            if (!recorded) {
                long durationMs = System.currentTimeMillis() - startedAt;
                recordCall(publicPath, method, requestId, clientApiKey, headers, requestJson,
                        502, responseBody, durationMs, upstream, safeException.safeSummary());
            }
            throw safeException;
        }
    }

    private void recordCall(
            String publicPath,
            String method,
            String requestId,
            String clientApiKey,
            Map<String, String> headers,
            String requestBody,
            int statusCode,
            String responseBody,
            long durationMs,
            ResolvedUpstream upstream,
            String errorMessage) {
        TokenUsage usage = extractUsage(responseBody);
        try {
            ApiUsageRecord record = ApiUsageRecord.builder()
                    .apiKey(clientApiKey)
                    .requestId(requestId)
                    .model(SEEDANCE_MODEL)
                    .endpoint(publicPath)
                    .method(method)
                    .statusCode(statusCode)
                    .tokenUsage(usage)
                    .durationMs(durationMs)
                    .errorMessage(errorMessage)
                    .requestMetadata(usageTrackingService.createRequestMetadata(
                            header(headers, "user-agent"), headers == null ? Map.of() : headers))
                    .build();
            usageTrackingService.trackUsage(record);
        } catch (Exception exception) {
            log.error("Failed to track video usage for requestId={}: type={}",
                    requestId, exceptionType(exception));
        }
        try {
            requestLogService.logRequest(
                    requestId,
                    GatewayKeyUtil.displayPrefix(clientApiKey),
                    method,
                    publicPath,
                    SEEDANCE_MODEL,
                    headers == null ? Map.of() : headers,
                    sanitizeMediaJson(requestBody),
                    statusCode,
                    sanitizeMediaJson(responseBody),
                    durationMs,
                    usage,
                    errorMessage);
        } catch (Exception exception) {
            log.error("Failed to persist video request log for requestId={}: type={}",
                    requestId, exceptionType(exception));
        }
        log.info("Video gateway call completed: requestId={}, endpoint={}, upstream={}, status={}, durationMs={}",
                requestId, publicPath, upstream.name(), statusCode, durationMs);
    }

    private ResolvedUpstream resolveUpstream(String requestedName) throws IOException {
        String requested = textOrNull(requestedName);
        if (requested != null
                && !ZENMUX_UPSTREAM_NAME.equals(requested)
                && !LEGACY_ZENMUX_UPSTREAM_NAME.equals(requested)) {
            throw new IllegalArgumentException(
                    "only ZenMux upstream is supported");
        }

        if (zenmuxApiKey == null || zenmuxApiKey.isBlank()) {
            throw new IOException("Zenmux API key is not configured");
        }
        if (zenmuxBaseUrl == null || zenmuxBaseUrl.isBlank()) {
            throw new IOException("Zenmux base URL is not configured");
        }
        GatewayRoutingContext.set(GatewayRoutingContext.Source.PLATFORM, ZENMUX_UPSTREAM_NAME);
        return new ResolvedUpstream(
                ZENMUX_UPSTREAM_NAME,
                zenmuxBaseUrl.trim(),
                zenmuxApiKey.trim(),
                Math.max(1, zenmuxVideoTimeoutMs));
    }

    private String buildVertexBaseUrl(String baseUrl) throws IOException {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IOException("video upstream base_url is missing");
        }
        String normalized = removeTrailingSlashes(baseUrl.trim());
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/vertex-ai")) {
            return normalized;
        }
        if (lower.endsWith("/api")) {
            return normalized + "/vertex-ai";
        }
        if (lower.equals("https://zenmux.ai") || lower.equals("http://zenmux.ai")) {
            return normalized + "/api/vertex-ai";
        }
        return normalized;
    }

    private String validateOperationName(String operationName) {
        String normalized = trimSlashes(operationName);
        if (normalized.contains("..")
                || normalized.contains("?")
                || normalized.contains("&")
                || normalized.startsWith("http://")
                || normalized.startsWith("https://")) {
            throw new IllegalArgumentException("invalid operation_name");
        }
        String marker = "/operations/";
        int markerIndex = normalized.lastIndexOf(marker);
        if (markerIndex <= 0 || !normalized.substring(0, markerIndex).endsWith(MODEL_RESOURCE)) {
            throw new IllegalArgumentException("operation_name must belong to " + SEEDANCE_MODEL);
        }
        String operationId = normalized.substring(markerIndex + marker.length());
        if (!operationId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid operation_name");
        }
        return normalized;
    }

    private boolean putMediaInput(ObjectNode target, String fieldName, VideoGenerationRequest.MediaInput input) {
        ObjectNode media = mediaInputNode(input);
        if (media == null || media.isEmpty()) {
            return false;
        }
        target.set(fieldName, media);
        return true;
    }

    private boolean putReferenceImages(ObjectNode target, Object rawReferences) {
        if (rawReferences == null) {
            return false;
        }
        JsonNode raw = objectMapper.valueToTree(rawReferences);
        ArrayNode normalized = objectMapper.createArrayNode();
        if (raw.isArray()) {
            raw.forEach(item -> addReferenceImage(normalized, item));
        } else {
            addReferenceImage(normalized, raw);
        }
        if (normalized.isEmpty()) {
            return false;
        }
        target.set("referenceImages", normalized);
        return true;
    }

    private void addReferenceImage(ArrayNode target, JsonNode rawReference) {
        if (rawReference == null || !rawReference.isObject()) {
            return;
        }
        JsonNode imageSource = firstExisting(rawReference, "image", "referenceImage");
        VideoGenerationRequest.MediaInput mediaInput = mediaInputFromValue(
                imageSource == null ? rawReference : imageSource);
        ObjectNode image = mediaInputNode(mediaInput);
        if (image == null || image.isEmpty()) {
            return;
        }
        ObjectNode reference = target.addObject();
        reference.set("image", image);
        JsonNode referenceId = firstExisting(rawReference, "referenceId", "reference_id");
        if (referenceId != null) {
            reference.set("referenceId", referenceId);
        }
        String referenceType = firstText(rawReference, "referenceType", "reference_type");
        reference.put("referenceType", referenceType == null ? "asset" : referenceType);
    }

    private VideoGenerationRequest.MediaInput mediaInputFromValue(Object value) {
        if (value == null) {
            return null;
        }
        return objectMapper.convertValue(value, VideoGenerationRequest.MediaInput.class);
    }

    private ObjectNode mediaInputNode(VideoGenerationRequest.MediaInput input) {
        if (input == null || !input.hasValue()) {
            return null;
        }
        ObjectNode media = objectMapper.createObjectNode();
        if (input.getData() != null && !input.getData().isBlank()) {
            media.put("bytesBase64Encoded", input.getData().trim());
        }
        if (input.getUri() != null && !input.getUri().isBlank()) {
            media.put("gcsUri", input.getUri().trim());
        }
        if (input.getMimeType() != null && !input.getMimeType().isBlank()) {
            media.put("mimeType", input.getMimeType().trim());
        }
        return media;
    }

    private void putMap(ObjectNode target, Map<?, ?> values) {
        values.forEach((key, value) -> {
            if (key != null && value != null) {
                target.set(key.toString(), objectMapper.valueToTree(value));
            }
        });
    }

    private void removeSmartAspectRatio(ObjectNode parameters) {
        JsonNode aspectRatio = parameters.path("aspectRatio");
        if (aspectRatio.isTextual() && "smart".equalsIgnoreCase(aspectRatio.asText().trim())) {
            parameters.remove("aspectRatio");
        }
    }

    private boolean isIgnoredConfigKey(String key) {
        return "http_options".equals(key)
                || "httpOptions".equals(key)
                || "mode".equals(key)
                || "generationMode".equals(key);
    }

    private Object removeFirst(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                return source.remove(key);
            }
        }
        return null;
    }

    private Integer optionalInteger(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.asInt();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText().trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(fieldName + " must be an integer");
            }
        }
        throw new IllegalArgumentException(fieldName + " must be an integer");
    }

    private String optionalText(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(fieldName + " must be a string");
        }
        return textOrNull(value);
    }

    private JsonNode firstExisting(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... fields) {
        JsonNode value = firstExisting(node, fields);
        return value == null ? null : textOrNull(value);
    }

    private TokenUsage extractUsage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return TokenUsage.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode usage = root.path("usageMetadata");
            if (usage.isMissingNode() || usage.isNull()) {
                usage = root.path("usage");
            }
            int input = firstInt(usage, "promptTokenCount", "input_tokens", "prompt_tokens");
            int output = firstInt(usage, "candidatesTokenCount", "output_tokens", "completion_tokens");
            int total = firstInt(usage, "totalTokenCount", "total_tokens");
            return TokenUsage.builder()
                    .inputTokens(input)
                    .outputTokens(output)
                    .totalTokens(total > 0 ? total : input + output)
                    .cacheCreationInputTokens(0)
                    .cacheReadInputTokens(0)
                    .build();
        } catch (Exception ignored) {
            return TokenUsage.empty();
        }
    }

    private int firstInt(JsonNode node, String... fields) {
        if (node == null) {
            return 0;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.asInt();
            }
        }
        return 0;
    }

    private String sanitizeMediaJson(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            redactMedia(root, false);
            return objectMapper.writeValueAsString(root);
        } catch (Exception ignored) {
            return "{\"media_payload\":\"redacted\"}";
        }
    }

    private void redactMedia(JsonNode node, boolean mediaContext) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> redactMedia(child, mediaContext));
            return;
        }
        if (!(node instanceof ObjectNode objectNode)) {
            return;
        }
        List<String> fields = new ArrayList<>();
        objectNode.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            JsonNode value = objectNode.get(field);
            String normalized = field.toLowerCase(Locale.ROOT);
            if (isMediaPayloadField(normalized) || (mediaContext && normalized.equals("data"))) {
                int length = value != null && value.isTextual() ? value.asText().length() : 0;
                objectNode.put(field, "<redacted:" + length + " chars>");
            } else if (isMediaUrlField(normalized)) {
                objectNode.put(field, "<redacted-url>");
            } else {
                redactMedia(value, mediaContext || isMediaContainerField(normalized));
            }
        }
    }

    private boolean isMediaPayloadField(String field) {
        return field.equals("base64")
                || field.equals("b64_json")
                || field.equals("bytesbase64encoded")
                || field.equals("bytes_base64_encoded")
                || field.equals("imagebytes")
                || field.equals("image_bytes")
                || field.equals("videobytes")
                || field.equals("video_bytes")
                || field.equals("videobase64")
                || field.equals("video_base64");
    }

    private boolean isMediaUrlField(String field) {
        return field.equals("url")
                || field.equals("uri")
                || field.equals("gcsuri")
                || field.equals("gcs_uri")
                || field.equals("fileuri")
                || field.equals("file_uri")
                || field.equals("download_url")
                || field.equals("downloadurl");
    }

    private boolean isMediaContainerField(String field) {
        return field.contains("image")
                || field.contains("video")
                || field.contains("frame")
                || field.equals("inlinedata")
                || field.equals("inline_data");
    }

    private String authorization(String token) throws IOException {
        if (token == null || token.isBlank()) {
            throw new IOException("video upstream auth token is missing");
        }
        return token.regionMatches(true, 0, "Bearer ", 0, 7) ? token : "Bearer " + token;
    }

    private String resolveRequestedUpstream(String bodyValue, String headerValue) {
        String body = textOrNull(bodyValue);
        String header = textOrNull(headerValue);
        if (body != null && header != null && !body.equals(header)) {
            throw new IllegalArgumentException("request upstream does not match x-upstream header");
        }
        return body != null ? body : header;
    }

    private String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private String textOrNull(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null
                : value.asText().trim();
    }

    private String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String removeTrailingSlashes(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String trimSlashes(String value) {
        String result = value.trim();
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String exceptionType(Exception exception) {
        String type = exception.getClass().getSimpleName();
        return type.isBlank() ? Exception.class.getSimpleName() : type;
    }

    private record ResolvedUpstream(
            String name,
            String baseUrl,
            String authToken,
            int timeoutMs) {
    }

    public static final class UpstreamHttpException extends IOException {

        private final Integer statusCode;
        private final int bodyBytes;

        public UpstreamHttpException(int statusCode, int bodyBytes) {
            super(safeSummary(statusCode > 0 ? statusCode : null, bodyBytes));
            this.statusCode = statusCode > 0 ? statusCode : null;
            this.bodyBytes = bodyBytes;
        }

        public Integer getStatusCode() {
            return statusCode;
        }

        public int getBodyBytes() {
            return bodyBytes;
        }

        public String safeSummary() {
            return safeSummary(statusCode, bodyBytes);
        }

        private static String safeSummary(Integer statusCode, int bodyBytes) {
            return "Video upstream request failed: status="
                    + (statusCode == null ? "unavailable" : statusCode)
                    + ", bodyBytes=" + bodyBytes;
        }
    }
}
