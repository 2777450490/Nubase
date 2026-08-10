-- Permanently fence pre-lease workers once a V15-aware worker has claimed a project.
-- V14 remains immutable because it may already have been applied to live databases.
ALTER TABLE public.database_configs
    ADD COLUMN IF NOT EXISTS init_fence_version BIGINT NOT NULL DEFAULT 0;

-- Pending projects must not be served by nodes that only check the legacy enabled flag.
UPDATE public.database_configs
SET enabled = FALSE
WHERE enabled = TRUE
  AND init_status IN ('PENDING_INIT', 'INITIALIZING', 'INIT_FAILED');

CREATE OR REPLACE FUNCTION public.enforce_database_config_initialization_fence()
RETURNS TRIGGER
LANGUAGE plpgsql
AS '
BEGIN
    -- Once a V15-aware claim has advanced the generation, old binaries cannot overwrite a
    -- provisioning status because they do not advance init_fence_version.
    IF TG_OP = ''UPDATE''
       AND NEW.init_status IS DISTINCT FROM OLD.init_status
       AND OLD.init_fence_version > 0
       AND NEW.init_fence_version <= OLD.init_fence_version THEN
        RAISE EXCEPTION
            ''database configuration initialization status requires a newer fence version''
            USING ERRCODE = ''23514'';
    END IF;

    -- Preserve safe visibility for legacy writers. A legacy terminal update can still finish a
    -- generation-zero project, but it can never publish a row after a V15 generation exists.
    IF NEW.init_status IN (''PENDING_INIT'', ''INITIALIZING'', ''INIT_FAILED'') THEN
        NEW.enabled := FALSE;
    ELSIF TG_OP = ''UPDATE''
          AND NEW.init_status IS DISTINCT FROM OLD.init_status
          AND NEW.init_status = ''INITIALIZED'' THEN
        NEW.enabled := TRUE;
    END IF;

    RETURN NEW;
END;
';

DROP TRIGGER IF EXISTS database_configs_initialization_fence_trigger
    ON public.database_configs;

CREATE TRIGGER database_configs_initialization_fence_trigger
BEFORE INSERT OR UPDATE OF init_status, enabled, init_fence_version
ON public.database_configs
FOR EACH ROW
EXECUTE FUNCTION public.enforce_database_config_initialization_fence();

ALTER TABLE public.database_configs
    ADD CONSTRAINT database_configs_provisioning_requires_disabled
    CHECK (
        init_status IS NULL
        OR init_status = 'INITIALIZED'
        OR enabled = FALSE
    );

COMMENT ON COLUMN public.database_configs.init_fence_version IS
    'Monotonic generation required for initialization status changes after lease fencing begins';
