package ai.nubase.ai.gateway.service;

import ai.nubase.ai.gateway.dto.TokenUsage;
import ai.nubase.ai.gateway.entity.UpstreamConfig;
import ai.nubase.ai.gateway.platform.PlatformUpstreamService;
import ai.nubase.common.config.AnthropicConfig;
import ai.nubase.common.enums.ApiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ClaudeGatewayServiceLoggingSafetyTest {

    @Test
    void formatsOnlyAllowlistedResponseHeaders() {
        Headers headers = new Headers.Builder()
                .add("Set-Cookie", "session=cookie-value")
                .add("Authorization", "authorization-value")
                .add("X-API-Key", "api-key-value")
                .add("X-Request-Id", "request-123")
                .add("tracestate", "vendor=opaque-value")
                .add("CF-Ray", "ray-123")
                .add("X-Upstream-Status", "upstream-status-value")
                .add("X-Error", "upstream-error-value")
                .add("Content-Type", "text/event-stream")
                .add("X-Debug-Details", "internal-value")
                .build();

        String formatted = ClaudeGatewayService.formatResponseHeadersForLogging(headers);

        assertThat(formatted)
                .contains(
                        "X-Request-Id",
                        "CF-Ray",
                        "Content-Type")
                .doesNotContain(
                        "request-123",
                        "ray-123",
                        "text/event-stream",
                        "Set-Cookie",
                        "cookie-value",
                        "Authorization",
                        "authorization-value",
                        "X-API-Key",
                        "api-key-value",
                        "X-Upstream-Status",
                        "upstream-status-value",
                        "X-Error",
                        "upstream-error-value",
                        "tracestate",
                        "opaque-value",
                        "X-Debug-Details",
                        "internal-value");
    }

    @Test
    void formatsMissingOrFilteredHeadersAsEmptyObject() {
        assertThat(ClaudeGatewayService.formatResponseHeadersForLogging(null)).isEqualTo("{}");
        assertThat(ClaudeGatewayService.formatResponseHeadersForLogging(
                new Headers.Builder().add("Set-Cookie", "session=value").build()))
                .isEqualTo("{}");
    }

    @Test
    void summarizesRequestShapeWithoutIncludingUserControlledValues() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ClaudeGatewayService service = newService(objectMapper);
        String userContent = "private-user-content";
        String systemContent = "private-system-content";
        String unknownRole = "private-role-value";
        String unknownType = "private-type-value";
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "system", systemContent,
                "messages", List.of(Map.of(
                        "role", unknownRole,
                        "content", List.of(Map.of(
                                "type", unknownType,
                                "text", userContent))))));

        String summary = service.summarizeRequestBody(requestBody);

        assertThat(summary)
                .contains("messageCount=1", "roles={other=1}", "contentBlocks={other=1}", "hasSystem=true")
                .doesNotContain(userContent, systemContent, unknownRole, unknownType);
    }

    @Test
    void malformedRequestSummaryDoesNotIncludeParserInput() {
        String malformedBody = "malformed-private-content";

        String summary = newService(new ObjectMapper()).summarizeRequestBody(malformedBody);

        assertThat(summary)
                .startsWith("stats_parse_failed bodyBytes=")
                .doesNotContain(malformedBody, "private-content");
    }

    @Test
    void summarizesFailuresAndEventTypesWithoutUserControlledValues() {
        String responseBody = "private-response-body";
        IOException error = new IOException("private-exception-message");

        assertThat(ClaudeGatewayService.safeFailureSummary(502, responseBody, error))
                .isEqualTo("status=502 exception=IOException bodyBytes=21")
                .doesNotContain(responseBody, error.getMessage());
        assertThat(ClaudeGatewayService.safeEventType("message_delta")).isEqualTo("message_delta");
        assertThat(ClaudeGatewayService.safeEventType("private-event-type")).isEqualTo("other");
        assertThat(ClaudeGatewayService.safeEventType(null)).isEqualTo("other");
    }

    @Test
    void synchronousCallsReturnBodiesWithoutLoggingRawContent(CapturedOutput output) throws Exception {
        String requestContent = "private-request-content";
        String getResponse = "{\"value\":\"private-get-response\"}";
        String messageResponse = "{\"value\":\"private-message-response\"}";
        String countResponse = "{\"value\":\"private-count-response\"}";
        String eventResponse = "{\"value\":\"private-event-response\"}";

        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200).setBody(getResponse));
            server.enqueue(new MockResponse().setResponseCode(200).setBody(messageResponse));
            server.enqueue(new MockResponse().setResponseCode(200).setBody(countResponse));
            server.enqueue(new MockResponse().setResponseCode(200).setBody(eventResponse));
            ServiceHarness harness = newHarness(server.url("").toString().replaceAll("/$", ""));
            String requestBody = "{\"model\":\"test-model\",\"messages\":[{\"role\":\"user\",\"content\":\""
                    + requestContent + "\"}]}";

            assertThat(harness.service().forwardGetRequest("/v1/models", Map.of(), "test-client-key"))
                    .isEqualTo(getResponse);
            assertThat(harness.service().forwardRequest(
                    "/v1/messages", requestBody, Map.of(), "test-client-key", null, ApiProvider.CLAUDE))
                    .isEqualTo(messageResponse);
            assertThat(harness.service().forwardCountTokensRequest(
                    requestBody, Map.of(), "test-client-key"))
                    .isEqualTo(countResponse);
            assertThat(harness.service().forwardEventLoggingRequest(requestBody, Map.of()))
                    .isEqualTo(eventResponse);
        }

        assertThat(output.getAll())
                .doesNotContain(
                        requestContent,
                        getResponse,
                        messageResponse,
                        countResponse,
                        eventResponse,
                        "private-get-response",
                        "private-message-response",
                        "private-count-response",
                        "private-event-response",
                        "test...-key");
    }

    @Test
    void eventLoggingErrorsDoNotWriteRawResponseBody(CapturedOutput output) throws Exception {
        String errorBody = "private-event-error-body";

        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(400).setBody(errorBody));
            ServiceHarness harness = newHarness(server.url("").toString().replaceAll("/$", ""));

            assertThatThrownBy(() -> harness.service().forwardEventLoggingRequest("{}", Map.of()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("status=400")
                    .hasMessageContaining("bodyBytes=24")
                    .hasMessageNotContaining(errorBody);
        }

        assertThat(output.getAll())
                .contains("status=400", "bodyBytes=24")
                .doesNotContain(errorBody);
    }

    @Test
    void sseEventLogsOnlySafeTypeAndPayloadSize(CapturedOutput output) {
        String eventId = "private-event-id";
        String eventType = "private-event-type";
        String eventData = "private-event-data";
        ServiceHarness harness = newHarness("http://127.0.0.1:1");
        SseEmitter emitter = new SseEmitter();

        harness.service().forwardStreamingRequest(
                "/v1/messages",
                "{\"model\":\"test-model\",\"messages\":[]}",
                Map.of(),
                "test-client-key",
                null,
                emitter,
                ApiProvider.CLAUDE);

        ArgumentCaptor<EventSourceListener> listenerCaptor = ArgumentCaptor.forClass(EventSourceListener.class);
        verify(harness.streamingProvider()).newEventSource(
                any(Request.class),
                listenerCaptor.capture(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyString(),
                anyString(),
                anyString());
        listenerCaptor.getValue().onEvent(harness.eventSource(), eventId, eventType, eventData);

        assertThat(output.getAll())
                .contains("type=other", "dataBytes=18")
                .doesNotContain(eventId, eventType, eventData, "test...-key");
    }

    private ClaudeGatewayService newService(ObjectMapper objectMapper) {
        return new ClaudeGatewayService(
                new AnthropicConfig(),
                objectMapper,
                mock(ApiUsageTrackingService.class),
                mock(ApiRequestLogService.class),
                mock(UpstreamConfigService.class),
                mock(PlatformUpstreamService.class),
                mock(ContextPruneService.class),
                mock(AiGatewayStreamingHttpClientProvider.class));
    }

    private ServiceHarness newHarness(String baseUrl) {
        AnthropicConfig config = new AnthropicConfig();
        config.setBaseUrl(baseUrl);
        config.setAuthToken("test-upstream-key");
        config.setTimeout(1000);

        ObjectMapper objectMapper = new ObjectMapper();
        ApiUsageTrackingService usageTrackingService = mock(ApiUsageTrackingService.class);
        when(usageTrackingService.extractModelFromRequest(anyString())).thenReturn("test-model");
        when(usageTrackingService.extractTokenUsage(anyString())).thenReturn(TokenUsage.empty());

        UpstreamConfigService upstreamConfigService = mock(UpstreamConfigService.class);
        UpstreamConfig upstream = UpstreamConfig.builder()
                .name("test-upstream")
                .baseUrl(baseUrl)
                .authToken("test-upstream-key")
                .provider(ApiProvider.CLAUDE)
                .timeoutMs(1000)
                .build();
        when(upstreamConfigService.getDefaultByProvider(ApiProvider.CLAUDE)).thenReturn(upstream);
        when(upstreamConfigService.getFailoverUpstreams(any(), any())).thenReturn(List.of());

        AiGatewayStreamingHttpClientProvider streamingProvider =
                mock(AiGatewayStreamingHttpClientProvider.class);
        EventSource eventSource = mock(EventSource.class);
        when(streamingProvider.newEventSource(
                any(Request.class),
                any(EventSourceListener.class),
                anyInt(),
                anyInt(),
                anyInt(),
                anyString(),
                anyString(),
                anyString()))
                .thenReturn(eventSource);

        ClaudeGatewayService service = new ClaudeGatewayService(
                config,
                objectMapper,
                usageTrackingService,
                mock(ApiRequestLogService.class),
                upstreamConfigService,
                mock(PlatformUpstreamService.class),
                new ContextPruneService(objectMapper),
                streamingProvider);
        return new ServiceHarness(service, streamingProvider, eventSource);
    }

    private record ServiceHarness(
            ClaudeGatewayService service,
            AiGatewayStreamingHttpClientProvider streamingProvider,
            EventSource eventSource) {
    }
}
