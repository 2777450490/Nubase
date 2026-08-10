package ai.nubase.ai.gateway.service;

import ai.nubase.ai.gateway.billing.BillingService;
import ai.nubase.ai.gateway.platform.PlatformUsageTrackingService;
import ai.nubase.ai.gateway.repository.ApiKeyRepository;
import ai.nubase.ai.gateway.repository.ApiUsageLogRepository;
import ai.nubase.ai.gateway.repository.DailyTokenUsageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ApiUsageTrackingServiceMetadataTest {

    @Test
    void persistsOnlyAllowlistedHeadersInRequestMetadata() {
        ApiUsageTrackingService service = new ApiUsageTrackingService(
                mock(ApiKeyRepository.class),
                mock(DailyTokenUsageRepository.class),
                mock(ApiUsageLogRepository.class),
                mock(PricingService.class),
                new ObjectMapper(),
                mock(PlatformUsageTrackingService.class),
                mock(BillingService.class));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "authorization-value");
        headers.put("Cookie", "cookie-value");
        headers.put("X-Client-API-Key", "client-key-value");
        headers.put("X-Goog-Api-Key", "google-key-value");
        headers.put("X-OpenAI-API-Key", "openai-key-value");
        headers.put("X-Auth-Key", "auth-key-value");
        headers.put("X-Access-Key", "access-key-value");
        headers.put("X-Private-Key", "private-key-value");
        headers.put("x-custom-header", "custom-value");
        headers.put("x-request-id", "request-123");
        headers.put("traceparent", "00-trace-span-01");
        headers.put("tracestate", "vendor=opaque-value");
        headers.put("user-agent", "test-client");

        Map<String, Object> metadata = service.createRequestMetadata("test-client", headers);

        assertThat(metadata).containsEntry("user_agent", "[present]");
        assertThat(metadata.get("headers"))
                .isEqualTo(Map.of(
                        "x-request-id", "[present]",
                        "traceparent", "[present]",
                        "user-agent", "[present]"));
        assertThat(metadata.toString())
                .doesNotContain("request-123", "00-trace-span-01", "test-client");
    }
}
