-- Round 5 (2A): delivery tasks, plugin devices/tokens and the plugin execution backend.
--
-- Key design decisions:
--   Plugin devices and tokens are user-scoped rows protected by RLS like every
--     other business table; token plaintext is never stored, only SHA-256 hex.
--   AI APPLY auto-creates a PENDING_CONFIRMATION delivery task via an AFTER UPDATE
--     trigger on job_matches; migration backfills pre-existing SUCCEEDED+APPLY rows.
--   Task/event writes from the API run under the RLS tenant context; plugin state
--     transitions use narrow SECURITY DEFINER functions so a plugin Token can never
--     write state directly.
--   delivery_task_events is append-only: the app role gets INSERT/SELECT only.
--   All SECURITY DEFINER functions fix search_path, disable row_security, revoke
--     PUBLIC and are granted only to the app role.

-- 1. Plugin devices
CREATE TABLE app.plugin_devices (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE RESTRICT,
    device_name varchar(100) NOT NULL,
    installation_id_hash char(64) NOT NULL,
    browser_name varchar(40),
    browser_version varchar(40),
    extension_version varchar(40) NOT NULL,
    capabilities jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    last_seen_at timestamptz,
    bound_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    revoke_reason varchar(255),
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, user_id),
    CONSTRAINT plugin_devices_id_user_unique UNIQUE (id, user_id),
    CONSTRAINT plugin_devices_name_not_blank CHECK (length(trim(device_name)) BETWEEN 1 AND 100),
    CONSTRAINT plugin_devices_installation_hash_check CHECK (installation_id_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT plugin_devices_status_check CHECK (status IN ('ACTIVE', 'REVOKED', 'DISABLED')),
    CONSTRAINT plugin_devices_capabilities_check CHECK (
        jsonb_typeof(capabilities) = 'array'
        AND capabilities <@ '["BOSS","ZHILIAN"]'::jsonb
    ),
    CONSTRAINT plugin_devices_version_check CHECK (version > 0),
    CONSTRAINT plugin_devices_extension_not_blank CHECK (length(trim(extension_version)) > 0),
    CONSTRAINT plugin_devices_revoked_state_check CHECK (
        (status = 'REVOKED' AND revoked_at IS NOT NULL)
        OR (status <> 'REVOKED' AND revoked_at IS NULL)
    )
);

-- One active device per user per installation id; a re-bind reuses the row.
CREATE UNIQUE INDEX plugin_devices_user_installation_active_unique
    ON app.plugin_devices (user_id, installation_id_hash)
    WHERE status = 'ACTIVE';
CREATE INDEX plugin_devices_user_status_seen_idx
    ON app.plugin_devices (user_id, status, last_seen_at DESC);

-- 2. Plugin tokens (opaque Bearer tokens; only SHA-256 hex is stored)
CREATE TABLE app.plugin_tokens (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE RESTRICT,
    plugin_device_id uuid NOT NULL,
    token_prefix varchar(16) NOT NULL,
    token_hash char(64) NOT NULL,
    scopes jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    expires_at timestamptz NOT NULL,
    last_used_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    version integer NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, user_id),
    CONSTRAINT plugin_tokens_id_user_unique UNIQUE (id, user_id),
    CONSTRAINT plugin_tokens_hash_unique UNIQUE (token_hash),
    CONSTRAINT plugin_tokens_device_fk FOREIGN KEY (plugin_device_id, user_id)
        REFERENCES app.plugin_devices (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT plugin_tokens_hash_check CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT plugin_tokens_prefix_not_blank CHECK (length(trim(token_prefix)) > 0),
    CONSTRAINT plugin_tokens_status_check CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT plugin_tokens_scopes_check CHECK (
        jsonb_typeof(scopes) = 'array'
        AND scopes <@ '["device:read","tasks:read","tasks:write"]'::jsonb
    ),
    CONSTRAINT plugin_tokens_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT plugin_tokens_version_check CHECK (version > 0)
);

CREATE INDEX plugin_tokens_user_device_status_idx
    ON app.plugin_tokens (user_id, plugin_device_id, status);
CREATE INDEX plugin_tokens_active_expiry_idx
    ON app.plugin_tokens (expires_at)
    WHERE status = 'ACTIVE';
CREATE INDEX plugin_tokens_prefix_idx
    ON app.plugin_tokens (token_prefix);

-- 3. Delivery tasks (delivery list and plugin execution state)
CREATE TABLE app.delivery_tasks (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE RESTRICT,
    job_post_id uuid NOT NULL,
    job_match_id uuid,
    assigned_device_id uuid,
    status varchar(32) NOT NULL DEFAULT 'PENDING_CONFIRMATION',
    greeting varchar(60),
    confirmation_version integer NOT NULL DEFAULT 0,
    confirmed_at timestamptz,
    confirmed_by uuid,
    idempotency_key_hash char(64) NOT NULL,
    idempotency_payload_hash char(64) NOT NULL,
    lease_id uuid,
    leased_at timestamptz,
    lease_expires_at timestamptz,
    execution_id varchar(80),
    attempt_count integer NOT NULL DEFAULT 0,
    last_error_code varchar(64),
    last_error_message varchar(500),
    last_error_retryable boolean,
    started_at timestamptz,
    finished_at timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, user_id),
    CONSTRAINT delivery_tasks_id_user_unique UNIQUE (id, user_id),
    CONSTRAINT delivery_tasks_job_fk FOREIGN KEY (job_post_id, user_id)
        REFERENCES app.job_posts (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT delivery_tasks_match_fk FOREIGN KEY (job_match_id, user_id)
        REFERENCES app.job_matches (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT delivery_tasks_device_fk FOREIGN KEY (assigned_device_id, user_id)
        REFERENCES app.plugin_devices (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT delivery_tasks_status_check CHECK (status IN (
        'PENDING_CONFIRMATION', 'CONFIRMED', 'LEASED', 'EXECUTING', 'SUCCEEDED',
        'FAILED', 'PAUSED', 'SKIPPED', 'CANCELLED'
    )),
    CONSTRAINT delivery_tasks_greeting_check CHECK (
        greeting IS NULL OR char_length(greeting) <= 60
    ),
    CONSTRAINT delivery_tasks_key_hash_check CHECK (idempotency_key_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT delivery_tasks_payload_hash_check CHECK (idempotency_payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT delivery_tasks_confirmation_version_check CHECK (confirmation_version >= 0),
    CONSTRAINT delivery_tasks_attempt_check CHECK (attempt_count >= 0),
    CONSTRAINT delivery_tasks_version_check CHECK (version > 0),
    -- State machine field consistency: every status carries exactly the
    -- confirmation/device/lease/execution fields its lifecycle allows, so a
    -- direct INSERT/UPDATE cannot forge inconsistent rows. SUCCEEDED/FAILED/
    -- PAUSED may keep the execution attribution (assigned_device_id and
    -- execution_id) for history and idempotent replay, but never a lease.
    CONSTRAINT delivery_tasks_status_confirmation_check CHECK (
        (status = 'PENDING_CONFIRMATION' AND confirmed_at IS NULL AND confirmed_by IS NULL)
        OR (status IN ('CONFIRMED', 'LEASED', 'EXECUTING', 'SUCCEEDED', 'FAILED', 'PAUSED')
            AND confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL)
        OR (status IN ('SKIPPED', 'CANCELLED')
            AND confirmed_at IS NULL AND confirmed_by IS NULL)
    ),
    CONSTRAINT delivery_tasks_execution_state_check CHECK (
        (status = 'PENDING_CONFIRMATION'
            AND assigned_device_id IS NULL
            AND lease_id IS NULL AND leased_at IS NULL AND lease_expires_at IS NULL
            AND execution_id IS NULL)
        OR (status = 'CONFIRMED'
            AND lease_id IS NULL AND leased_at IS NULL AND lease_expires_at IS NULL
            AND execution_id IS NULL)
        OR (status IN ('LEASED', 'EXECUTING')
            AND assigned_device_id IS NOT NULL
            AND lease_id IS NOT NULL AND leased_at IS NOT NULL AND lease_expires_at IS NOT NULL
            AND (status = 'LEASED' OR execution_id IS NOT NULL))
        OR (status IN ('SKIPPED', 'CANCELLED')
            AND assigned_device_id IS NULL
            AND lease_id IS NULL AND leased_at IS NULL AND lease_expires_at IS NULL
            AND execution_id IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED', 'PAUSED')
            AND lease_id IS NULL AND leased_at IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT delivery_tasks_finished_check CHECK (
        status NOT IN ('SUCCEEDED', 'FAILED', 'SKIPPED', 'CANCELLED') OR finished_at IS NOT NULL
    )
);

-- One idempotent create per user + key hash; one active task per user + job post.
CREATE UNIQUE INDEX delivery_tasks_user_key_hash_unique
    ON app.delivery_tasks (user_id, idempotency_key_hash);
CREATE UNIQUE INDEX delivery_tasks_user_job_active_unique
    ON app.delivery_tasks (user_id, job_post_id)
    WHERE status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'LEASED', 'EXECUTING', 'PAUSED');
CREATE INDEX delivery_tasks_user_status_created_idx
    ON app.delivery_tasks (user_id, status, created_at DESC);
CREATE INDEX delivery_tasks_user_device_status_idx
    ON app.delivery_tasks (user_id, assigned_device_id, status, confirmed_at);
CREATE INDEX delivery_tasks_lease_recovery_idx
    ON app.delivery_tasks (lease_expires_at)
    WHERE status IN ('LEASED', 'EXECUTING');

-- 4. Delivery task events (append-only domain timeline)
CREATE TABLE app.delivery_task_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE RESTRICT,
    delivery_task_id uuid NOT NULL,
    event_type varchar(48) NOT NULL,
    from_status varchar(32),
    to_status varchar(32),
    actor_type varchar(24) NOT NULL,
    actor_id uuid,
    request_id varchar(64),
    event_key varchar(120) NOT NULL,
    idempotency_key_hash char(64),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT delivery_task_events_task_fk FOREIGN KEY (delivery_task_id, user_id)
        REFERENCES app.delivery_tasks (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT delivery_task_events_task_key_unique UNIQUE (delivery_task_id, event_key),
    CONSTRAINT delivery_task_events_type_check CHECK (event_type IN (
        'CREATED', 'CONFIRMED', 'GREETING_UPDATED', 'CONFIRMATION_INVALIDATED',
        'SKIPPED', 'LEASED', 'STARTED', 'SUCCEEDED', 'FAILED', 'PAUSED',
        'LEASE_EXPIRED', 'DEVICE_REVOKED'
    )),
    CONSTRAINT delivery_task_events_actor_check CHECK (actor_type IN ('USER', 'PLUGIN', 'SYSTEM', 'ADMIN')),
    CONSTRAINT delivery_task_events_status_check CHECK (
        (from_status IS NULL OR from_status IN (
            'PENDING_CONFIRMATION', 'CONFIRMED', 'LEASED', 'EXECUTING', 'SUCCEEDED',
            'FAILED', 'PAUSED', 'SKIPPED', 'CANCELLED'
        ))
        AND (to_status IS NULL OR to_status IN (
            'PENDING_CONFIRMATION', 'CONFIRMED', 'LEASED', 'EXECUTING', 'SUCCEEDED',
            'FAILED', 'PAUSED', 'SKIPPED', 'CANCELLED'
        ))
    ),
    CONSTRAINT delivery_task_events_details_check CHECK (jsonb_typeof(details) = 'object'),
    CONSTRAINT delivery_task_events_key_not_blank CHECK (length(trim(event_key)) > 0),
    CONSTRAINT delivery_task_events_idem_hash_check CHECK (
        idempotency_key_hash IS NULL OR idempotency_key_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX delivery_task_events_user_task_idx
    ON app.delivery_task_events (user_id, delivery_task_id, id);
CREATE INDEX delivery_task_events_user_created_idx
    ON app.delivery_task_events (user_id, created_at DESC);
-- One plugin event per idempotency key per task: a reused key with a different
-- payload cannot mint a second event.
CREATE UNIQUE INDEX delivery_task_events_task_idem_key_unique
    ON app.delivery_task_events (delivery_task_id, idempotency_key_hash)
    WHERE idempotency_key_hash IS NOT NULL;

-- 5. Row-level security: app role sees only its own rows; events are append-only.
ALTER TABLE app.plugin_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.plugin_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.delivery_tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.delivery_task_events ENABLE ROW LEVEL SECURITY;

CREATE POLICY plugin_devices_current_user_policy
    ON app.plugin_devices FOR ALL TO ${app_role}
    USING (user_id = app.current_user_id())
    WITH CHECK (user_id = app.current_user_id());

CREATE POLICY plugin_tokens_current_user_policy
    ON app.plugin_tokens FOR ALL TO ${app_role}
    USING (user_id = app.current_user_id())
    WITH CHECK (user_id = app.current_user_id());

CREATE POLICY delivery_tasks_current_user_policy
    ON app.delivery_tasks FOR ALL TO ${app_role}
    USING (user_id = app.current_user_id())
    WITH CHECK (user_id = app.current_user_id());

-- Events are append-only domain records: no UPDATE/DELETE for the app role.
REVOKE UPDATE, DELETE ON app.delivery_task_events FROM ${app_role};

CREATE POLICY delivery_task_events_insert_policy
    ON app.delivery_task_events FOR INSERT TO ${app_role}
    WITH CHECK (user_id = app.current_user_id());

CREATE POLICY delivery_task_events_select_policy
    ON app.delivery_task_events FOR SELECT TO ${app_role}
    USING (user_id = app.current_user_id());

-- 6. Auto-create delivery task when a match first reaches SUCCEEDED + APPLY.
--    Repeated UPDATEs / repeated worker completions cannot create duplicates:
--    the per-user key-hash and per-user active-job unique indexes absorb them.
CREATE OR REPLACE FUNCTION app.auto_create_delivery_task_on_match_apply()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_task_id uuid;
BEGIN
    IF NEW.status = 'SUCCEEDED'
       AND NEW.decision = 'APPLY'
       AND (OLD.status IS DISTINCT FROM 'SUCCEEDED'
            OR OLD.decision IS DISTINCT FROM 'APPLY') THEN
        INSERT INTO app.delivery_tasks (
            id, user_id, job_post_id, job_match_id, status, greeting,
            idempotency_key_hash, idempotency_payload_hash
        )
        SELECT gen_random_uuid(), NEW.user_id, NEW.job_post_id, NEW.id,
               'PENDING_CONFIRMATION',
               CASE WHEN jp.platform = 'BOSS' THEN NEW.greeting ELSE NULL END,
               encode(digest('auto-apply:' || NEW.id::text, 'sha256'), 'hex'),
               encode(digest('auto-apply:' || NEW.id::text, 'sha256'), 'hex')
        FROM app.job_posts jp
        WHERE jp.id = NEW.job_post_id
          AND jp.user_id = NEW.user_id
          AND jp.platform IN ('BOSS', 'ZHILIAN')
        ON CONFLICT DO NOTHING
        RETURNING id INTO v_task_id;

        IF FOUND THEN
            INSERT INTO app.delivery_task_events (
                user_id, delivery_task_id, event_type, from_status, to_status,
                actor_type, actor_id, event_key, details
            ) VALUES (
                NEW.user_id, v_task_id, 'CREATED', NULL, 'PENDING_CONFIRMATION',
                'SYSTEM', NULL,
                'auto-apply:' || NEW.id::text,
                jsonb_build_object('source', 'MATCH_APPLY', 'matchId', NEW.id)
            );
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER job_matches_auto_create_delivery_task
    AFTER UPDATE OF status, decision ON app.job_matches
    FOR EACH ROW EXECUTE FUNCTION app.auto_create_delivery_task_on_match_apply();

-- 7. Migration backfill: pre-existing SUCCEEDED + APPLY matches get one
--    PENDING_CONFIRMATION task each for BOSS/ZHILIAN. The latest match per
--    user + job is chosen explicitly with DISTINCT ON so the outcome never
--    depends on an insertion-order side effect of ON CONFLICT DO NOTHING.
INSERT INTO app.delivery_tasks (
    id, user_id, job_post_id, job_match_id, status, greeting,
    idempotency_key_hash, idempotency_payload_hash
)
SELECT DISTINCT ON (m.user_id, m.job_post_id)
       gen_random_uuid(), m.user_id, m.job_post_id, m.id,
       'PENDING_CONFIRMATION',
       CASE WHEN jp.platform = 'BOSS' THEN m.greeting ELSE NULL END,
       encode(digest('backfill:' || m.id::text, 'sha256'), 'hex'),
       encode(digest('backfill:' || m.id::text, 'sha256'), 'hex')
FROM app.job_matches m
JOIN app.job_posts jp ON jp.id = m.job_post_id AND jp.user_id = m.user_id
WHERE m.status = 'SUCCEEDED'
  AND m.decision = 'APPLY'
  AND jp.platform IN ('BOSS', 'ZHILIAN')
ORDER BY m.user_id, m.job_post_id, m.created_at DESC, m.id DESC
ON CONFLICT DO NOTHING;

INSERT INTO app.delivery_task_events (
    user_id, delivery_task_id, event_type, from_status, to_status,
    actor_type, actor_id, event_key, details
)
SELECT t.user_id, t.id, 'CREATED', NULL, 'PENDING_CONFIRMATION',
       'SYSTEM', NULL,
       'backfill:' || COALESCE(t.job_match_id, t.id)::text,
       jsonb_build_object('source', 'MIGRATION_BACKFILL')
FROM app.delivery_tasks t;

-- 8. Narrow security-definer functions for token auth and plugin state transitions.

-- 8a. Authenticate a plugin token by prefix + hash. The prefix only narrows the
--     candidate set; the final comparison runs a fixed 64-character, non-early-
--     returning pass over the full hash so timing cannot reveal a match. The
--     function never returns the token hash; an unknown token yields an empty set.
CREATE OR REPLACE FUNCTION app.authenticate_plugin_token(
    p_token_prefix varchar,
    p_token_hash char(64)
)
RETURNS TABLE (
    token_id uuid,
    owner_user_id uuid,
    device_id uuid,
    token_status varchar,
    token_scopes jsonb,
    token_expires_at timestamptz,
    user_status varchar,
    user_display_name varchar,
    device_status varchar,
    device_capabilities jsonb,
    device_extension_version varchar,
    device_name varchar
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_token_id uuid;
BEGIN
    -- Lazily mark expired tokens before lookup.
    UPDATE app.plugin_tokens
    SET status = 'EXPIRED', version = version + 1
    WHERE token_hash = p_token_hash AND status = 'ACTIVE' AND expires_at <= now();

    SELECT t.id INTO v_token_id
    FROM app.plugin_tokens t
    WHERE t.token_prefix = p_token_prefix
      AND app.plugin_token_hash_matches(t.token_hash, p_token_hash)
    ORDER BY t.created_at DESC
    LIMIT 1;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    RETURN QUERY
    SELECT t.id, t.user_id, t.plugin_device_id, t.status, t.scopes, t.expires_at,
           u.status, COALESCE(p.display_name, ''),
           d.status, d.capabilities, d.extension_version, d.device_name
    FROM app.plugin_tokens t
    JOIN app.plugin_devices d ON d.id = t.plugin_device_id AND d.user_id = t.user_id
    JOIN app.users u ON u.id = t.user_id
    LEFT JOIN app.user_profiles p ON p.user_id = t.user_id
    WHERE t.id = v_token_id;
END;
$function$;

-- Constant-time 64-character hash comparison: always walks the full length and
-- only accumulates the XOR difference, so it never returns early on a match.
-- Private helper of authenticate_plugin_token; PUBLIC cannot execute it.
CREATE OR REPLACE FUNCTION app.plugin_token_hash_matches(
    p_stored char(64),
    p_provided char(64)
)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
AS $function$
DECLARE
    v_difference integer := 0;
    v_index integer;
BEGIN
    FOR v_index IN 1..64 LOOP
        v_difference := v_difference
            | (ascii(substr(p_stored, v_index, 1)) # ascii(substr(p_provided, v_index, 1)));
    END LOOP;
    RETURN v_difference = 0;
END;
$function$;

-- 8b. Throttled last-seen / last-used maintenance after successful auth.
CREATE OR REPLACE FUNCTION app.touch_plugin_token(
    p_token_id uuid,
    p_device_id uuid,
    p_interval_seconds integer
)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
    UPDATE app.plugin_tokens
    SET last_used_at = now(), version = version + 1
    WHERE id = p_token_id
      AND (last_used_at IS NULL OR last_used_at < now() - (p_interval_seconds * interval '1 second'));
    UPDATE app.plugin_devices
    SET last_seen_at = now(), version = version + 1
    WHERE id = p_device_id
      AND (last_seen_at IS NULL OR last_seen_at < now() - (p_interval_seconds * interval '1 second'));
$function$;

-- 8c. Bind (create or reuse) a device and issue a token atomically.
--     Reuse revokes existing active tokens (rotation). Enforces a per-user device cap.
CREATE OR REPLACE FUNCTION app.bind_plugin_device(
    p_user_id uuid,
    p_installation_id_hash char(64),
    p_device_name varchar,
    p_browser_name varchar,
    p_browser_version varchar,
    p_extension_version varchar,
    p_capabilities jsonb,
    p_token_prefix varchar,
    p_token_hash char(64),
    p_token_scopes jsonb,
    p_token_expires_at timestamptz,
    p_max_devices integer
)
RETURNS TABLE (outcome text, bound_device_id uuid, bound_token_id uuid, device_reused boolean)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_user_status varchar(24);
    v_device_id uuid;
    v_token_id uuid;
    v_reused boolean := false;
    v_device_count integer;
    v_max_devices integer := GREATEST(1, LEAST(p_max_devices, 50));
BEGIN
    -- Serialize concurrent binds per user and re-check the account state so a
    -- bind code issued before a disable cannot mint devices or tokens.
    SELECT status INTO v_user_status
    FROM app.users
    WHERE id = p_user_id
    FOR UPDATE;

    IF v_user_status IS DISTINCT FROM 'ACTIVE' THEN
        RETURN QUERY SELECT 'ACCOUNT_DISABLED', NULL::uuid, NULL::uuid, false;
        RETURN;
    END IF;

    SELECT id INTO v_device_id
    FROM app.plugin_devices
    WHERE user_id = p_user_id
      AND installation_id_hash = p_installation_id_hash
      AND status = 'ACTIVE'
    FOR UPDATE;

    IF FOUND THEN
        v_reused := true;
        UPDATE app.plugin_tokens
        SET status = 'REVOKED', revoked_at = now(), version = version + 1
        WHERE plugin_device_id = v_device_id AND user_id = p_user_id AND status = 'ACTIVE';
        UPDATE app.plugin_devices
        SET device_name = p_device_name,
            browser_name = p_browser_name,
            browser_version = p_browser_version,
            extension_version = p_extension_version,
            capabilities = p_capabilities,
            last_seen_at = now(),
            version = version + 1
        WHERE id = v_device_id AND user_id = p_user_id;
    ELSE
        SELECT count(*) INTO v_device_count
        FROM app.plugin_devices
        WHERE user_id = p_user_id AND status = 'ACTIVE';
        IF v_device_count >= v_max_devices THEN
            RETURN QUERY SELECT 'DEVICE_LIMIT_EXCEEDED', NULL::uuid, NULL::uuid, false;
            RETURN;
        END IF;

        INSERT INTO app.plugin_devices (
            user_id, device_name, installation_id_hash, browser_name, browser_version,
            extension_version, capabilities, bound_at, last_seen_at
        ) VALUES (
            p_user_id, p_device_name, p_installation_id_hash, p_browser_name, p_browser_version,
            p_extension_version, p_capabilities, now(), now()
        )
        RETURNING id INTO v_device_id;
    END IF;

    INSERT INTO app.plugin_tokens (
        user_id, plugin_device_id, token_prefix, token_hash, scopes, status, expires_at
    ) VALUES (
        p_user_id, v_device_id, p_token_prefix, p_token_hash, p_token_scopes, 'ACTIVE', p_token_expires_at
    )
    RETURNING id INTO v_token_id;

    RETURN QUERY SELECT 'OK', v_device_id, v_token_id, v_reused;
END;
$function$;

-- 8d. Revoke a device: revoke its tokens, unassign not-yet-executed tasks and
--     release live leases back to CONFIRMED with a DEVICE_REVOKED event.
CREATE OR REPLACE FUNCTION app.revoke_plugin_device(
    p_user_id uuid,
    p_device_id uuid,
    p_revoke_reason varchar
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_task record;
BEGIN
    UPDATE app.plugin_devices
    SET status = 'REVOKED', revoked_at = now(), revoke_reason = p_revoke_reason,
        version = version + 1
    WHERE id = p_device_id AND user_id = p_user_id AND status = 'ACTIVE';
    IF NOT FOUND THEN
        RETURN false;
    END IF;

    UPDATE app.plugin_tokens
    SET status = 'REVOKED', revoked_at = now(), version = version + 1
    WHERE plugin_device_id = p_device_id AND user_id = p_user_id AND status = 'ACTIVE';

    UPDATE app.delivery_tasks
    SET assigned_device_id = NULL, version = version + 1
    WHERE user_id = p_user_id
      AND assigned_device_id = p_device_id
      AND status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'PAUSED', 'FAILED');

    FOR v_task IN
        SELECT id, status FROM app.delivery_tasks
        WHERE user_id = p_user_id
          AND assigned_device_id = p_device_id
          AND status IN ('LEASED', 'EXECUTING')
        FOR UPDATE
    LOOP
        UPDATE app.delivery_tasks
        SET status = 'CONFIRMED',
            lease_id = NULL, leased_at = NULL, lease_expires_at = NULL,
            execution_id = NULL, assigned_device_id = NULL,
            version = version + 1
        WHERE id = v_task.id AND user_id = p_user_id;

        INSERT INTO app.delivery_task_events (
            user_id, delivery_task_id, event_type, from_status, to_status,
            actor_type, actor_id, event_key, details
        ) VALUES (
            p_user_id, v_task.id, 'DEVICE_REVOKED', v_task.status, 'CONFIRMED',
            'SYSTEM', p_device_id,
            'device-revoked:' || v_task.id::text || ':' || gen_random_uuid()::text,
            jsonb_build_object('deviceId', p_device_id)
        );
    END LOOP;

    RETURN true;
END;
$function$;

-- 8e. Atomic plugin start: CONFIRMED -> EXECUTING with a short lease.
--     The task row is locked FOR UPDATE so concurrent starts serialize; the
--     first device wins and binds assigned_device_id atomically. An identical
--     payload replay returns the original lease, a different payload for the
--     same execution or idempotency key conflicts.
CREATE OR REPLACE FUNCTION app.plugin_task_start(
    p_user_id uuid,
    p_device_id uuid,
    p_task_id uuid,
    p_expected_version integer,
    p_execution_id varchar,
    p_idempotency_key_hash char(64),
    p_payload_hash varchar,
    p_lease_seconds integer,
    p_max_attempts integer
)
RETURNS TABLE (
    outcome text,
    new_lease_id uuid,
    new_lease_expires_at timestamptz,
    attempt_number integer,
    new_version integer,
    task_status text,
    job_platform varchar,
    job_url text,
    job_title varchar,
    job_company varchar,
    task_greeting varchar
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_lease uuid := gen_random_uuid();
    v_lease_seconds integer := GREATEST(30, LEAST(p_lease_seconds, 1800));
    v_max_attempts integer := GREATEST(1, LEAST(p_max_attempts, 10));
    v_task app.delivery_tasks%ROWTYPE;
    v_capable boolean;
    v_new_version integer;
    v_attempt_number integer;
    v_replay_hash varchar;
    v_replay_actor uuid;
BEGIN
    SELECT * INTO v_task FROM app.delivery_tasks
    WHERE user_id = p_user_id AND id = p_task_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN QUERY SELECT 'NOT_FOUND', NULL::uuid, NULL::timestamptz, NULL::integer,
            NULL::integer, NULL::text, NULL::varchar, NULL::text, NULL::varchar,
            NULL::varchar, NULL::varchar;
        RETURN;
    END IF;

    -- Idempotent replay: the stored payload hash is compared before any version
    -- check so a client retrying with its original version still succeeds.
    SELECT e.details ->> 'payloadHash', e.actor_id INTO v_replay_hash, v_replay_actor
    FROM app.delivery_task_events e
    WHERE e.delivery_task_id = p_task_id
      AND e.event_key = 'start:' || p_execution_id
    LIMIT 1;

    IF FOUND THEN
        IF v_replay_hash IS DISTINCT FROM p_payload_hash
           OR v_replay_actor IS DISTINCT FROM p_device_id THEN
            RETURN QUERY SELECT 'IDEMPOTENCY_CONFLICT', NULL::uuid, NULL::timestamptz, NULL::integer,
                NULL::integer, NULL::text, NULL::varchar, NULL::text, NULL::varchar,
                NULL::varchar, NULL::varchar;
            RETURN;
        END IF;
        IF v_task.status = 'EXECUTING'
           AND v_task.execution_id = p_execution_id
           AND v_task.lease_id IS NOT NULL
           AND v_task.lease_expires_at > now() THEN
            RETURN QUERY
            SELECT 'REPLAY', v_task.lease_id, v_task.lease_expires_at,
                   v_task.attempt_count, v_task.version, v_task.status::text,
                   jp.platform, jp.job_url, jp.title, jp.company_name, v_task.greeting
            FROM app.job_posts jp
            WHERE jp.id = v_task.job_post_id AND jp.user_id = p_user_id;
            RETURN;
        END IF;
        RETURN QUERY SELECT 'IDEMPOTENCY_CONFLICT', NULL::uuid, NULL::timestamptz, NULL::integer,
            NULL::integer, NULL::text, NULL::varchar, NULL::text, NULL::varchar,
            NULL::varchar, NULL::varchar;
        RETURN;
    END IF;

    -- A reused idempotency key under a different execution conflicts as well.
    IF EXISTS (
        SELECT 1 FROM app.delivery_task_events e
        WHERE e.delivery_task_id = p_task_id
          AND e.idempotency_key_hash = p_idempotency_key_hash
    ) THEN
        RETURN QUERY SELECT 'IDEMPOTENCY_CONFLICT', NULL::uuid, NULL::timestamptz, NULL::integer,
            NULL::integer, NULL::text, NULL::varchar, NULL::text, NULL::varchar,
            NULL::varchar, NULL::varchar;
        RETURN;
    END IF;

    IF v_task.version <> p_expected_version THEN
        RETURN QUERY SELECT 'VERSION_CONFLICT', NULL::uuid, NULL::timestamptz, NULL::integer,
            NULL::integer, NULL::text, NULL::varchar, NULL::text, NULL::varchar,
            NULL::varchar, NULL::varchar;
        RETURN;
    END IF;

    IF v_task.status <> 'CONFIRMED' THEN
        RETURN QUERY SELECT
            CASE WHEN v_task.status IN ('LEASED', 'EXECUTING')
                 THEN 'TASK_ALREADY_CLAIMED' ELSE 'INVALID_STATE' END,
            NULL::uuid, NULL::timestamptz, NULL::integer, NULL::integer, NULL::text,
            NULL::varchar, NULL::text, NULL::varchar, NULL::varchar, NULL::varchar;
        RETURN;
    END IF;

    IF v_task.attempt_count >= v_max_attempts THEN
        RETURN QUERY SELECT 'MAX_ATTEMPTS', NULL::uuid, NULL::timestamptz, NULL::integer,
            NULL::integer, NULL::text, NULL::varchar, NULL::text, NULL::varchar,
            NULL::varchar, NULL::varchar;
        RETURN;
    END IF;

    IF v_task.assigned_device_id IS NOT NULL AND v_task.assigned_device_id <> p_device_id THEN
        RETURN QUERY SELECT 'TASK_ALREADY_CLAIMED', NULL::uuid, NULL::timestamptz, NULL::integer,
            NULL::integer, NULL::text, NULL::varchar, NULL::text, NULL::varchar,
            NULL::varchar, NULL::varchar;
        RETURN;
    END IF;

    -- Device must belong to the user, be ACTIVE and support the job platform.
    SELECT (d.status = 'ACTIVE'
            AND jp.platform = ANY (SELECT jsonb_array_elements_text(d.capabilities)))
    INTO v_capable
    FROM app.plugin_devices d
    JOIN app.job_posts jp ON jp.id = v_task.job_post_id AND jp.user_id = p_user_id
    WHERE d.id = p_device_id AND d.user_id = p_user_id;

    IF NOT FOUND OR NOT v_capable THEN
        RETURN QUERY SELECT 'DEVICE_UNAVAILABLE', NULL::uuid, NULL::timestamptz, NULL::integer,
            NULL::integer, NULL::text, NULL::varchar, NULL::text, NULL::varchar,
            NULL::varchar, NULL::varchar;
        RETURN;
    END IF;

    UPDATE app.delivery_tasks
    SET status = 'EXECUTING',
        assigned_device_id = p_device_id,
        lease_id = v_lease,
        leased_at = now(),
        lease_expires_at = now() + make_interval(secs => v_lease_seconds),
        execution_id = p_execution_id,
        attempt_count = attempt_count + 1,
        started_at = COALESCE(started_at, now()),
        last_error_code = NULL,
        last_error_message = NULL,
        last_error_retryable = NULL,
        version = version + 1
    WHERE id = p_task_id
      AND user_id = p_user_id
      AND status = 'CONFIRMED'
      AND version = p_expected_version
      AND (assigned_device_id IS NULL OR assigned_device_id = p_device_id)
    RETURNING attempt_count, version INTO v_attempt_number, v_new_version;

    IF NOT FOUND THEN
        -- Lost the race: someone else claimed it.
        RETURN QUERY SELECT 'TASK_ALREADY_CLAIMED', NULL::uuid, NULL::timestamptz, NULL::integer,
            NULL::integer, NULL::text, NULL::varchar, NULL::text, NULL::varchar,
            NULL::varchar, NULL::varchar;
        RETURN;
    END IF;

    INSERT INTO app.delivery_task_events (
        user_id, delivery_task_id, event_type, from_status, to_status,
        actor_type, actor_id, event_key, idempotency_key_hash, details
    ) VALUES (
        p_user_id, p_task_id, 'STARTED', 'CONFIRMED', 'EXECUTING',
        'PLUGIN', p_device_id,
        'start:' || p_execution_id,
        p_idempotency_key_hash,
        jsonb_build_object('attemptNumber', v_attempt_number, 'leaseSeconds', v_lease_seconds,
                           'payloadHash', p_payload_hash)
    );

    RETURN QUERY
    SELECT 'OK', t.lease_id, t.lease_expires_at,
           v_attempt_number, v_new_version, 'EXECUTING',
           jp.platform, jp.job_url, jp.title, jp.company_name, t.greeting
    FROM app.delivery_tasks t
    JOIN app.job_posts jp ON jp.id = t.job_post_id AND jp.user_id = t.user_id
    WHERE t.id = p_task_id AND t.user_id = p_user_id;
END;
$function$;

-- 8f. Plugin success: EXECUTING -> SUCCEEDED (terminal, never overwritten).
--     The row lock serializes racing finishes; only the device that holds the
--     lease may report back; identical payloads replay, different payloads for
--     the same execution or idempotency key conflict.
CREATE OR REPLACE FUNCTION app.plugin_task_success(
    p_user_id uuid,
    p_device_id uuid,
    p_task_id uuid,
    p_lease_id uuid,
    p_execution_id varchar,
    p_expected_version integer,
    p_completed_at timestamptz,
    p_result_code varchar,
    p_evidence jsonb,
    p_idempotency_key_hash char(64),
    p_payload_hash varchar
)
RETURNS TABLE (outcome text, new_version integer, finished_at timestamptz)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_task app.delivery_tasks%ROWTYPE;
    v_new_version integer;
    v_finished_at timestamptz;
    v_replay_hash varchar;
    v_replay_actor uuid;
BEGIN
    SELECT * INTO v_task FROM app.delivery_tasks
    WHERE user_id = p_user_id AND id = p_task_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'NOT_FOUND', NULL::integer, NULL::timestamptz;
        RETURN;
    END IF;

    -- Idempotent replay: the stored payload hash is compared before the version
    -- check; SUCCEEDED is terminal and cannot be rewritten by any later call.
    SELECT e.details ->> 'payloadHash', e.actor_id INTO v_replay_hash, v_replay_actor
    FROM app.delivery_task_events e
    WHERE e.delivery_task_id = p_task_id
      AND e.event_key = 'success:' || p_execution_id
    LIMIT 1;

    IF FOUND THEN
        IF v_replay_hash IS DISTINCT FROM p_payload_hash
           OR v_replay_actor IS DISTINCT FROM p_device_id THEN
            RETURN QUERY SELECT 'IDEMPOTENCY_CONFLICT', NULL::integer, NULL::timestamptz;
            RETURN;
        END IF;
        RETURN QUERY SELECT 'REPLAY', v_task.version, v_task.finished_at;
        RETURN;
    END IF;

    IF EXISTS (
        SELECT 1 FROM app.delivery_task_events e
        WHERE e.delivery_task_id = p_task_id
          AND e.idempotency_key_hash = p_idempotency_key_hash
    ) THEN
        RETURN QUERY SELECT 'IDEMPOTENCY_CONFLICT', NULL::integer, NULL::timestamptz;
        RETURN;
    END IF;

    -- Only the device that holds the lease may report back.
    IF v_task.assigned_device_id IS DISTINCT FROM p_device_id THEN
        RETURN QUERY SELECT 'LEASE_INVALID', NULL::integer, NULL::timestamptz;
        RETURN;
    END IF;

    IF v_task.version <> p_expected_version THEN
        RETURN QUERY SELECT 'VERSION_CONFLICT', NULL::integer, NULL::timestamptz;
        RETURN;
    END IF;

    IF v_task.status = 'EXECUTING' THEN
        IF v_task.lease_id IS DISTINCT FROM p_lease_id THEN
            RETURN QUERY SELECT 'LEASE_INVALID', NULL::integer, NULL::timestamptz;
            RETURN;
        END IF;
        IF v_task.lease_expires_at <= now() THEN
            RETURN QUERY SELECT 'LEASE_EXPIRED', NULL::integer, NULL::timestamptz;
            RETURN;
        END IF;
        IF v_task.execution_id IS DISTINCT FROM p_execution_id THEN
            RETURN QUERY SELECT 'LEASE_INVALID', NULL::integer, NULL::timestamptz;
            RETURN;
        END IF;
    ELSE
        RETURN QUERY SELECT 'INVALID_STATE', NULL::integer, NULL::timestamptz;
        RETURN;
    END IF;

    v_finished_at := COALESCE(p_completed_at, now());
    UPDATE app.delivery_tasks
    SET status = 'SUCCEEDED',
        finished_at = v_finished_at,
        lease_id = NULL, leased_at = NULL, lease_expires_at = NULL,
        last_error_code = NULL, last_error_message = NULL, last_error_retryable = NULL,
        version = version + 1
    WHERE id = p_task_id AND user_id = p_user_id
    RETURNING version INTO v_new_version;

    INSERT INTO app.delivery_task_events (
        user_id, delivery_task_id, event_type, from_status, to_status,
        actor_type, actor_id, event_key, idempotency_key_hash, details
    ) VALUES (
        p_user_id, p_task_id, 'SUCCEEDED', 'EXECUTING', 'SUCCEEDED',
        'PLUGIN', p_device_id,
        'success:' || p_execution_id,
        p_idempotency_key_hash,
        jsonb_build_object('resultCode', p_result_code,
                           'evidence', COALESCE(p_evidence, '{}'::jsonb),
                           'payloadHash', p_payload_hash)
    );

    RETURN QUERY SELECT 'OK', v_new_version, v_finished_at;
END;
$function$;

-- 8g. Plugin fail: EXECUTING -> FAILED with bounded error details. The row
--     lock serializes racing finishes; identical payloads replay, different
--     payloads for the same execution or idempotency key conflict.
CREATE OR REPLACE FUNCTION app.plugin_task_fail(
    p_user_id uuid,
    p_device_id uuid,
    p_task_id uuid,
    p_lease_id uuid,
    p_execution_id varchar,
    p_expected_version integer,
    p_failed_at timestamptz,
    p_error_code varchar,
    p_error_message varchar,
    p_retryable boolean,
    p_idempotency_key_hash char(64),
    p_payload_hash varchar
)
RETURNS TABLE (
    outcome text,
    new_version integer,
    finished_at timestamptz,
    attempt_number integer
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_task app.delivery_tasks%ROWTYPE;
    v_new_version integer;
    v_finished_at timestamptz;
    v_replay_hash varchar;
    v_replay_actor uuid;
BEGIN
    SELECT * INTO v_task FROM app.delivery_tasks
    WHERE user_id = p_user_id AND id = p_task_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'NOT_FOUND', NULL::integer, NULL::timestamptz, NULL::integer;
        RETURN;
    END IF;

    -- Idempotent replay of the same failure event (same attempt + device);
    -- the stored payload hash is compared before the version check.
    SELECT e.details ->> 'payloadHash', e.actor_id INTO v_replay_hash, v_replay_actor
    FROM app.delivery_task_events e
    WHERE e.delivery_task_id = p_task_id
      AND e.event_key = 'fail:' || p_execution_id || ':a' || v_task.attempt_count
    LIMIT 1;

    IF FOUND THEN
        IF v_replay_hash IS DISTINCT FROM p_payload_hash
           OR v_replay_actor IS DISTINCT FROM p_device_id THEN
            RETURN QUERY SELECT 'IDEMPOTENCY_CONFLICT', NULL::integer, NULL::timestamptz, NULL::integer;
            RETURN;
        END IF;
        IF v_task.status = 'FAILED' AND v_task.execution_id = p_execution_id THEN
            RETURN QUERY SELECT 'REPLAY', v_task.version, v_task.finished_at, v_task.attempt_count;
            RETURN;
        END IF;
        RETURN QUERY SELECT 'IDEMPOTENCY_CONFLICT', NULL::integer, NULL::timestamptz, NULL::integer;
        RETURN;
    END IF;

    IF EXISTS (
        SELECT 1 FROM app.delivery_task_events e
        WHERE e.delivery_task_id = p_task_id
          AND e.idempotency_key_hash = p_idempotency_key_hash
    ) THEN
        RETURN QUERY SELECT 'IDEMPOTENCY_CONFLICT', NULL::integer, NULL::timestamptz, NULL::integer;
        RETURN;
    END IF;

    -- Only the device that holds the lease may report back.
    IF v_task.assigned_device_id IS DISTINCT FROM p_device_id THEN
        RETURN QUERY SELECT 'LEASE_INVALID', NULL::integer, NULL::timestamptz, NULL::integer;
        RETURN;
    END IF;

    IF v_task.version <> p_expected_version THEN
        RETURN QUERY SELECT 'VERSION_CONFLICT', NULL::integer, NULL::timestamptz, NULL::integer;
        RETURN;
    END IF;

    IF v_task.status = 'EXECUTING' THEN
        IF v_task.lease_id IS DISTINCT FROM p_lease_id
           OR v_task.execution_id IS DISTINCT FROM p_execution_id THEN
            RETURN QUERY SELECT 'LEASE_INVALID', NULL::integer, NULL::timestamptz, NULL::integer;
            RETURN;
        END IF;
        IF v_task.lease_expires_at <= now() THEN
            RETURN QUERY SELECT 'LEASE_EXPIRED', NULL::integer, NULL::timestamptz, NULL::integer;
            RETURN;
        END IF;
    ELSE
        RETURN QUERY SELECT 'INVALID_STATE', NULL::integer, NULL::timestamptz, NULL::integer;
        RETURN;
    END IF;

    v_finished_at := COALESCE(p_failed_at, now());
    UPDATE app.delivery_tasks
    SET status = 'FAILED',
        finished_at = v_finished_at,
        lease_id = NULL, leased_at = NULL, lease_expires_at = NULL,
        last_error_code = p_error_code,
        last_error_message = p_error_message,
        last_error_retryable = p_retryable,
        version = version + 1
    WHERE id = p_task_id AND user_id = p_user_id
    RETURNING version, attempt_count INTO v_new_version, v_task.attempt_count;

    INSERT INTO app.delivery_task_events (
        user_id, delivery_task_id, event_type, from_status, to_status,
        actor_type, actor_id, event_key, idempotency_key_hash, details
    ) VALUES (
        p_user_id, p_task_id, 'FAILED', 'EXECUTING', 'FAILED',
        'PLUGIN', p_device_id,
        'fail:' || p_execution_id || ':a' || v_task.attempt_count,
        p_idempotency_key_hash,
        jsonb_build_object('errorCode', p_error_code, 'retryable', p_retryable,
                           'attemptNumber', v_task.attempt_count,
                           'payloadHash', p_payload_hash)
    );

    RETURN QUERY SELECT 'OK', v_new_version, v_finished_at, v_task.attempt_count;
END;
$function$;

-- 8h. Plugin pause: EXECUTING -> PAUSED, lease released, user must re-confirm.
--     The row lock serializes racing finishes; identical payloads replay,
--     different payloads for the same execution or idempotency key conflict.
CREATE OR REPLACE FUNCTION app.plugin_task_pause(
    p_user_id uuid,
    p_device_id uuid,
    p_task_id uuid,
    p_lease_id uuid,
    p_execution_id varchar,
    p_expected_version integer,
    p_reason varchar,
    p_message varchar,
    p_idempotency_key_hash char(64),
    p_payload_hash varchar
)
RETURNS TABLE (outcome text, new_version integer)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_task app.delivery_tasks%ROWTYPE;
    v_new_version integer;
    v_replay_hash varchar;
    v_replay_actor uuid;
BEGIN
    SELECT * INTO v_task FROM app.delivery_tasks
    WHERE user_id = p_user_id AND id = p_task_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'NOT_FOUND', NULL::integer;
        RETURN;
    END IF;

    -- Idempotent replay of the same pause event; the stored payload hash is
    -- compared before the version check.
    SELECT e.details ->> 'payloadHash', e.actor_id INTO v_replay_hash, v_replay_actor
    FROM app.delivery_task_events e
    WHERE e.delivery_task_id = p_task_id
      AND e.event_key = 'pause:' || p_execution_id || ':a' || v_task.attempt_count
    LIMIT 1;

    IF FOUND THEN
        IF v_replay_hash IS DISTINCT FROM p_payload_hash
           OR v_replay_actor IS DISTINCT FROM p_device_id THEN
            RETURN QUERY SELECT 'IDEMPOTENCY_CONFLICT', NULL::integer;
            RETURN;
        END IF;
        IF v_task.status = 'PAUSED' AND v_task.execution_id = p_execution_id THEN
            RETURN QUERY SELECT 'REPLAY', v_task.version;
            RETURN;
        END IF;
        RETURN QUERY SELECT 'IDEMPOTENCY_CONFLICT', NULL::integer;
        RETURN;
    END IF;

    IF EXISTS (
        SELECT 1 FROM app.delivery_task_events e
        WHERE e.delivery_task_id = p_task_id
          AND e.idempotency_key_hash = p_idempotency_key_hash
    ) THEN
        RETURN QUERY SELECT 'IDEMPOTENCY_CONFLICT', NULL::integer;
        RETURN;
    END IF;

    -- Only the device that holds the lease may report back.
    IF v_task.assigned_device_id IS DISTINCT FROM p_device_id THEN
        RETURN QUERY SELECT 'LEASE_INVALID', NULL::integer;
        RETURN;
    END IF;

    IF v_task.version <> p_expected_version THEN
        RETURN QUERY SELECT 'VERSION_CONFLICT', NULL::integer;
        RETURN;
    END IF;

    IF v_task.status = 'EXECUTING' THEN
        IF v_task.lease_id IS DISTINCT FROM p_lease_id
           OR v_task.execution_id IS DISTINCT FROM p_execution_id THEN
            RETURN QUERY SELECT 'LEASE_INVALID', NULL::integer;
            RETURN;
        END IF;
        IF v_task.lease_expires_at <= now() THEN
            RETURN QUERY SELECT 'LEASE_EXPIRED', NULL::integer;
            RETURN;
        END IF;
    ELSE
        RETURN QUERY SELECT 'INVALID_STATE', NULL::integer;
        RETURN;
    END IF;

    UPDATE app.delivery_tasks
    SET status = 'PAUSED',
        lease_id = NULL, leased_at = NULL, lease_expires_at = NULL,
        last_error_code = p_reason,
        last_error_message = p_message,
        last_error_retryable = false,
        version = version + 1
    WHERE id = p_task_id AND user_id = p_user_id
    RETURNING version INTO v_new_version;

    INSERT INTO app.delivery_task_events (
        user_id, delivery_task_id, event_type, from_status, to_status,
        actor_type, actor_id, event_key, idempotency_key_hash, details
    ) VALUES (
        p_user_id, p_task_id, 'PAUSED', 'EXECUTING', 'PAUSED',
        'PLUGIN', p_device_id,
        'pause:' || p_execution_id || ':a' || v_task.attempt_count,
        p_idempotency_key_hash,
        jsonb_build_object('pauseReason', p_reason, 'attemptNumber', v_task.attempt_count,
                           'payloadHash', p_payload_hash)
    );

    RETURN QUERY SELECT 'OK', v_new_version;
END;
$function$;

-- 8i. Lease expiry sweep: expired EXECUTING/LEASED leases go back to CONFIRMED,
--     or to FAILED once attempts are exhausted. The assigned device is always
--     released so any capable device of the user can claim the task again.
--     Returns recovered rows.
CREATE OR REPLACE FUNCTION app.recover_expired_delivery_leases(p_max_attempts integer)
RETURNS TABLE (recovered_task_id uuid, recovered_user_id uuid, recovered_status text)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_task record;
    v_max_attempts integer := GREATEST(1, LEAST(p_max_attempts, 10));
BEGIN
    FOR v_task IN
        SELECT id, user_id, status, attempt_count
        FROM app.delivery_tasks
        WHERE status IN ('LEASED', 'EXECUTING')
          AND lease_expires_at < now()
        FOR UPDATE SKIP LOCKED
    LOOP
        IF v_task.attempt_count >= v_max_attempts THEN
            UPDATE app.delivery_tasks
            SET status = 'FAILED',
                finished_at = now(),
                assigned_device_id = NULL,
                lease_id = NULL, leased_at = NULL, lease_expires_at = NULL,
                execution_id = NULL,
                last_error_code = 'MAX_ATTEMPTS_EXCEEDED',
                last_error_message = '执行超时且已达到最大尝试次数',
                last_error_retryable = false,
                version = version + 1
            WHERE id = v_task.id AND user_id = v_task.user_id;
        ELSE
            UPDATE app.delivery_tasks
            SET status = 'CONFIRMED',
                assigned_device_id = NULL,
                lease_id = NULL, leased_at = NULL, lease_expires_at = NULL,
                execution_id = NULL,
                version = version + 1
            WHERE id = v_task.id AND user_id = v_task.user_id;
        END IF;

        INSERT INTO app.delivery_task_events (
            user_id, delivery_task_id, event_type, from_status, to_status,
            actor_type, actor_id, event_key, details
        ) VALUES (
            v_task.user_id, v_task.id, 'LEASE_EXPIRED', v_task.status,
            CASE WHEN v_task.attempt_count >= v_max_attempts THEN 'FAILED' ELSE 'CONFIRMED' END,
            'SYSTEM', NULL,
            'lease-expired:' || v_task.id::text || ':a' || v_task.attempt_count,
            jsonb_build_object('attemptNumber', v_task.attempt_count, 'deviceReleased', true)
        );

        RETURN QUERY SELECT v_task.id, v_task.user_id,
            CASE WHEN v_task.attempt_count >= v_max_attempts THEN 'FAILED' ELSE 'CONFIRMED' END;
    END LOOP;
    RETURN;
END;
$function$;

-- Revoke PUBLIC and grant only to the app role.
REVOKE ALL ON FUNCTION app.authenticate_plugin_token(varchar, char) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.plugin_token_hash_matches(char, char) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.touch_plugin_token(uuid, uuid, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.bind_plugin_device(uuid, char, varchar, varchar, varchar, varchar, jsonb, varchar, char, jsonb, timestamptz, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.revoke_plugin_device(uuid, uuid, varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.plugin_task_start(uuid, uuid, uuid, integer, varchar, char, varchar, integer, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.plugin_task_success(uuid, uuid, uuid, uuid, varchar, integer, timestamptz, varchar, jsonb, char, varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.plugin_task_fail(uuid, uuid, uuid, uuid, varchar, integer, timestamptz, varchar, varchar, boolean, char, varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.plugin_task_pause(uuid, uuid, uuid, uuid, varchar, integer, varchar, varchar, char, varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.recover_expired_delivery_leases(integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.auto_create_delivery_task_on_match_apply() FROM PUBLIC;

GRANT EXECUTE ON FUNCTION app.authenticate_plugin_token(varchar, char) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.touch_plugin_token(uuid, uuid, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.bind_plugin_device(uuid, char, varchar, varchar, varchar, varchar, jsonb, varchar, char, jsonb, timestamptz, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.revoke_plugin_device(uuid, uuid, varchar) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.plugin_task_start(uuid, uuid, uuid, integer, varchar, char, varchar, integer, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.plugin_task_success(uuid, uuid, uuid, uuid, varchar, integer, timestamptz, varchar, jsonb, char, varchar) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.plugin_task_fail(uuid, uuid, uuid, uuid, varchar, integer, timestamptz, varchar, varchar, boolean, char, varchar) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.plugin_task_pause(uuid, uuid, uuid, uuid, varchar, integer, varchar, varchar, char, varchar) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.recover_expired_delivery_leases(integer) TO ${app_role};

-- 9. Touch triggers for the new tables.
CREATE TRIGGER plugin_devices_touch_updated_at
    BEFORE UPDATE ON app.plugin_devices
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

CREATE TRIGGER plugin_tokens_touch_updated_at
    BEFORE UPDATE ON app.plugin_tokens
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

CREATE TRIGGER delivery_tasks_touch_updated_at
    BEFORE UPDATE ON app.delivery_tasks
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- 10. New audit event types for plugin binding and delivery.
ALTER TABLE app.audit_logs DROP CONSTRAINT audit_logs_action_check;
ALTER TABLE app.audit_logs ADD CONSTRAINT audit_logs_action_check CHECK (action IN (
    'AUTH_REGISTER',
    'AUTH_LOGIN',
    'AUTH_LOGOUT',
    'AUTH_LOGIN_FAILED',
    'AUTH_ACCOUNT_LOCKED',
    'AUTH_LOGIN_LOCKED',
    'AUTH_LOGIN_DISABLED',
    'AUTH_LOGIN_PENDING',
    'RESUME_UPLOAD',
    'RESUME_UPLOAD_REJECTED',
    'RESUME_PARSE_SUCCEEDED',
    'RESUME_PARSE_FAILED',
    'RESUME_DELETE_REQUESTED',
    'RESUME_PURGED',
    'PREFERENCE_UPDATED',
    'JOB_ANALYSIS_REQUESTED',
    'JOB_ANALYSIS_SUCCEEDED',
    'JOB_ANALYSIS_FAILED',
    'JOB_ANALYSIS_REUSED',
    'PLUGIN_BIND_CODE_CREATED',
    'PLUGIN_DEVICE_BOUND',
    'PLUGIN_DEVICE_REVOKED',
    'DELIVERY_TASK_CREATED',
    'DELIVERY_TASK_CONFIRMED',
    'DELIVERY_GREETING_UPDATED',
    'DELIVERY_TASK_SKIPPED',
    'PLUGIN_TASK_STARTED',
    'PLUGIN_TASK_SUCCEEDED',
    'PLUGIN_TASK_FAILED',
    'PLUGIN_TASK_PAUSED'
));

COMMENT ON TABLE app.plugin_devices IS 'User-bound browser extension devices; only a random installation ID hash is stored, never hardware fingerprints.';
COMMENT ON TABLE app.plugin_tokens IS 'Opaque plugin token hashes (SHA-256 hex) with scopes; plaintext tokens are returned exactly once at bind time.';
COMMENT ON TABLE app.delivery_tasks IS 'Delivery list and plugin execution state; plugin state transitions only via narrow security-definer functions.';
COMMENT ON TABLE app.delivery_task_events IS 'Append-only delivery task timeline; the app role cannot UPDATE or DELETE events.';
COMMENT ON FUNCTION app.auto_create_delivery_task_on_match_apply() IS 'Creates a PENDING_CONFIRMATION delivery task when a match first reaches SUCCEEDED+APPLY (BOSS/ZHILIAN only, BOSS copies the match greeting).';
COMMENT ON FUNCTION app.authenticate_plugin_token(varchar, char) IS 'Resolve a token prefix+hash to the minimal trusted token/device/user fields with a constant-time full-length hash comparison; never returns the hash.';
COMMENT ON FUNCTION app.plugin_token_hash_matches(char, char) IS 'Fixed-length, non-early-returning 64-char hash comparison used only inside authenticate_plugin_token; PUBLIC cannot execute it.';
COMMENT ON FUNCTION app.touch_plugin_token(uuid, uuid, integer) IS 'Throttled last_used/last_seen maintenance after successful plugin auth.';
COMMENT ON FUNCTION app.bind_plugin_device(uuid, char, varchar, varchar, varchar, varchar, jsonb, varchar, char, jsonb, timestamptz, integer) IS 'Atomically create or reuse a device and issue a token under a per-user lock; verifies the account is ACTIVE and enforces the device cap.';
COMMENT ON FUNCTION app.revoke_plugin_device(uuid, uuid, varchar) IS 'Revoke a device, its tokens and pending assignments; releases live leases back to CONFIRMED.';
COMMENT ON FUNCTION app.plugin_task_start(uuid, uuid, uuid, integer, varchar, char, varchar, integer, integer) IS 'Atomic CONFIRMED to EXECUTING claim that binds the winning device, with lease, capability, attempt and idempotency enforcement.';
COMMENT ON FUNCTION app.plugin_task_success(uuid, uuid, uuid, uuid, varchar, integer, timestamptz, varchar, jsonb, char, varchar) IS 'EXECUTING to SUCCEEDED; terminal, lease-holder only and replay-safe for identical payloads.';
COMMENT ON FUNCTION app.plugin_task_fail(uuid, uuid, uuid, uuid, varchar, integer, timestamptz, varchar, varchar, boolean, char, varchar) IS 'EXECUTING to FAILED with bounded error details; lease-holder only and replay-safe for identical payloads.';
COMMENT ON FUNCTION app.plugin_task_pause(uuid, uuid, uuid, uuid, varchar, integer, varchar, varchar, char, varchar) IS 'EXECUTING to PAUSED, releasing the lease; lease-holder only, replay-safe; the user must re-confirm.';
COMMENT ON FUNCTION app.recover_expired_delivery_leases(integer) IS 'Sweep expired EXECUTING/LEASED leases back to CONFIRMED, or FAILED after max attempts.';
