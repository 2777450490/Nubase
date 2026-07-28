-- Central, financially authoritative AI gateway billing ledger.
--
-- Unlike per-tenant ai_gateway usage tables, these tables live in the metadata
-- database so reservation and settlement can be committed in one short local
-- transaction. Usage dashboards remain projections; account balance is derived
-- only from this ledger and the account row updated in the same transaction.

CREATE TABLE IF NOT EXISTS public.ai_gateway_billing_accounts (
    id              BIGSERIAL PRIMARY KEY,
    app_code        VARCHAR(128)  NOT NULL UNIQUE,
    currency        VARCHAR(8)    NOT NULL DEFAULT 'USD',
    balance         NUMERIC(24,8) NOT NULL DEFAULT 0,
    reserved_amount NUMERIC(24,8) NOT NULL DEFAULT 0,
    credit_limit    NUMERIC(24,8) NOT NULL DEFAULT 0,
    status          VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_billing_accounts_currency
        CHECK (currency ~ '^[A-Z]{3,8}$'),
    CONSTRAINT ck_billing_accounts_reserved_nonnegative
        CHECK (reserved_amount >= 0),
    CONSTRAINT ck_billing_accounts_credit_nonnegative
        CHECK (credit_limit >= 0),
    CONSTRAINT ck_billing_accounts_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

CREATE INDEX IF NOT EXISTS idx_billing_accounts_status
    ON public.ai_gateway_billing_accounts (status);

CREATE TABLE IF NOT EXISTS public.ai_gateway_model_price_versions (
    id                                  BIGSERIAL PRIMARY KEY,
    model                               VARCHAR(160)  NOT NULL,
    normalized_model                    VARCHAR(160)  NOT NULL,
    provider                            VARCHAR(32)   NOT NULL,
    display_name                        VARCHAR(160),
    currency                            VARCHAR(8)    NOT NULL DEFAULT 'USD',
    input_price_per_1m                  NUMERIC(24,8) NOT NULL,
    output_price_per_1m                 NUMERIC(24,8) NOT NULL,
    cache_creation_input_price_per_1m   NUMERIC(24,8) NOT NULL DEFAULT 0,
    cache_read_input_price_per_1m       NUMERIC(24,8) NOT NULL DEFAULT 0,
    effective_from                      TIMESTAMPTZ   NOT NULL,
    effective_to                        TIMESTAMPTZ,
    is_active                           BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at                          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by                          UUID,
    CONSTRAINT ck_model_prices_currency
        CHECK (currency ~ '^[A-Z]{3,8}$'),
    CONSTRAINT ck_model_prices_nonnegative
        CHECK (input_price_per_1m >= 0
            AND output_price_per_1m >= 0
            AND cache_creation_input_price_per_1m >= 0
            AND cache_read_input_price_per_1m >= 0),
    CONSTRAINT ck_model_prices_effective_range
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_model_prices_current
    ON public.ai_gateway_model_price_versions (normalized_model, currency)
    WHERE is_active = TRUE AND effective_to IS NULL;

CREATE INDEX IF NOT EXISTS idx_model_prices_lookup
    ON public.ai_gateway_model_price_versions
        (normalized_model, currency, effective_from DESC)
    WHERE is_active = TRUE;

CREATE TABLE IF NOT EXISTS public.ai_gateway_billing_requests (
    request_id                              UUID          PRIMARY KEY,
    account_id                              BIGINT        NOT NULL
        REFERENCES public.ai_gateway_billing_accounts (id) ON DELETE RESTRICT,
    app_code                                VARCHAR(128)  NOT NULL,
    client_idempotency_key                  VARCHAR(255),
    model                                   VARCHAR(160)  NOT NULL,
    normalized_model                        VARCHAR(160)  NOT NULL,
    provider                                VARCHAR(32)   NOT NULL,
    endpoint                                VARCHAR(255),
    status                                  VARCHAR(32)   NOT NULL,
    currency                                VARCHAR(8)    NOT NULL,
    price_version_id                        BIGINT        NOT NULL
        REFERENCES public.ai_gateway_model_price_versions (id) ON DELETE RESTRICT,
    input_price_per_1m_snapshot              NUMERIC(24,8) NOT NULL,
    output_price_per_1m_snapshot             NUMERIC(24,8) NOT NULL,
    cache_creation_price_per_1m_snapshot     NUMERIC(24,8) NOT NULL,
    cache_read_price_per_1m_snapshot         NUMERIC(24,8) NOT NULL,
    estimated_input_tokens                  BIGINT        NOT NULL DEFAULT 0,
    reserved_output_tokens                  BIGINT        NOT NULL DEFAULT 0,
    input_tokens                            BIGINT        NOT NULL DEFAULT 0,
    output_tokens                           BIGINT        NOT NULL DEFAULT 0,
    cache_creation_input_tokens             BIGINT        NOT NULL DEFAULT 0,
    cache_read_input_tokens                 BIGINT        NOT NULL DEFAULT 0,
    reserved_amount                         NUMERIC(24,8) NOT NULL,
    actual_amount                           NUMERIC(24,8),
    usage_source                            VARCHAR(32),
    error_code                              VARCHAR(64),
    created_at                              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    settled_at                              TIMESTAMPTZ,
    CONSTRAINT ck_billing_requests_status
        CHECK (status IN ('RESERVED', 'SETTLED', 'RELEASED', 'RECONCILE_REQUIRED')),
    CONSTRAINT ck_billing_requests_tokens_nonnegative
        CHECK (estimated_input_tokens >= 0
            AND reserved_output_tokens >= 0
            AND input_tokens >= 0
            AND output_tokens >= 0
            AND cache_creation_input_tokens >= 0
            AND cache_read_input_tokens >= 0),
    CONSTRAINT ck_billing_requests_amounts_nonnegative
        CHECK (reserved_amount >= 0 AND (actual_amount IS NULL OR actual_amount >= 0))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_billing_requests_client_idempotency
    ON public.ai_gateway_billing_requests (app_code, client_idempotency_key)
    WHERE client_idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_billing_requests_account_created
    ON public.ai_gateway_billing_requests (account_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_billing_requests_reconcile
    ON public.ai_gateway_billing_requests (status, updated_at)
    WHERE status IN ('RESERVED', 'RECONCILE_REQUIRED');

CREATE INDEX IF NOT EXISTS idx_billing_requests_price_version
    ON public.ai_gateway_billing_requests (price_version_id);

CREATE TABLE IF NOT EXISTS public.ai_gateway_billing_ledger (
    id                  BIGSERIAL     PRIMARY KEY,
    account_id          BIGINT        NOT NULL
        REFERENCES public.ai_gateway_billing_accounts (id) ON DELETE RESTRICT,
    request_id          UUID
        REFERENCES public.ai_gateway_billing_requests (request_id) ON DELETE RESTRICT,
    entry_type          VARCHAR(24)   NOT NULL,
    idempotency_key     VARCHAR(320)  NOT NULL UNIQUE,
    balance_delta       NUMERIC(24,8) NOT NULL DEFAULT 0,
    reserved_delta      NUMERIC(24,8) NOT NULL DEFAULT 0,
    balance_after       NUMERIC(24,8) NOT NULL,
    reserved_after      NUMERIC(24,8) NOT NULL,
    currency            VARCHAR(8)    NOT NULL,
    reason              TEXT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by          UUID,
    CONSTRAINT ck_billing_ledger_entry_type
        CHECK (entry_type IN ('RESERVE', 'SETTLE', 'RELEASE', 'TOP_UP', 'ADJUSTMENT', 'REFUND')),
    CONSTRAINT ck_billing_ledger_reserved_after_nonnegative
        CHECK (reserved_after >= 0)
);

CREATE INDEX IF NOT EXISTS idx_billing_ledger_account_created
    ON public.ai_gateway_billing_ledger (account_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_billing_ledger_request
    ON public.ai_gateway_billing_ledger (request_id)
    WHERE request_id IS NOT NULL;

COMMENT ON TABLE public.ai_gateway_billing_accounts IS
    'Financially authoritative project balances and active reservations.';
COMMENT ON TABLE public.ai_gateway_model_price_versions IS
    'Immutable platform selling-price versions; tenant pricing is not a billing source.';
COMMENT ON TABLE public.ai_gateway_billing_requests IS
    'One state machine row per billable gateway request, with request-scoped price snapshots.';
COMMENT ON TABLE public.ai_gateway_billing_ledger IS
    'Append-only balance and reservation deltas; idempotency_key prevents duplicate financial entries.';
