package ai.nubase.ai.gateway.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VideoGenerationRequest {

    public static final String SEEDANCE_MODEL = "bytedance/doubao-seedance-2.0";

    private String model;
    private String prompt;
    private MediaInput image;
    private MediaInput video;

    @JsonProperty("last_frame")
    @JsonAlias("lastFrame")
    private MediaInput lastFrame;

    @JsonProperty("reference_images")
    @JsonAlias("referenceImages")
    private List<Map<String, Object>> referenceImages;

    private Map<String, Object> config;
    private List<Map<String, Object>> instances;
    private Map<String, Object> parameters;

    public void normalizeAndValidate() {
        model = resolveFixedModel(model);
        prompt = StringUtils.trimToNull(prompt);
        if (hasRawInstances()) {
            return;
        }
        boolean hasImage = image != null && image.hasValue();
        boolean hasVideo = video != null && video.hasValue();
        if (StringUtils.isBlank(prompt) && !hasImage && !hasVideo) {
            throw new IllegalArgumentException("prompt, image, video, or instances is required");
        }
    }

    public boolean hasRawInstances() {
        return instances != null && !instances.isEmpty();
    }

    public static String resolveFixedModel(String requestedModel) {
        String normalized = StringUtils.trimToEmpty(requestedModel).toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()
                || normalized.equals(SEEDANCE_MODEL)
                || normalized.equals("seedance")
                || normalized.equals("seeddance")
                || normalized.equals("doubao-seedance-2.0")) {
            return SEEDANCE_MODEL;
        }
        throw new IllegalArgumentException("only " + SEEDANCE_MODEL + " is supported");
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MediaInput {

        @JsonAlias({
                "base64",
                "bytesBase64Encoded",
                "bytes_base64_encoded",
                "imageBytes",
                "image_bytes",
                "videoBytes",
                "video_bytes"
        })
        private String data;

        @JsonAlias({"gcsUri", "gcs_uri", "fileUri", "file_uri"})
        private String uri;

        @JsonAlias("mime_type")
        private String mimeType;

        public boolean hasValue() {
            return StringUtils.isNotBlank(data) || StringUtils.isNotBlank(uri);
        }
    }
}
