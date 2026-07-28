package ai.nubase.ai.gateway.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImageGenerationRequest {

    public static final String TASK_TEXT_TO_IMAGE = "text_to_image";
    public static final String TASK_IMAGE_TO_IMAGE = "image_to_image";

    private String model;
    private String task;
    private String prompt;

    @JsonAlias({"input_images", "inputImages", "reference_images", "referenceImages"})
    private List<ImageInput> inputImages;

    private Map<String, Object> config;

    public void setConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return;
        }
        if (this.config == null) {
            this.config = new LinkedHashMap<>();
        }
        this.config.putAll(config);
    }

    @JsonAnySetter
    public void putConfig(String key, Object value) {
        if (StringUtils.isBlank(key) || value == null) {
            return;
        }
        if (config == null) {
            config = new LinkedHashMap<>();
        }
        config.put(key, value);
    }

    public void normalizeAndValidate(String defaultModel) {
        model = StringUtils.defaultIfBlank(StringUtils.trimToNull(model), StringUtils.trimToNull(defaultModel));
        task = StringUtils.trimToNull(task);
        prompt = StringUtils.trimToNull(prompt);

        if (StringUtils.isBlank(model)) {
            throw new IllegalArgumentException("model is required");
        }
        if (StringUtils.isBlank(prompt)) {
            throw new IllegalArgumentException("prompt is required");
        }
        if (prompt.length() > 32_000) {
            throw new IllegalArgumentException("prompt must not exceed 32000 characters");
        }

        normalizeInputImages();
        if (StringUtils.isBlank(task)) {
            task = hasInputImages() ? TASK_IMAGE_TO_IMAGE : TASK_TEXT_TO_IMAGE;
        }
        if (!TASK_TEXT_TO_IMAGE.equals(task) && !TASK_IMAGE_TO_IMAGE.equals(task)) {
            throw new IllegalArgumentException("task must be text_to_image or image_to_image");
        }
        if (TASK_IMAGE_TO_IMAGE.equals(task) && !hasInputImages()) {
            throw new IllegalArgumentException("input_images is required for image_to_image");
        }
    }

    public boolean hasInputImages() {
        return inputImages != null && !inputImages.isEmpty();
    }

    private void normalizeInputImages() {
        if (inputImages == null || inputImages.isEmpty()) {
            inputImages = null;
            return;
        }
        List<ImageInput> normalized = new ArrayList<>();
        for (ImageInput inputImage : inputImages) {
            if (inputImage == null) {
                continue;
            }
            inputImage.normalize();
            if (inputImage.hasValue()) {
                normalized.add(inputImage);
            }
        }
        inputImages = normalized.isEmpty() ? null : normalized;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ImageInput {
        @JsonAlias({"reference_id", "referenceId", "id"})
        private Integer referenceId;

        @JsonAlias({"base64", "b64_json", "imageBytes", "bytesBase64Encoded"})
        private String data;

        @JsonAlias({"gcsUri", "fileUri"})
        private String uri;

        @JsonAlias("mime_type")
        private String mimeType;

        @JsonAlias("reference_type")
        private String referenceType;

        public void normalize() {
            data = StringUtils.trimToNull(data);
            uri = StringUtils.trimToNull(uri);
            mimeType = StringUtils.defaultIfBlank(StringUtils.trimToNull(mimeType), "image/png");
            referenceType = StringUtils.defaultIfBlank(
                    StringUtils.trimToNull(referenceType), "REFERENCE_TYPE_RAW");
        }

        public boolean hasValue() {
            return StringUtils.isNotBlank(data) || StringUtils.isNotBlank(uri);
        }
    }
}
