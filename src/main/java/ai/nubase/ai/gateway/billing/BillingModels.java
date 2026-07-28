package ai.nubase.ai.gateway.billing;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class BillingModels {

    private BillingModels() {
    }

    public enum AccountStatus {
        ACTIVE,
        SUSPENDED,
        CLOSED
    }

    public enum RequestStatus {
        RESERVED,
        SETTLED,
        RELEASED,
        RECONCILE_REQUIRED
    }

    public enum UsageSource {
        UPSTREAM,
        LOCAL_ESTIMATE,
        MANUAL
    }

    public enum LedgerEntryType {
        RESERVE,
        SETTLE,
        RELEASE,
        TOP_UP,
        ADJUSTMENT,
        REFUND
    }

    public record BillingAccount(
            long id,
            String appCode,
            String currency,
            BigDecimal balance,
            BigDecimal reservedAmount,
            BigDecimal creditLimit,
            AccountStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        @JsonProperty("availableAmount")
        public BigDecimal availableAmount() {
            return balance.add(creditLimit).subtract(reservedAmount);
        }
    }

    public record PriceVersion(
            long id,
            String model,
            String normalizedModel,
            String provider,
            String displayName,
            String currency,
            BigDecimal inputPricePer1M,
            BigDecimal outputPricePer1M,
            BigDecimal cacheCreationPricePer1M,
            BigDecimal cacheReadPricePer1M,
            Instant effectiveFrom,
            Instant effectiveTo,
            boolean active) {
    }

    public record ReservePlan(
            UUID requestId,
            String appCode,
            String clientIdempotencyKey,
            String model,
            String normalizedModel,
            String provider,
            String endpoint,
            long estimatedInputTokens,
            long reservedOutputTokens,
            BigDecimal reservedAmount,
            PriceVersion price) {
    }

    public record Reservation(
            UUID requestId,
            long accountId,
            String appCode,
            String model,
            BigDecimal reservedAmount,
            String currency,
            RequestStatus status,
            boolean idempotentReplay) {
    }

    public record BillingRequest(
            UUID requestId,
            long accountId,
            String appCode,
            String clientIdempotencyKey,
            String model,
            String normalizedModel,
            String provider,
            String endpoint,
            RequestStatus status,
            String currency,
            long priceVersionId,
            BigDecimal inputPricePer1M,
            BigDecimal outputPricePer1M,
            BigDecimal cacheCreationPricePer1M,
            BigDecimal cacheReadPricePer1M,
            long estimatedInputTokens,
            long reservedOutputTokens,
            long inputTokens,
            long outputTokens,
            long cacheCreationInputTokens,
            long cacheReadInputTokens,
            BigDecimal reservedAmount,
            BigDecimal actualAmount,
            UsageSource usageSource,
            String errorCode,
            Instant createdAt,
            Instant updatedAt,
            Instant settledAt) {

        public PriceVersion priceSnapshot() {
            return new PriceVersion(
                    priceVersionId,
                    model,
                    normalizedModel,
                    provider,
                    model,
                    currency,
                    inputPricePer1M,
                    outputPricePer1M,
                    cacheCreationPricePer1M,
                    cacheReadPricePer1M,
                    createdAt,
                    null,
                    true);
        }
    }

    public record Settlement(
            UUID requestId,
            RequestStatus status,
            BigDecimal reservedAmount,
            BigDecimal actualAmount,
            String currency,
            BigDecimal balanceAfter,
            BigDecimal reservedAfter,
            boolean idempotentReplay) {
    }

    public record LedgerEntry(
            long id,
            long accountId,
            String appCode,
            UUID requestId,
            LedgerEntryType entryType,
            String idempotencyKey,
            BigDecimal balanceDelta,
            BigDecimal reservedDelta,
            BigDecimal balanceAfter,
            BigDecimal reservedAfter,
            String currency,
            String reason,
            Instant createdAt,
            UUID createdBy) {
    }
}
