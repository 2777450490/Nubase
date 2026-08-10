package ai.nubase.ai.gateway.service;

import ai.nubase.ai.gateway.dto.TokenUsage;
import ai.nubase.common.config.AnthropicConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRequestLogServiceTest {

    @TempDir
    Path logDirectory;

    private ObjectMapper objectMapper;
    private ApiRequestLogService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AnthropicConfig config = new AnthropicConfig();
        config.getLogging().setEnabled(true);
        service = new ApiRequestLogService(objectMapper, config, new MockEnvironment());
        ReflectionTestUtils.setField(service, "logBaseDir", logDirectory.toString());
    }

    @Test
    void createsUniqueRequestDirectoriesAndPersistsOnlyAllowlistedHeaders() throws Exception {
        Map<String, String> firstRequestHeaders = new LinkedHashMap<>();
        firstRequestHeaders.put("Authorization", "authorization-value");
        firstRequestHeaders.put("Cookie", "cookie-value");
        firstRequestHeaders.put("X-Client-API-Key", "client-key-value");
        firstRequestHeaders.put("X-Goog-Api-Key", "google-key-value");
        firstRequestHeaders.put("X-OpenAI-API-Key", "openai-key-value");
        firstRequestHeaders.put("X-Auth-Key", "auth-key-value");
        firstRequestHeaders.put("X-Access-Key", "access-key-value");
        firstRequestHeaders.put("X-Private-Key", "private-key-value");
        firstRequestHeaders.put("x-custom-header", "custom-value");
        firstRequestHeaders.put("x-request-id", "request-123");
        firstRequestHeaders.put("traceparent", "00-trace-span-01");
        firstRequestHeaders.put("tracestate", "vendor=opaque-value");
        firstRequestHeaders.put("user-agent", "test-client");

        service.logRequest(
                "request/one",
                "nbk_demo...",
                "POST",
                "/v1/messages",
                "test-model",
                firstRequestHeaders,
                "{}",
                200,
                "{}",
                10,
                TokenUsage.empty(),
                null);
        service.logRequest(
                "request-two",
                "nbk_demo...",
                "POST",
                "/v1/messages",
                "test-model",
                Map.of("x-request-id", "request-456"),
                "{}",
                200,
                "{}",
                11,
                TokenUsage.empty(),
                null);

        List<Path> requestDirectories;
        try (var paths = Files.list(logDirectory)) {
            requestDirectories = paths.sorted(Comparator.comparing(Path::toString)).toList();
        }
        assertThat(requestDirectories).hasSize(2);
        assertThat(requestDirectories)
                .anyMatch(path -> path.getFileName().toString().endsWith("_request_one"))
                .anyMatch(path -> path.getFileName().toString().endsWith("_request-two"));

        Path firstRequestDirectory = requestDirectories.stream()
                .filter(path -> path.getFileName().toString().endsWith("_request_one"))
                .findFirst()
                .orElseThrow();
        JsonNode requestLog = objectMapper.readTree(
                Files.readString(firstRequestDirectory.resolve("request.json")));

        assertThat(requestLog.path("headers").path("x-request-id").asText())
                .isEqualTo("[present]");
        assertThat(requestLog.path("headers").path("traceparent").asText())
                .isEqualTo("[present]");
        assertThat(requestLog.path("headers").path("user-agent").asText())
                .isEqualTo("[present]");
        assertThat(requestLog.toString())
                .doesNotContain("request-123", "00-trace-span-01", "test-client");
        assertThat(requestLog.path("headers").fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("x-request-id", "traceparent", "user-agent");
        assertThat(Files.readString(firstRequestDirectory.resolve("request.json")))
                .doesNotContain(
                        "authorization-value",
                        "cookie-value",
                        "client-key-value",
                        "google-key-value",
                        "openai-key-value",
                        "auth-key-value",
                        "access-key-value",
                        "private-key-value",
                        "opaque-value",
                        "custom-value");
    }
}
