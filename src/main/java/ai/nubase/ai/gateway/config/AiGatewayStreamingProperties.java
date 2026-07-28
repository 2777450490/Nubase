package ai.nubase.ai.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "ai.gateway.streaming")
public class AiGatewayStreamingProperties {

    @Valid
    private DispatcherLimits dispatcher = new DispatcherLimits();

    @Data
    public static class DispatcherLimits {

        @Min(1)
        private int maxRequests = 128;

        @Min(1)
        private int maxRequestsPerHost = 32;

        @AssertTrue(message = "maxRequests must be greater than or equal to maxRequestsPerHost")
        public boolean isCapacityValid() {
            return maxRequests >= maxRequestsPerHost;
        }
    }
}
