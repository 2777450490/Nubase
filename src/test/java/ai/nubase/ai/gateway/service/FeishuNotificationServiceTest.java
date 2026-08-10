package ai.nubase.ai.gateway.service;

import ai.nubase.ai.gateway.config.FeishuConfig;
import ai.nubase.ai.gateway.entity.UpstreamConfig;
import ai.nubase.common.enums.ApiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class FeishuNotificationServiceTest {

    private MockWebServer server;
    private FeishuConfig feishuConfig;
    private FeishuNotificationService notificationService;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        feishuConfig = new FeishuConfig();
        feishuConfig.setEnabled(true);
        feishuConfig.setWebhookUrl(server.url("/webhook").toString());
        notificationService = new FeishuNotificationService(feishuConfig, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void upstreamCardsNeverContainAuthenticationToken() throws Exception {
        String authToken = "upstream-auth-token-must-not-leave-process";
        UpstreamConfig upstream = UpstreamConfig.builder()
                .name("openai-primary")
                .provider(ApiProvider.OPENAI)
                .baseUrl("https://upstream.example.test")
                .authToken(authToken)
                .build();

        server.enqueue(new MockResponse().setResponseCode(200));
        notificationService.notifyUpstreamDown(
                upstream,
                "gpt-5.4-mini: HTTP 401 (responseTimeMs=12); leaked=" + authToken);

        String downCard = takeCardJson();
        assertThat(downCard)
                .contains("OPENAI", "openai-primary", "UNHEALTHY")
                .doesNotContain(
                        authToken,
                        "API Key",
                        "https://upstream.example.test",
                        "HTTP 401",
                        "leaked=");

        server.enqueue(new MockResponse().setResponseCode(200));
        notificationService.notifyUpstreamRecovered(upstream);

        String recoveredCard = takeCardJson();
        assertThat(recoveredCard)
                .contains("OPENAI", "openai-primary", "HEALTHY")
                .doesNotContain(authToken, "API Key", "https://upstream.example.test");
    }

    @Test
    void mem0FailureCardContainsOnlyOperationalIdentifiersAndStatus() throws Exception {
        String userId = "user-sensitive-123";
        String requestPayload = "{\"messages\":[{\"content\":\"private prompt\"}]}";
        String rawError = "upstream response included private customer data";

        server.enqueue(new MockResponse().setResponseCode(200));
        notificationService.notifyMem0AsyncWriteFailed(
                "request-abc-123",
                42L,
                userId,
                503,
                rawError,
                requestPayload);

        String card = takeCardJson();
        assertThat(card)
                .contains("request-abc-123", "42", "503", "Upstream service error")
                .doesNotContain(userId, requestPayload, "private prompt", rawError, "User ID", "Request Preview");
    }

    @Test
    void webhookFailuresDoNotLogResponseBodyOrWebhookUrl(CapturedOutput output) {
        String responseBody = "webhook-response-body-secret";
        server.enqueue(new MockResponse().setResponseCode(400).setBody(responseBody));

        notificationService.notifyMem0AsyncWriteFailed("request-123", 42L, "user-123", 400, "raw error", "payload");

        assertThat(output.getAll()).contains("HTTP 400").doesNotContain(responseBody, feishuConfig.getWebhookUrl());

        String invalidWebhookUrl = "invalid-webhook-url-secret";
        feishuConfig.setWebhookUrl(invalidWebhookUrl);
        notificationService.notifyMem0AsyncWriteFailed("request-456", 43L, "user-456", 500, "raw error", "payload");

        assertThat(output.getAll()).contains("IllegalArgumentException").doesNotContain(invalidWebhookUrl);
    }

    private String takeCardJson() throws Exception {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        return request.getBody().readUtf8();
    }
}
