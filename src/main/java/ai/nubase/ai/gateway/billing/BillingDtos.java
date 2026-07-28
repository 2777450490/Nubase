package ai.nubase.ai.gateway.billing;

import ai.nubase.ai.gateway.billing.BillingModels.AccountStatus;
import ai.nubase.ai.gateway.billing.BillingModels.LedgerEntryType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class BillingDtos {

    private BillingDtos() {
    }

    public record AccountUpsertRequest(
            @Pattern(regexp = "^[A-Za-z]{3,8}$") String currency,
            @DecimalMin("0") BigDecimal creditLimit,
            AccountStatus status) {
    }

    public record BalanceAdjustmentRequest(
            @NotNull BigDecimal amount,
            LedgerEntryType type,
            @NotBlank @Size(max = 320) String idempotencyKey,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record PricePublishRequest(
            @NotBlank @Size(max = 160) String model,
            @NotBlank @Size(max = 32) String provider,
            @Size(max = 160) String displayName,
            @Pattern(regexp = "^[A-Za-z]{3,8}$") String currency,
            @NotNull @DecimalMin("0") BigDecimal inputPricePer1M,
            @NotNull @DecimalMin("0") BigDecimal outputPricePer1M,
            @DecimalMin("0") BigDecimal cacheCreationPricePer1M,
            @DecimalMin("0") BigDecimal cacheReadPricePer1M,
            Instant effectiveFrom) {
    }

    public record ReleaseRequest(@NotBlank @Size(max = 1000) String reason) {
    }
}
