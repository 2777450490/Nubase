package ai.nubase.ai.gateway.billing;

import ai.nubase.ai.gateway.billing.BillingExceptions.AccountNotFoundException;
import ai.nubase.ai.gateway.billing.BillingExceptions.AccountUnavailableException;
import ai.nubase.ai.gateway.billing.BillingExceptions.DuplicateRequestException;
import ai.nubase.ai.gateway.billing.BillingExceptions.InsufficientBalanceException;
import ai.nubase.ai.gateway.billing.BillingExceptions.InvalidRequestStateException;
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
import ai.nubase.ai.gateway.dto.TokenUsage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class BillingRepository {

    private static final String ACCOUNT_COLUMNS =
            "id, app_code, currency, balance, reserved_amount, credit_limit, status, version, created_at, updated_at";
    private static final String PRICE_COLUMNS =
            "id, model, normalized_model, provider, display_name, currency, input_price_per_1m, "
                    + "output_price_per_1m, cache_creation_input_price_per_1m, cache_read_input_price_per_1m, "
                    + "effective_from, effective_to, is_active";
    private static final String REQUEST_COLUMNS =
            "request_id, account_id, app_code, client_idempotency_key, model, normalized_model, provider, endpoint, "
                    + "status, currency, price_version_id, input_price_per_1m_snapshot, output_price_per_1m_snapshot, "
                    + "cache_creation_price_per_1m_snapshot, cache_read_price_per_1m_snapshot, estimated_input_tokens, "
                    + "reserved_output_tokens, input_tokens, output_tokens, cache_creation_input_tokens, "
                    + "cache_read_input_tokens, reserved_amount, actual_amount, usage_source, error_code, "
                    + "created_at, updated_at, settled_at";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public BillingRepository(
            @Qualifier("metadataJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("metadataTransactionManager") PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public BillingAccount upsertAccount(
            String appCode, String currency, BigDecimal creditLimit, AccountStatus status) {
        return required(tx.execute(ignored -> jdbc.queryForObject(
                "INSERT INTO public.ai_gateway_billing_accounts (app_code, currency, credit_limit, status) "
                        + "VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (app_code) DO UPDATE SET currency = EXCLUDED.currency, "
                        + "credit_limit = EXCLUDED.credit_limit, status = EXCLUDED.status, "
                        + "version = public.ai_gateway_billing_accounts.version + 1, updated_at = NOW() "
                        + "RETURNING " + ACCOUNT_COLUMNS,
                accountMapper, appCode, currency, creditLimit, status.name())));
    }

    public Optional<BillingAccount> findAccount(String appCode) {
        return first(jdbc.query(
                "SELECT " + ACCOUNT_COLUMNS + " FROM public.ai_gateway_billing_accounts WHERE app_code = ?",
                accountMapper, appCode));
    }

    public List<BillingAccount> listAccounts() {
        return jdbc.query(
                "SELECT " + ACCOUNT_COLUMNS + " FROM public.ai_gateway_billing_accounts ORDER BY created_at DESC",
                accountMapper);
    }

    public BillingAccount adjustBalance(
            String appCode,
            BigDecimal amount,
            LedgerEntryType type,
            String idempotencyKey,
            String reason,
            UUID createdBy) {
        return required(tx.execute(ignored -> {
            BillingAccount account = lockAccount(appCode);
            Optional<Long> existingLedgerAccount = findLedgerAccountId(idempotencyKey);
            if (existingLedgerAccount.isPresent()) {
                if (existingLedgerAccount.get() != account.id()) {
                    throw new IllegalArgumentException("idempotencyKey is already used by another billing account");
                }
                return account;
            }
            BillingAccount updated = jdbc.queryForObject(
                    "UPDATE public.ai_gateway_billing_accounts SET balance = balance + ?, "
                            + "version = version + 1, updated_at = NOW() WHERE id = ? RETURNING " + ACCOUNT_COLUMNS,
                    accountMapper, amount, account.id());
            insertLedger(updated, null, type, idempotencyKey, amount, BigDecimal.ZERO, reason, createdBy);
            return updated;
        }));
    }

    public PriceVersion publishPrice(
            String model,
            String normalizedModel,
            String provider,
            String displayName,
            String currency,
            BigDecimal inputPrice,
            BigDecimal outputPrice,
            BigDecimal cacheCreationPrice,
            BigDecimal cacheReadPrice,
            Instant effectiveFrom,
            UUID createdBy) {
        return required(tx.execute(ignored -> {
            jdbc.update(
                    "UPDATE public.ai_gateway_model_price_versions SET effective_to = ?, is_active = FALSE "
                            + "WHERE normalized_model = ? AND currency = ? AND is_active = TRUE AND effective_to IS NULL",
                    Timestamp.from(effectiveFrom), normalizedModel, currency);
            return jdbc.queryForObject(
                    "INSERT INTO public.ai_gateway_model_price_versions "
                            + "(model, normalized_model, provider, display_name, currency, input_price_per_1m, "
                            + " output_price_per_1m, cache_creation_input_price_per_1m, "
                            + " cache_read_input_price_per_1m, effective_from, created_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + PRICE_COLUMNS,
                    priceMapper, model, normalizedModel, provider, displayName, currency,
                    inputPrice, outputPrice, cacheCreationPrice, cacheReadPrice,
                    Timestamp.from(effectiveFrom), createdBy);
        }));
    }

    public Optional<PriceVersion> findActivePrice(String normalizedModel, String currency, Instant at) {
        return first(jdbc.query(
                "SELECT " + PRICE_COLUMNS + " FROM public.ai_gateway_model_price_versions "
                        + "WHERE normalized_model = ? AND currency = ? AND is_active = TRUE "
                        + "AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?) "
                        + "ORDER BY effective_from DESC LIMIT 1",
                priceMapper, normalizedModel, currency, Timestamp.from(at), Timestamp.from(at)));
    }

    public List<PriceVersion> listPrices(boolean activeOnly) {
        String where = activeOnly ? " WHERE is_active = TRUE AND effective_to IS NULL" : "";
        return jdbc.query(
                "SELECT " + PRICE_COLUMNS + " FROM public.ai_gateway_model_price_versions" + where
                        + " ORDER BY normalized_model, effective_from DESC",
                priceMapper);
    }

    public Reservation reserve(ReservePlan plan) {
        return required(tx.execute(ignored -> {
            Optional<BillingRequest> existing = findRequest(plan.requestId(), false);
            if (existing.isPresent()) {
                return toReservation(existing.get(), true);
            }

            BillingAccount account = findAccount(plan.appCode())
                    .orElseThrow(() -> new AccountNotFoundException(plan.appCode()));
            if (account.status() != AccountStatus.ACTIVE) {
                throw new AccountUnavailableException(plan.appCode(), account.status().name());
            }
            if (!account.currency().equals(plan.price().currency())) {
                throw new IllegalArgumentException("Billing account currency does not match price currency");
            }

            int inserted = jdbc.update(
                    "INSERT INTO public.ai_gateway_billing_requests "
                            + "(request_id, account_id, app_code, client_idempotency_key, model, normalized_model, "
                            + " provider, endpoint, status, currency, price_version_id, input_price_per_1m_snapshot, "
                            + " output_price_per_1m_snapshot, cache_creation_price_per_1m_snapshot, "
                            + " cache_read_price_per_1m_snapshot, estimated_input_tokens, reserved_output_tokens, "
                            + " reserved_amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT DO NOTHING",
                    plan.requestId(), account.id(), plan.appCode(), blankToNull(plan.clientIdempotencyKey()),
                    plan.model(), plan.normalizedModel(), plan.provider(), plan.endpoint(), plan.price().currency(),
                    plan.price().id(), plan.price().inputPricePer1M(), plan.price().outputPricePer1M(),
                    plan.price().cacheCreationPricePer1M(), plan.price().cacheReadPricePer1M(),
                    plan.estimatedInputTokens(), plan.reservedOutputTokens(), plan.reservedAmount());
            if (inserted == 0) {
                Optional<BillingRequest> byRequest = findRequest(plan.requestId(), false);
                if (byRequest.isPresent()) {
                    return toReservation(byRequest.get(), true);
                }
                UUID duplicate = findRequestIdByClientKey(plan.appCode(), plan.clientIdempotencyKey())
                        .orElse(plan.requestId());
                throw new DuplicateRequestException(duplicate);
            }

            int updated = jdbc.update(
                    "UPDATE public.ai_gateway_billing_accounts "
                            + "SET reserved_amount = reserved_amount + ?, version = version + 1, updated_at = NOW() "
                            + "WHERE id = ? AND status = 'ACTIVE' AND currency = ? "
                            + "AND balance + credit_limit - reserved_amount >= ?",
                    plan.reservedAmount(), account.id(), plan.price().currency(), plan.reservedAmount());
            if (updated != 1) {
                BillingAccount latest = findAccount(plan.appCode())
                        .orElseThrow(() -> new AccountNotFoundException(plan.appCode()));
                if (latest.status() != AccountStatus.ACTIVE) {
                    throw new AccountUnavailableException(plan.appCode(), latest.status().name());
                }
                throw new InsufficientBalanceException(plan.appCode(), plan.reservedAmount());
            }

            BillingAccount after = findAccount(plan.appCode()).orElseThrow();
            insertLedger(
                    after,
                    plan.requestId(),
                    LedgerEntryType.RESERVE,
                    plan.requestId() + ":RESERVE",
                    BigDecimal.ZERO,
                    plan.reservedAmount(),
                    "Gateway request reservation",
                    null);
            return new Reservation(
                    plan.requestId(), account.id(), plan.appCode(), plan.model(), plan.reservedAmount(),
                    plan.price().currency(), RequestStatus.RESERVED, false);
        }));
    }

    public Optional<BillingRequest> findRequest(UUID requestId) {
        return findRequest(requestId, false);
    }

    public List<BillingRequest> listRequests(String appCode, RequestStatus status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT ").append(REQUEST_COLUMNS)
                .append(" FROM public.ai_gateway_billing_requests WHERE 1 = 1");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (appCode != null && !appCode.isBlank()) {
            sql.append(" AND app_code = ?");
            args.add(appCode);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), requestMapper, args.toArray());
    }

    public List<LedgerEntry> listLedger(String appCode, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT l.id, l.account_id, a.app_code, l.request_id, l.entry_type, l.idempotency_key, "
                        + "l.balance_delta, l.reserved_delta, l.balance_after, l.reserved_after, l.currency, "
                        + "l.reason, l.created_at, l.created_by FROM public.ai_gateway_billing_ledger l "
                        + "JOIN public.ai_gateway_billing_accounts a ON a.id = l.account_id WHERE 1 = 1");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (appCode != null && !appCode.isBlank()) {
            sql.append(" AND a.app_code = ?");
            args.add(appCode);
        }
        sql.append(" ORDER BY l.created_at DESC, l.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), ledgerMapper, args.toArray());
    }

    public Settlement settle(UUID requestId, TokenUsage usage, UsageSource source) {
        return required(tx.execute(ignored -> {
            BillingRequest request = findRequest(requestId, true)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown billing request " + requestId));
            if (request.status() == RequestStatus.SETTLED) {
                BillingAccount account = findAccount(request.appCode()).orElseThrow();
                return new Settlement(
                        requestId, request.status(), request.reservedAmount(), request.actualAmount(), request.currency(),
                        account.balance(), account.reservedAmount(), true);
            }
            if (request.status() == RequestStatus.RELEASED) {
                throw new InvalidRequestStateException(requestId, request.status().name());
            }

            BigDecimal actual = BillingCostCalculator.actual(request.priceSnapshot(), usage);
            BillingAccount after = jdbc.queryForObject(
                    "UPDATE public.ai_gateway_billing_accounts SET "
                            + "balance = balance - ?, reserved_amount = reserved_amount - ?, "
                            + "status = CASE WHEN status = 'ACTIVE' "
                            + "AND balance - ? + credit_limit - (reserved_amount - ?) < 0 "
                            + "THEN 'SUSPENDED' ELSE status END, "
                            + "version = version + 1, updated_at = NOW() "
                            + "WHERE id = ? AND reserved_amount >= ? RETURNING " + ACCOUNT_COLUMNS,
                    accountMapper,
                    actual, request.reservedAmount(), actual, request.reservedAmount(),
                    request.accountId(), request.reservedAmount());
            if (after == null) {
                throw new IllegalStateException("Reserved amount invariant failed for request " + requestId);
            }

            TokenUsage safeUsage = usage == null ? TokenUsage.empty() : usage;
            jdbc.update(
                    "UPDATE public.ai_gateway_billing_requests SET status = 'SETTLED', input_tokens = ?, "
                            + "output_tokens = ?, cache_creation_input_tokens = ?, cache_read_input_tokens = ?, "
                            + "actual_amount = ?, usage_source = ?, error_code = NULL, settled_at = NOW(), "
                            + "updated_at = NOW() WHERE request_id = ?",
                    nonNegative(safeUsage.getInputTokens()), nonNegative(safeUsage.getOutputTokens()),
                    nonNegative(safeUsage.getCacheCreationInputTokens()),
                    nonNegative(safeUsage.getCacheReadInputTokens()),
                    actual, source.name(), requestId);
            insertLedger(
                    after,
                    requestId,
                    LedgerEntryType.SETTLE,
                    requestId + ":SETTLE",
                    actual.negate(),
                    request.reservedAmount().negate(),
                    "Gateway request settlement",
                    null);
            return new Settlement(
                    requestId, RequestStatus.SETTLED, request.reservedAmount(), actual, request.currency(),
                    after.balance(), after.reservedAmount(), false);
        }));
    }

    public boolean release(UUID requestId, String reason, UUID createdBy) {
        return Boolean.TRUE.equals(tx.execute(ignored -> {
            Optional<BillingRequest> maybeRequest = findRequest(requestId, true);
            if (maybeRequest.isEmpty()) {
                return false;
            }
            BillingRequest request = maybeRequest.get();
            if (request.status() == RequestStatus.RELEASED) {
                return true;
            }
            if (request.status() == RequestStatus.SETTLED) {
                throw new InvalidRequestStateException(requestId, request.status().name());
            }
            BillingAccount after = jdbc.queryForObject(
                    "UPDATE public.ai_gateway_billing_accounts SET reserved_amount = reserved_amount - ?, "
                            + "version = version + 1, updated_at = NOW() "
                            + "WHERE id = ? AND reserved_amount >= ? RETURNING " + ACCOUNT_COLUMNS,
                    accountMapper, request.reservedAmount(), request.accountId(), request.reservedAmount());
            if (after == null) {
                throw new IllegalStateException("Reserved amount invariant failed for request " + requestId);
            }
            jdbc.update(
                    "UPDATE public.ai_gateway_billing_requests SET status = 'RELEASED', "
                            + "error_code = 'manually_released', "
                            + "updated_at = NOW() WHERE request_id = ?",
                    requestId);
            insertLedger(
                    after,
                    requestId,
                    LedgerEntryType.RELEASE,
                    requestId + ":RELEASE",
                    BigDecimal.ZERO,
                    request.reservedAmount().negate(),
                    reason,
                    createdBy);
            return true;
        }));
    }

    public boolean markReconcileRequired(UUID requestId, String errorCode) {
        return jdbc.update(
                "UPDATE public.ai_gateway_billing_requests SET status = 'RECONCILE_REQUIRED', error_code = ?, "
                        + "updated_at = NOW() WHERE request_id = ? AND status = 'RESERVED'",
                errorCode, requestId) == 1;
    }

    private BillingAccount lockAccount(String appCode) {
        return first(jdbc.query(
                "SELECT " + ACCOUNT_COLUMNS + " FROM public.ai_gateway_billing_accounts "
                        + "WHERE app_code = ? FOR UPDATE",
                accountMapper, appCode)).orElseThrow(() -> new AccountNotFoundException(appCode));
    }

    private Optional<BillingRequest> findRequest(UUID requestId, boolean forUpdate) {
        String suffix = forUpdate ? " FOR UPDATE" : "";
        return first(jdbc.query(
                "SELECT " + REQUEST_COLUMNS + " FROM public.ai_gateway_billing_requests WHERE request_id = ?" + suffix,
                requestMapper, requestId));
    }

    private Optional<UUID> findRequestIdByClientKey(String appCode, String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return first(jdbc.query(
                "SELECT request_id FROM public.ai_gateway_billing_requests "
                        + "WHERE app_code = ? AND client_idempotency_key = ?",
                (rs, rowNum) -> rs.getObject(1, UUID.class), appCode, key));
    }

    public int markExpiredReservations(Instant cutoff) {
        return jdbc.update(
                "UPDATE public.ai_gateway_billing_requests SET status = 'RECONCILE_REQUIRED', "
                        + "error_code = 'reservation_ttl_expired', updated_at = NOW() "
                        + "WHERE status = 'RESERVED' AND updated_at < ?",
                Timestamp.from(cutoff));
    }

    private Optional<Long> findLedgerAccountId(String idempotencyKey) {
        return first(jdbc.query(
                "SELECT account_id FROM public.ai_gateway_billing_ledger WHERE idempotency_key = ?",
                (rs, rowNum) -> rs.getLong(1), idempotencyKey));
    }

    private void insertLedger(
            BillingAccount account,
            UUID requestId,
            LedgerEntryType type,
            String idempotencyKey,
            BigDecimal balanceDelta,
            BigDecimal reservedDelta,
            String reason,
            UUID createdBy) {
        jdbc.update(
                "INSERT INTO public.ai_gateway_billing_ledger "
                        + "(account_id, request_id, entry_type, idempotency_key, balance_delta, reserved_delta, "
                        + " balance_after, reserved_after, currency, reason, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                account.id(), requestId, type.name(), idempotencyKey, balanceDelta, reservedDelta,
                account.balance(), account.reservedAmount(), account.currency(), reason, createdBy);
    }

    private static Reservation toReservation(BillingRequest request, boolean replay) {
        return new Reservation(
                request.requestId(), request.accountId(), request.appCode(), request.model(),
                request.reservedAmount(), request.currency(), request.status(), replay);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static long nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value.longValue());
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static <T> T required(T value) {
        if (value == null) {
            throw new IllegalStateException("Billing transaction returned no result");
        }
        return value;
    }

    private final RowMapper<BillingAccount> accountMapper = (rs, rowNum) -> new BillingAccount(
            rs.getLong("id"),
            rs.getString("app_code"),
            rs.getString("currency"),
            rs.getBigDecimal("balance"),
            rs.getBigDecimal("reserved_amount"),
            rs.getBigDecimal("credit_limit"),
            AccountStatus.valueOf(rs.getString("status")),
            rs.getLong("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private final RowMapper<PriceVersion> priceMapper = (rs, rowNum) -> new PriceVersion(
            rs.getLong("id"),
            rs.getString("model"),
            rs.getString("normalized_model"),
            rs.getString("provider"),
            rs.getString("display_name"),
            rs.getString("currency"),
            rs.getBigDecimal("input_price_per_1m"),
            rs.getBigDecimal("output_price_per_1m"),
            rs.getBigDecimal("cache_creation_input_price_per_1m"),
            rs.getBigDecimal("cache_read_input_price_per_1m"),
            instant(rs, "effective_from"),
            nullableInstant(rs, "effective_to"),
            rs.getBoolean("is_active"));

    private final RowMapper<BillingRequest> requestMapper = (rs, rowNum) -> new BillingRequest(
            rs.getObject("request_id", UUID.class),
            rs.getLong("account_id"),
            rs.getString("app_code"),
            rs.getString("client_idempotency_key"),
            rs.getString("model"),
            rs.getString("normalized_model"),
            rs.getString("provider"),
            rs.getString("endpoint"),
            RequestStatus.valueOf(rs.getString("status")),
            rs.getString("currency"),
            rs.getLong("price_version_id"),
            rs.getBigDecimal("input_price_per_1m_snapshot"),
            rs.getBigDecimal("output_price_per_1m_snapshot"),
            rs.getBigDecimal("cache_creation_price_per_1m_snapshot"),
            rs.getBigDecimal("cache_read_price_per_1m_snapshot"),
            rs.getLong("estimated_input_tokens"),
            rs.getLong("reserved_output_tokens"),
            rs.getLong("input_tokens"),
            rs.getLong("output_tokens"),
            rs.getLong("cache_creation_input_tokens"),
            rs.getLong("cache_read_input_tokens"),
            rs.getBigDecimal("reserved_amount"),
            rs.getBigDecimal("actual_amount"),
            enumOrNull(UsageSource.class, rs.getString("usage_source")),
            rs.getString("error_code"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            nullableInstant(rs, "settled_at"));

    private final RowMapper<LedgerEntry> ledgerMapper = (rs, rowNum) -> new LedgerEntry(
            rs.getLong("id"),
            rs.getLong("account_id"),
            rs.getString("app_code"),
            rs.getObject("request_id", UUID.class),
            LedgerEntryType.valueOf(rs.getString("entry_type")),
            rs.getString("idempotency_key"),
            rs.getBigDecimal("balance_delta"),
            rs.getBigDecimal("reserved_delta"),
            rs.getBigDecimal("balance_after"),
            rs.getBigDecimal("reserved_after"),
            rs.getString("currency"),
            rs.getString("reason"),
            instant(rs, "created_at"),
            rs.getObject("created_by", UUID.class));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        if (value == null) {
            throw new SQLException(column + " must not be null");
        }
        return value.toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    }
}
