package ai.nubase.ai.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImageGenerationResponse {

    private String id;
    private String model;
    private String task;
    private List<Output> outputs;
    private JsonNode usage;
    private Upstream upstream;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Output {
        private String type;

        @JsonProperty("mime_type")
        private String mimeType;

        @JsonProperty("b64_json")
        private String b64Json;

        private String uri;

        @JsonProperty("rai_filtered_reason")
        private String raiFilteredReason;

        @JsonProperty("enhanced_prompt")
        private String enhancedPrompt;

        @JsonProperty("safety_attributes")
        private JsonNode safetyAttributes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Upstream {
        private String provider;
        private String action;
    }
}
