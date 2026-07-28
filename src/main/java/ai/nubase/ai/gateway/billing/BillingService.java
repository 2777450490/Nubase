package ai.nubase.ai.gateway.billing;

import ai.nubase.ai.gateway.billing.BillingExceptions.BillingException;
import ai.nubase.ai.gateway.billing.BillingExceptions.PriceNotFoundException;
import ai.nubase.ai.gateway.billing.BillingModels.AccountStatus;
import ai.nubase.ai.gateway.billing.BillingModels.BillingAccount;
import ai.nubase.ai.gateway.billing.BillingModels.BillingRequest;
import ai.nubase.ai.gateway.billing.BillingModels.LedgerEntryType;
import ai.nubase.ai.gateway.billing.BillingModels.LedgerEntry;
import ai.nubase.ai.gateway.billing.BillingModels.PriceVersion;
import ai.nubase.ai.gateway.billing.BillingModels.RequestStatus;
import ai.nubase.ai.gateway.billing.BillingModels.Reservation;
import ai.nubase.ai.gateway.billing.BillingModels.ReservePlan;
import ai.nubase.ai.gateway.billing.BillingModels.Settlement;
import ai.nubase.ai.gateway.billing.BillingModels.UsageSource;
import ai.nubase.ai.gateway.dto.ApiUsageRecord;
import ai.nubase.ai.gateway.dto.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final BillingRepository repository;
    private final BillingProperties properties;

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public Reservation reserve(
            UUID requestId,
            String appCode,
            String clientIdempotencyKey,
            String model,
            String endpoint,
            long estimatedInputTokens,
            long requestedOutputTokens) {
        requireText(appCode, "appCode");
        requireText(model, "model");
        long safeInput = Math.max(0, estimatedInputTokens);
        long safeOutput = normalizeOutputLimit(requestedOutputTokens);
        String normalizedModel = normalizeModel(model);

        BillingAccount account = repository.findAccount(appCode)
                .orElseThrow(() -> new BillingExceptions.AccountNotFoundException(appCode));
        PriceVersion price = repository.findActivePrice(normalizedModel, account.currency(), Instant.now())
                .orElseThrow(() -> new PriceNotFoundException(model, account.currency()));
        BigDecimal reserved = BillingCostCalculator.reserve(
                price, safeInput, safeOutput, properties.getReservationSafetyMultiplier());
        ReservePlan plan = new ReservePlan(
                requestId,
                appCode,
                normalizeIdempotencyKey(clientIdempotencyKey),
                model.trim(),
                normalizedModel,
                price.provider(),
                endpoint,
                safeInput,
                safeOutput,
                reserved,
                price);
        return repository.reserve(plan);
    }

    public void recordUsage(ApiUsageRecord record) {
        if (!properties.isEnabled() || record == null || record.getRequestId() == null) {
            return;
        }
        UUID requestId;
        try {
            requestId = UUID.fromString(record.getRequestId());
        } catch (IllegalArgumentException ignored) {
            log.warn("billing settlement skipped: requestId is not a UUID ({})", record.getRequestId());
            return;
        }
        Optional<BillingRequest> request = repository.findRequest(requestId);
        if (request.isEmpty()) {
            return;
        }

        TokenUsage usage = record.getTokenUsage() == null ? TokenUsage.empty() : record.getTokenUsage();
        if (hasUsage(usage)) {
            Settlement settlement = repository.settle(requestId, usage, UsageSource.UPSTREAM);
            log.info(
                    "billing.settled requestId={} appCode={} model={} amount={} currency={} balanceAfter={}",
                    requestId,
                    request.get().appCode(),
                    request.get().model(),
                    settlement.actualAmount(),
                    settlement.currency(),
                    settlement.balanceAfter());
            return;
        }

        int status = record.getStatusCode() == null ? 0 : record.getStatusCode();
        String reason = status >= 400
                ? "upstream_error_without_usage"
                : "successful_response_without_usage";
        repository.markReconcileRequired(requestId, reason);
        log.error("billing.reconcile_required requestId={} reason={}", requestId, reason);
    }

    public void markAdmissionUncertain(UUID requestId, String reason) {
        if (!properties.isEnabled() || requestId == null) {
            return;
        }
        repository.markReconcileRequired(requestId, reason);
    }

    public BillingAccount upsertAccount(
            String appCode, String currency, BigDecimal creditLimit, AccountStatus status) {
        requireText(appCode, "appCode");
        String normalizedCurrency = normalizeCurrency(currency);
        BigDecimal safeCredit = nonNegative(creditLimit, "creditLimit");
        repository.findAccount(appCode).ifPresent(existing -> {
            if (!existing.currency().equals(normalizedCurrency)
                    && (existing.balance().signum() != 0 || existing.reservedAmount().signum() != 0)) {
                throw new IllegalArgumentException("currency cannot change while balance or reservations are non-zero");
            }
        });
        return repository.upsertAccount(
                appCode.trim(), normalizedCurrency, safeCredit, status == null ? AccountStatus.ACTIVE : status);
    }

    public BillingAccount adjustBalance(
            String appCode,
            BigDecimal amount,
            LedgerEntryType type,
            String idempotencyKey,
            String reason,
            UUID createdBy) {
        requireText(appCode, "appCode");
        requireText(idempotencyKey, "idempotencyKey");
        if (amount == null || amount.signum() == 0) {
            throw new IllegalArgumentException("amount must not be zero");
        }
        LedgerEntryType safeType = type == null ? LedgerEntryType.ADJUSTMENT : type;
        if (safeType != LedgerEntryType.TOP_UP
                && safeType != LedgerEntryType.ADJUSTMENT
                && safeType != LedgerEntryType.REFUND) {
            throw new IllegalArgumentException("Unsupported manual ledger entry type " + safeType);
        }
        return repository.adjustBalance(
                appCode.trim(), amount, safeType, idempotencyKey.trim(), trimToNull(reason), createdBy);
    }

    public PriceVersion publishPrice(
            String model,
            String provider,
            String displayName,
            String currency,
            BigDecimal inputPrice,
            BigDecimal outputPrice,
            BigDecimal cacheCreationPrice,
            BigDecimal cacheReadPrice,
            Instant effectiveFrom,
            UUID createdBy) {
        requireText(model, "model");
        requireText(provider, "provider");
        String normalizedCurrency = normalizeCurrency(currency);
        Instant effective = effectiveFrom == null ? Instant.now() : effectiveFrom;
        if (effective.isAfter(Instant.now().plusSeconds(1))) {
            throw new IllegalArgumentException("future price activation is not supported in the first billing release");
        }
        String normalizedModel = normalizeModel(model);
        repository.findActivePrice(normalizedModel, normalizedCurrency, Instant.now()).ifPresent(current -> {
            if (!effective.isAfter(current.effectiveFrom())) {
                throw new IllegalArgumentException("effectiveFrom must be later than the current price version");
            }
        });
        return repository.publishPrice(
                model.trim(),
                normalizedModel,
                provider.trim().toUpperCase(Locale.ROOT),
                trimToNull(displayName),
                normalizedCurrency,
                nonNegative(inputPrice, "inputPrice"),
                nonNegative(outputPrice, "outputPrice"),
                nonNegative(cacheCreationPrice, "cacheCreationPrice"),
                nonNegative(cacheReadPrice, "cacheReadPrice"),
                effective,
                createdBy);
    }

    public List<BillingAccount> listAccounts() {
        return repository.listAccounts();
    }

    public Optional<BillingAccount> findAccount(String appCode) {
        return repository.findAccount(appCode);
    }

    public List<PriceVersion> listPrices(boolean activeOnly) {
        return repository.listPrices(activeOnly);
    }

    public List<BillingRequest> listRequests(String appCode, RequestStatus status, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        return repository.listRequests(trimToNull(appCode), status, safeSize, safePage * safeSize);
    }

    public List<LedgerEntry> listLedger(String appCode, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        return repository.listLedger(trimToNull(appCode), safeSize, safePage * safeSize);
    }

    public boolean release(UUID requestId, String reason, UUID createdBy) {
        return repository.release(requestId, requireReason(reason), createdBy);
    }

    private long normalizeOutputLimit(long requested) {
        long candidate = requested > 0 ? requested : properties.getDefaultMaxOutputTokens();
        return Math.min(candidate, properties.getMaximumOutputTokens());
    }

    public static String normalizeModel(String model) {
        return model == null ? null : model.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeCurrency(String currency) {
        String normalized = currency == null || currency.isBlank() ? "USD" : currency.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3,8}")) {
            throw new IllegalArgumentException("currency must contain 3 to 8 uppercase letters");
        }
        return normalized;
    }

    private static String normalizeIdempotencyKey(String key) {
        String normalized = trimToNull(key);
        if (normalized != null && normalized.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must not exceed 255 characters");
        }
        return normalized;
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value;
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return normalized;
    }

    private static boolean hasUsage(TokenUsage usage) {
        return positive(usage.getInputTokens())
                || positive(usage.getOutputTokens())
                || positive(usage.getCacheCreationInputTokens())
                || positive(usage.getCacheReadInputTokens());
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static String requireReason(String reason) {
        requireText(reason, "reason");
        return reason.trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
