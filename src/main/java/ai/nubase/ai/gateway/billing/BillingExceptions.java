package ai.nubase.ai.gateway.billing;

import java.math.BigDecimal;
import java.util.UUID;

public final class BillingExceptions {

    private BillingExceptions() {
    }

    public static class BillingException extends RuntimeException {
        private final String code;

        public BillingException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    public static final class AccountNotFoundException extends BillingException {
        public AccountNotFoundException(String appCode) {
            super("billing_account_not_found", "Billing account is not configured for project " + appCode);
        }
    }

    public static final class PriceNotFoundException extends BillingException {
        public PriceNotFoundException(String model, String currency) {
            super("model_not_priced", "No active " + currency + " price is configured for model " + model);
        }
    }

    public static final class InsufficientBalanceException extends BillingException {
        public InsufficientBalanceException(String appCode, BigDecimal required) {
            super("insufficient_balance", "Insufficient available balance for project " + appCode
                    + "; required reservation=" + required.toPlainString());
        }
    }

    public static final class AccountUnavailableException extends BillingException {
        public AccountUnavailableException(String appCode, String status) {
            super("billing_account_unavailable", "Billing account " + appCode + " is " + status);
        }
    }

    public static final class DuplicateRequestException extends BillingException {
        private final UUID existingRequestId;

        public DuplicateRequestException(UUID existingRequestId) {
            super("duplicate_idempotency_key", "Idempotency key already belongs to request " + existingRequestId);
            this.existingRequestId = existingRequestId;
        }

        public UUID getExistingRequestId() {
            return existingRequestId;
        }
    }

    public static final class InvalidRequestStateException extends BillingException {
        public InvalidRequestStateException(UUID requestId, String status) {
            super("invalid_billing_request_state", "Billing request " + requestId + " is " + status);
        }
    }
}
