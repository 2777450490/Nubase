package ai.nubase.ai.gateway.billing;

import ai.nubase.ai.gateway.billing.BillingModels.PriceVersion;
import ai.nubase.ai.gateway.dto.TokenUsage;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class BillingCostCalculator {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final int MONEY_SCALE = 8;

    private BillingCostCalculator() {
    }

    public static BigDecimal reserve(
            PriceVersion price,
            long estimatedInputTokens,
            long maximumOutputTokens,
            BigDecimal safetyMultiplier) {
        BigDecimal conservativeInputPrice = price.inputPricePer1M().max(price.cacheCreationPricePer1M());
        BigDecimal base = component(estimatedInputTokens, conservativeInputPrice)
                .add(component(maximumOutputTokens, price.outputPricePer1M()));
        BigDecimal multiplier = safetyMultiplier == null ? BigDecimal.ONE : safetyMultiplier;
        return base.multiply(multiplier).setScale(MONEY_SCALE, RoundingMode.CEILING);
    }

    public static BigDecimal actual(PriceVersion price, TokenUsage usage) {
        if (usage == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        }
        return component(nonNegative(usage.getInputTokens()), price.inputPricePer1M())
                .add(component(nonNegative(usage.getOutputTokens()), price.outputPricePer1M()))
                .add(component(nonNegative(usage.getCacheCreationInputTokens()), price.cacheCreationPricePer1M()))
                .add(component(nonNegative(usage.getCacheReadInputTokens()), price.cacheReadPricePer1M()))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal component(long tokens, BigDecimal pricePer1M) {
        if (tokens <= 0 || pricePer1M == null || pricePer1M.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(tokens)
                .multiply(pricePer1M)
                .divide(ONE_MILLION, 16, RoundingMode.HALF_UP);
    }

    private static long nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value.longValue());
    }
}
