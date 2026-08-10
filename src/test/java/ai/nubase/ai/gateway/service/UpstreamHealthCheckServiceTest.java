package ai.nubase.ai.gateway.service;

import ai.nubase.ai.gateway.entity.UpstreamConfig;
import ai.nubase.ai.gateway.repository.UpstreamConfigRepository;
import ai.nubase.common.enums.ApiProvider;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class UpstreamHealthCheckServiceTest {

    private MockWebServer server;
    private UpstreamHealthCheckService healthCheckService;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        healthCheckService = new UpstreamHealthCheckService(
                mock(UpstreamConfigRepository.class),
                mock(FeishuNotificationService.class),
                mock(PlatformTransactionManager.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void failedProbeReturnsAndLogsOnlySafeStatusSummaries(CapturedOutput output) {
        String firstResponseBody = "response-body-secret-one";
        String secondResponseBody = "response-body-secret-two";
        server.enqueue(new MockResponse().setResponseCode(401).setBody(firstResponseBody));
        server.enqueue(new MockResponse().setResponseCode(502).setBody(secondResponseBody));

        UpstreamConfig upstream = UpstreamConfig.builder()
                .name("openai-primary")
                .provider(ApiProvider.OPENAI)
                .baseUrl(server.url("").toString().replaceAll("/$", ""))
                .authToken("health-check-auth-token")
                .build();

        UpstreamHealthCheckService.HealthCheckResult result = healthCheckService.performHealthCheck(upstream);

        assertThat(result.isHealthy()).isFalse();
        assertThat(result.getMessage())
                .contains("gpt-5.4-mini: HTTP 401", "glm-5: HTTP 502", "responseTimeMs=")
                .doesNotContain(firstResponseBody, secondResponseBody);
        assertThat(output.getAll()).doesNotContain(firstResponseBody, secondResponseBody);
    }
}
