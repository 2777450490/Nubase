-- Fence asynchronous project provisioning workers with a database-owned lease token.
-- Existing columns remain unchanged so rolling upgrades can apply this migration safely.
ALTER TABLE public.database_configs
    ADD COLUMN IF NOT EXISTS init_lease_token UUID,
    ADD COLUMN IF NOT EXISTS init_lease_expires_at TIMESTAMPTZ;

ALTER TABLE public.database_configs
    ADD CONSTRAINT database_configs_init_lease_consistency
    -- Prevent an older worker from writing a terminal status while a fenced lease is active.
    CHECK (
        (init_lease_token IS NULL AND init_lease_expires_at IS NULL)
        OR (
            init_lease_token IS NOT NULL
            AND init_lease_expires_at IS NOT NULL
            AND init_status = 'INITIALIZING'
        )
    );

COMMENT ON COLUMN public.database_configs.init_lease_token IS
    'Opaque token held by the worker that currently owns physical database initialization';
COMMENT ON COLUMN public.database_configs.init_lease_expires_at IS
    'Database-clock deadline after which another worker may reclaim initialization';
