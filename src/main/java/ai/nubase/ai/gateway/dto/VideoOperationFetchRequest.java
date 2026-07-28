package ai.nubase.ai.gateway.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VideoOperationFetchRequest {

    @JsonProperty("operation_name")
    @JsonAlias({"operationName", "name"})
    private String operationName;

    private OperationRef operation;
    private String upstream;

    public String resolveOperationName() {
        String resolved = StringUtils.trimToNull(operationName);
        if (resolved == null && operation != null) {
            resolved = StringUtils.trimToNull(operation.getName());
        }
        if (resolved == null) {
            throw new IllegalArgumentException("operation_name is required");
        }
        return resolved;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OperationRef {
        private String name;
    }
}
