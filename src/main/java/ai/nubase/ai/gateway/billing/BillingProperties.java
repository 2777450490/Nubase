package ai.nubase.ai.gateway.billing;

import lombok.Data;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "nubase.ai-gateway.billing")
public class BillingProperties {

    /** Billing remains shadow-disabled until accounts and prices are configured. */
    private boolean enabled = false;

    @Min(1)
    private int defaultMaxOutputTokens = 4096;

    @Min(1)
    private int maximumOutputTokens = 131072;

    @NotNull
    @DecimalMin("1.0")
    private BigDecimal reservationSafetyMultiplier = new BigDecimal("1.10");

    @NotNull
    private Duration reservationTtl = Duration.ofMinutes(30);

    @Min(1024)
    private int maximumRequestBytes = 20 * 1024 * 1024;

    @AssertTrue(message = "maximumOutputTokens must not be lower than defaultMaxOutputTokens")
    public boolean isOutputTokenRangeValid() {
        return maximumOutputTokens >= defaultMaxOutputTokens;
    }

    @AssertTrue(message = "reservationTtl must be positive")
    public boolean isReservationTtlValid() {
        return reservationTtl != null && !reservationTtl.isZero() && !reservationTtl.isNegative();
    }
}
