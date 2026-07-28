package ai.nubase.ai.gateway.billing;

import ai.nubase.ai.gateway.billing.BillingModels.BillingRequest;
import ai.nubase.ai.gateway.billing.BillingModels.PriceVersion;
import ai.nubase.ai.gateway.billing.BillingModels.RequestStatus;
import ai.nubase.ai.gateway.dto.ApiUsageRecord;
import ai.nubase.ai.gateway.dto.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    @Mock
    private BillingRepository repository;

    private BillingProperties properties;
    private BillingService service;

    @BeforeEach
    void setUp() {
        properties = new BillingProperties();
        properties.setEnabled(true);
        service = new BillingService(repository, properties);
    }

    @Test
    void successfulResponseWithoutUsageRequiresReconciliation() {
        UUID requestId = UUID.randomUUID();
        when(repository.findRequest(requestId)).thenReturn(Optional.of(request(requestId)));
        ApiUsageRecord record = ApiUsageRecord.builder()
                .requestId(requestId.toString())
                .statusCode(200)
                .tokenUsage(TokenUsage.empty())
                .build();

        service.recordUsage(record);

        verify(repository).markReconcileRequired(requestId, "successful_response_without_usage");
        verify(repository, never()).release(requestId, "successful_response_without_usage", null);
    }

    @Test
    void failedAttemptWithoutUsageAlsoRequiresReconciliationBecauseFailoverMayContinue() {
        UUID requestId = UUID.randomUUID();
        when(repository.findRequest(requestId)).thenReturn(Optional.of(request(requestId)));
        ApiUsageRecord record = ApiUsageRecord.builder()
                .requestId(requestId.toString())
                .statusCode(502)
                .tokenUsage(TokenUsage.empty())
                .build();

        service.recordUsage(record);

        verify(repository).markReconcileRequired(requestId, "upstream_error_without_usage");
        verify(repository, never()).release(requestId, "upstream_error_without_usage", null);
    }

    @Test
    void replacementPriceMustStartAfterCurrentVersion() {
        Instant currentStart = Instant.now();
        PriceVersion current = new PriceVersion(
                1L,
                "model-a",
                "model-a",
                "OPENAI",
                "Model A",
                "USD",
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                currentStart,
                null,
                true);
        when(repository.findActivePrice(
                        org.mockito.ArgumentMatchers.eq("model-a"),
                        org.mockito.ArgumentMatchers.eq("USD"),
                        org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.publishPrice(
                "model-a",
                "OPENAI",
                "Model A",
                "USD",
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                currentStart.minusSeconds(1),
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveFrom");

        verify(repository, never()).publishPrice(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private BillingRequest request(UUID requestId) {
        Instant now = Instant.now();
        return new BillingRequest(
                requestId,
                1L,
                "app-a",
                null,
                "model-a",
                "model-a",
                "OPENAI",
                "/v1/responses",
                RequestStatus.RESERVED,
                "USD",
                1L,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                100,
                100,
                0,
                0,
                0,
                0,
                BigDecimal.ONE,
                null,
                null,
                null,
                now,
                now,
                null);
    }
}
