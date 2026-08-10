package ai.nubase.ai.gateway.controller;

import ai.nubase.ai.gateway.repository.ModelPricingRepository;
import ai.nubase.ai.gateway.service.ClaudeGatewayService;
import ai.nubase.ai.gateway.service.OpenAIApiService;
import ai.nubase.ai.gateway.service.OpenAINativeApiService;
import ai.nubase.ai.gateway.service.TokenCounterService;
import ai.nubase.ai.gateway.testsupport.LogCapture;
import ai.nubase.common.enums.ApiProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayControllerLogSafetyTest {

    private static final String SENSITIVE_CONTENT = "runtime-log-sensitive-content";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void openAINativeSuccessLogsOnlyResponseMetadata() throws Exception {
        OpenAINativeApiService service = mock(OpenAINativeApiService.class);
        OpenAINativeController controller = new OpenAINativeController(
                service, objectMapper, mock(ModelPricingRepository.class));
        String responseBody = "{\"output\":\"" + SENSITIVE_CONTENT + "\"}";
        when(service.handleNonStreamingRequest(anyString(), isNull(), isNull(), anyMap()))
                .thenReturn(responseBody);

        try (LogCapture logs = LogCapture.forClass(OpenAINativeController.class)) {
            ResponseEntity<?> response = (ResponseEntity<?>) controller.chatCompletions(
                    openAIRequestBody(), new MockHttpServletRequest("POST", "/v1/chat/completions"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(responseBody);
            assertThat(logs.formattedMessages())
                    .noneMatch(message -> message.contains(SENSITIVE_CONTENT))
                    .anyMatch(message -> message.contains("responseBytes="));
        }
    }

    @Test
    void openAINativeFailureDoesNotExposeExceptionMessage() throws Exception {
        OpenAINativeApiService service = mock(OpenAINativeApiService.class);
        OpenAINativeController controller = new OpenAINativeController(
                service, objectMapper, mock(ModelPricingRepository.class));
        when(service.handleNonStreamingRequest(anyString(), isNull(), isNull(), anyMap()))
                .thenThrow(new IOException("upstream echoed " + SENSITIVE_CONTENT));

        try (LogCapture logs = LogCapture.forClass(OpenAINativeController.class)) {
            ResponseEntity<?> response = (ResponseEntity<?>) controller.chatCompletions(
                    openAIRequestBody(), new MockHttpServletRequest("POST", "/v1/chat/completions"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            JsonNode error = objectMapper.readTree((String) response.getBody());
            assertThat(error.path("error").path("message").asText()).isEqualTo("Upstream request failed");
            assertThat(response.getBody().toString()).doesNotContain(SENSITIVE_CONTENT);
            assertThat(logs.formattedMessages())
                    .noneMatch(message -> message.contains(SENSITIVE_CONTENT))
                    .anyMatch(message -> message.contains("errorType=IOException"));
        }
    }

    @Test
    void claudeSuccessLogsOnlyResponseMetadata() throws Exception {
        ClaudeGatewayService gatewayService = mock(ClaudeGatewayService.class);
        ClaudeGatewayController controller = claudeController(gatewayService);
        String responseBody = "{\"content\":\"" + SENSITIVE_CONTENT + "\"}";
        when(gatewayService.forwardRequest(
                anyString(), anyString(), anyMap(), isNull(), isNull(), any(ApiProvider.class)))
                .thenReturn(responseBody);

        try (LogCapture logs = LogCapture.forClass(ClaudeGatewayController.class)) {
            ResponseEntity<?> response = (ResponseEntity<?>) controller.createMessage(
                    claudeRequestBody(), new MockHttpServletRequest("POST", "/v1/messages"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(responseBody);
            assertThat(logs.formattedMessages())
                    .noneMatch(message -> message.contains(SENSITIVE_CONTENT))
                    .anyMatch(message -> message.contains("responseBytes="));
        }
    }

    @Test
    void claudeFailureDoesNotExposeExceptionMessage() throws Exception {
        ClaudeGatewayService gatewayService = mock(ClaudeGatewayService.class);
        ClaudeGatewayController controller = claudeController(gatewayService);
        when(gatewayService.forwardRequest(
                anyString(), anyString(), anyMap(), isNull(), isNull(), any(ApiProvider.class)))
                .thenThrow(new IOException("upstream echoed " + SENSITIVE_CONTENT));

        try (LogCapture logs = LogCapture.forClass(ClaudeGatewayController.class)) {
            ResponseEntity<?> response = (ResponseEntity<?>) controller.createMessage(
                    claudeRequestBody(), new MockHttpServletRequest("POST", "/v1/messages"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            JsonNode error = objectMapper.readTree((String) response.getBody());
            assertThat(error.path("error").asText()).isEqualTo("Request forwarding failed");
            assertThat(response.getBody().toString()).doesNotContain(SENSITIVE_CONTENT);
            assertThat(logs.formattedMessages())
                    .noneMatch(message -> message.contains(SENSITIVE_CONTENT))
                    .anyMatch(message -> message.contains("errorType=IOException"));
        }
    }

    private ClaudeGatewayController claudeController(ClaudeGatewayService gatewayService) {
        return new ClaudeGatewayController(
                gatewayService,
                mock(OpenAIApiService.class),
                objectMapper,
                mock(TokenCounterService.class));
    }

    private String openAIRequestBody() {
        return "{\"model\":\"test-model\",\"messages\":[{\"role\":\"user\",\"content\":\""
                + SENSITIVE_CONTENT
                + "\"}]}";
    }

    private String claudeRequestBody() {
        return "{\"model\":\"claude-test\",\"messages\":[{\"role\":\"user\",\"content\":\""
                + SENSITIVE_CONTENT
                + "\"}]}";
    }
}
