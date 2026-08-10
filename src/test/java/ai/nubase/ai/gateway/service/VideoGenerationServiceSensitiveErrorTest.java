package ai.nubase.ai.gateway.service;

import ai.nubase.ai.gateway.dto.ApiUsageRecord;
import ai.nubase.ai.gateway.dto.TokenUsage;
import ai.nubase.ai.gateway.dto.VideoGenerationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class VideoGenerationServiceSensitiveErrorTest {

    private MockWebServer server;
    private ApiUsageTrackingService usageTrackingService;
    private ApiRequestLogService requestLogService;
    private VideoGenerationService service;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        usageTrackingService = mock(ApiUsageTrackingService.class);
        requestLogService = mock(ApiRequestLogService.class);
        when(usageTrackingService.createRequestMetadata(nullable(String.class), anyMap()))
                .thenReturn(Map.of());
        service = new VideoGenerationService(
                new ObjectMapper(),
                usageTrackingService,
                requestLogService,
                "test-upstream-key",
                server.url("/api/vertex-ai").toString(),
                5_000);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void upstreamFailureUsesOnlyStatusAndBodySizeOutsideControlledRequestLog(CapturedOutput output) {
        String sentinel = "video-upstream-response-sentinel";
        String responseBody = "{\"error\":{\"message\":\"" + sentinel + "\"},"
                + "\"prompt\":\"private prompt\"}";
        server.enqueue(new MockResponse().setResponseCode(502).setBody(responseBody));
        VideoGenerationRequest request = new VideoGenerationRequest();
        request.setPrompt("Generate a safe test clip");

        Throwable failure = catchThrowable(() -> service.submit(
                request,
                "nbk_test_client",
                Map.of("user-agent", "test-client"),
                null));

        assertThat(failure)
                .isInstanceOf(VideoGenerationService.UpstreamHttpException.class)
                .hasMessageContaining("status=502")
                .hasMessageContaining("bodyBytes="
                        + responseBody.getBytes(StandardCharsets.UTF_8).length)
                .hasMessageNotContaining(sentinel)
                .hasMessageNotContaining("private prompt");

        ArgumentCaptor<ApiUsageRecord> usageRecord = ArgumentCaptor.forClass(ApiUsageRecord.class);
        verify(usageTrackingService).trackUsage(usageRecord.capture());
        assertThat(usageRecord.getValue().getErrorMessage())
                .contains("status=502", "bodyBytes=")
                .doesNotContain(sentinel, "private prompt");
        assertThat(usageRecord.getValue().getRequestMetadata().toString())
                .doesNotContain(sentinel, "private prompt");

        ArgumentCaptor<String> requestLogError = ArgumentCaptor.forClass(String.class);
        verify(requestLogService).logRequest(
                anyString(),
                anyString(),
                eq("POST"),
                eq("/ai/v1/videos/generations"),
                eq(VideoGenerationService.SEEDANCE_MODEL),
                anyMap(),
                anyString(),
                eq(502),
                anyString(),
                anyLong(),
                any(TokenUsage.class),
                requestLogError.capture());
        assertThat(requestLogError.getValue())
                .contains("status=502", "bodyBytes=")
                .doesNotContain(sentinel, "private prompt");
        assertThat(output.getAll()).doesNotContain(sentinel, "private prompt");
    }
}
