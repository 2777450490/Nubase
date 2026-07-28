package ai.nubase.ai.gateway.service.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Component
public class ZenmuxOpenAiImageClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String CANONICAL_MODEL = "openai/gpt-image-2";

    private final String apiKey;
    private final String baseUrl;
    private final String apiVersion;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public ZenmuxOpenAiImageClient(
            @Value("${zenmux.api-key:}") String apiKey,
            @Value("${zenmux.base-url:https://zenmux.ai/api/vertex-ai}") String baseUrl,
            @Value("${zenmux.api-version:v1}") String apiVersion,
            @Value("${zenmux.image-timeout-ms:180000}") int imageTimeoutMs,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.apiVersion = apiVersion;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(imageTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(Math.min(imageTimeoutMs, 60_000), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public ImagePredictResult generateImages(
            String prompt,
            Map<String, Object> config) throws IOException {
        ObjectNode requestBody = buildImagePredictBody(prompt, null, config, false);
        return executePredict(requestBody);
    }

    public ImagePredictResult editImage(
            String prompt,
            List<ImageReference> referenceImages,
            Map<String, Object> config) throws IOException {
        ObjectNode requestBody = buildImagePredictBody(prompt, referenceImages, config, true);
        return executePredict(requestBody);
    }

    private ImagePredictResult executePredict(ObjectNode requestBody) throws IOException {
        String token = apiKey == null ? "" : apiKey.trim();
        if (token.isEmpty()) {
            throw new IOException("Zenmux API key is not configured");
        }

        String bodyJson = objectMapper.writeValueAsString(requestBody);
        Request request = new Request.Builder()
                .url(buildPredictUrl())
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(bodyJson, JSON_MEDIA_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "{}";
            if (!response.isSuccessful()) {
                throw new IOException("Zenmux image API error [" + response.code() + "]: " + responseBody);
            }
            return parseImagePredictResult(objectMapper.readTree(responseBody));
        }
    }

    private ObjectNode buildImagePredictBody(
            String prompt,
            List<ImageReference> referenceImages,
            Map<String, Object> config,
            boolean editMode) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        ArrayNode instances = requestBody.putArray("instances");
        ObjectNode instance = instances.addObject();
        instance.put("prompt", Objects.requireNonNull(prompt, "prompt is required"));

        if (referenceImages != null && !referenceImages.isEmpty()) {
            ArrayNode referenceImageNodes = instance.putArray("referenceImages");
            int fallbackReferenceId = 1;
            for (ImageReference referenceImage : referenceImages) {
                ObjectNode referenceNode = referenceImageNodes.addObject();
                referenceNode.put(
                        "referenceId",
                        referenceImage.referenceId() != null
                                ? referenceImage.referenceId()
                                : fallbackReferenceId);
                referenceNode.put(
                        "referenceType",
                        firstNonBlank(referenceImage.referenceType(), "REFERENCE_TYPE_RAW"));

                ObjectNode imageNode = referenceNode.putObject("referenceImage");
                putImageInput(
                        imageNode,
                        referenceImage.data(),
                        referenceImage.uri(),
                        referenceImage.mimeType());
                fallbackReferenceId++;
            }
        }

        ObjectNode parameters = objectMapper.createObjectNode();
        copyImageConfig(requestBody, parameters, config, editMode);
        if (!parameters.isEmpty()) {
            requestBody.set("parameters", parameters);
        }
        return requestBody;
    }

    private void copyImageConfig(
            ObjectNode requestBody,
            ObjectNode parameters,
            Map<String, Object> config,
            boolean editMode) {
        if (config == null || config.isEmpty()) {
            return;
        }

        Map<String, Object> normalizedConfig = new HashMap<>(config);
        Object labels = removeFirst(normalizedConfig, "labels");
        if (labels != null) {
            requestBody.set("labels", objectMapper.valueToTree(labels));
        }

        putParameter(parameters, normalizedConfig, "storageUri",
                "output_gcs_uri", "outputGcsUri", "storageUri");
        putParameter(parameters, normalizedConfig, "negativePrompt",
                "negative_prompt", "negativePrompt");
        putParameter(parameters, normalizedConfig, "sampleCount",
                "n", "number_of_images", "numberOfImages", "sampleCount");
        putParameter(parameters, normalizedConfig, "aspectRatio",
                "aspect_ratio", "aspectRatio");
        putParameter(parameters, normalizedConfig, "guidanceScale",
                "guidance_scale", "guidanceScale");
        putParameter(parameters, normalizedConfig, "seed", "seed");
        putParameter(parameters, normalizedConfig, "safetySetting",
                "safety_filter_level", "safetyFilterLevel", "safetySetting");
        putParameter(parameters, normalizedConfig, "personGeneration",
                "person_generation", "personGeneration");
        putParameter(parameters, normalizedConfig, "includeSafetyAttributes",
                "include_safety_attributes", "includeSafetyAttributes");
        putParameter(parameters, normalizedConfig, "includeRaiReason",
                "include_rai_reason", "includeRaiReason");
        putParameter(parameters, normalizedConfig, "language", "language");
        putParameter(parameters, normalizedConfig, "addWatermark",
                "add_watermark", "addWatermark");

        Object outputMimeType = removeFirst(
                normalizedConfig, "output_mime_type", "outputMimeType", "mimeType");
        if (outputMimeType == null) {
            Object outputFormat = removeFirst(normalizedConfig, "output_format", "outputFormat");
            if (outputFormat != null) {
                outputMimeType = mimeTypeFromOutputFormat(outputFormat.toString());
            }
        }
        if (outputMimeType != null) {
            objectChild(parameters, "outputOptions")
                    .set("mimeType", objectMapper.valueToTree(outputMimeType));
        }

        Object compressionQuality = removeFirst(
                normalizedConfig,
                "output_compression_quality",
                "outputCompressionQuality",
                "compressionQuality");
        if (compressionQuality != null) {
            objectChild(parameters, "outputOptions")
                    .set("compressionQuality", objectMapper.valueToTree(compressionQuality));
        }

        if (editMode) {
            putParameter(parameters, normalizedConfig, "editMode", "edit_mode", "editMode");
            Object baseSteps = removeFirst(normalizedConfig, "base_steps", "baseSteps");
            if (baseSteps != null) {
                objectChild(parameters, "editConfig")
                        .set("baseSteps", objectMapper.valueToTree(baseSteps));
            }
        } else {
            putParameter(parameters, normalizedConfig, "sampleImageSize",
                    "size", "image_size", "imageSize", "sampleImageSize");
            putParameter(parameters, normalizedConfig, "enhancePrompt",
                    "enhance_prompt", "enhancePrompt");
        }

        Object rawParameters = removeFirst(normalizedConfig, "parameters");
        if (rawParameters instanceof Map<?, ?> rawParameterMap) {
            ObjectNode rawParameterNode = objectMapper.valueToTree(rawParameterMap);
            parameters.setAll(rawParameterNode);
        }
    }

    private ImagePredictResult parseImagePredictResult(JsonNode root) {
        List<GeneratedImage> generatedImages = new ArrayList<>();
        JsonNode predictions = root.path("predictions");
        if (predictions.isArray()) {
            for (JsonNode prediction : predictions) {
                generatedImages.add(new GeneratedImage(
                        textOrNull(prediction.path("bytesBase64Encoded")),
                        textOrNull(prediction.path("gcsUri")),
                        firstText(prediction, "mimeType", "mime_type"),
                        textOrNull(prediction.path("raiFilteredReason")),
                        firstText(prediction, "prompt", "enhancedPrompt"),
                        nullableNode(prediction.path("safetyAttributes"))));
            }
        }
        return new ImagePredictResult(
                generatedImages,
                nullableNode(root.path("positivePromptSafetyAttributes")));
    }

    private String buildPredictUrl() {
        String resource = "publishers/openai/models/" + CANONICAL_MODEL.substring("openai/".length());
        return joinUrlPath(baseUrl, apiVersion, resource + ":predict");
    }

    private String joinUrlPath(String firstSegment, String... remainingSegments) {
        StringBuilder builder = new StringBuilder(removeTrailingSlashes(firstSegment));
        for (String segment : remainingSegments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            builder.append('/').append(trimSlashes(segment));
        }
        return builder.toString();
    }

    private String removeTrailingSlashes(String value) {
        String result = Objects.requireNonNull(value, "baseUrl is required").trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
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

    private void putImageInput(ObjectNode parent, String data, String uri, String mimeType) {
        if (uri != null && !uri.isBlank()) {
            parent.put("gcsUri", uri);
        }
        if (data != null && !data.isBlank()) {
            parent.put("bytesBase64Encoded", data);
        }
        if (mimeType != null && !mimeType.isBlank()) {
            parent.put("mimeType", mimeType);
        }
    }

    private void putParameter(
            ObjectNode parameters,
            Map<String, Object> source,
            String targetKey,
            String... sourceKeys) {
        Object value = removeFirst(source, sourceKeys);
        if (value != null) {
            parameters.set(targetKey, objectMapper.valueToTree(value));
        }
    }

    private Object removeFirst(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                return source.remove(key);
            }
        }
        return null;
    }

    private ObjectNode objectChild(ObjectNode parent, String fieldName) {
        JsonNode existing = parent.get(fieldName);
        if (existing instanceof ObjectNode objectNode) {
            return objectNode;
        }
        ObjectNode child = objectMapper.createObjectNode();
        parent.set(fieldName, child);
        return child;
    }

    private String mimeTypeFromOutputFormat(String outputFormat) {
        String normalized = outputFormat.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("image/")) {
            return normalized;
        }
        return "jpg".equals(normalized) ? "image/jpeg" : "image/" + normalized;
    }

    private JsonNode nullableNode(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node;
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = textOrNull(node.path(fieldName));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    public record ImageReference(
            Integer referenceId,
            String data,
            String uri,
            String mimeType,
            String referenceType) {
    }

    public record ImagePredictResult(
            List<GeneratedImage> generatedImages,
            JsonNode positivePromptSafetyAttributes) {
    }

    public record GeneratedImage(
            String imageBase64,
            String uri,
            String mimeType,
            String raiFilteredReason,
            String enhancedPrompt,
            JsonNode safetyAttributes) {
    }
}
