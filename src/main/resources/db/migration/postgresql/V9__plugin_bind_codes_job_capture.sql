-- Round 7 (P7): plugin bind codes (PostgreSQL source of truth) and job capture upload.
--
-- Key design decisions:
--   One-time bind codes move from Redis to PostgreSQL: only the SHA-256 hex of
--     the code is stored, codes are single-use, expire after a bounded TTL and
--     are capped per user (the oldest ACTIVE code is auto-superseded). Redis
--     remains a rate limiter and a short-lived idempotent response cache only.
--   Code consumption and device/token issuance run inside ONE database
--     transaction via narrow SECURITY DEFINER functions, so a bind can never
--     leave a consumed code without its device/token or vice versa.
--   Captured jobs are written into the existing app.job_posts pool (V4), one
--     row per (user_id, platform, external_job_id). The capture upsert only
--     refreshes last_seen_at on duplicates and never touches status, matches,
--     delivery tasks or any user-edited data. No separate capture table and
--     no raw payload storage: the client can never inject unknown keys or
--     sensitive values, and only whitelisted normalized fields land in
--     job_posts (company_info/welfare are built server-side).
--   Existing plugin_devices / plugin_tokens columns are NOT recreated; only the
--     missing device_type column and the jobs:write scope are added. Historical
--     tokens without jobs:write keep working for tasks but get 403 on capture
--     until the user re-binds the device.

-- 1. One-time bind codes (PostgreSQL is the single source of truth).
CREATE TABLE app.plugin_bind_codes (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE RESTRICT,
    bind_code_hash char(64) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, user_id),
    CONSTRAINT plugin_bind_codes_id_user_unique UNIQUE (id, user_id),
    CONSTRAINT plugin_bind_codes_hash_check CHECK (bind_code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT plugin_bind_codes_status_check CHECK (
        status IN ('ACTIVE', 'CONSUMED', 'EXPIRED', 'SUPERSEDED')
    ),
    CONSTRAINT plugin_bind_codes_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT plugin_bind_codes_consumed_state_check CHECK (
        (status = 'CONSUMED' AND consumed_at IS NOT NULL)
        OR (status <> 'CONSUMED' AND consumed_at IS NULL)
    ),
    CONSTRAINT plugin_bind_codes_version_check CHECK (version > 0)
);

CREATE INDEX plugin_bind_codes_hash_active_idx
    ON app.plugin_bind_codes (bind_code_hash)
    WHERE status = 'ACTIVE';
CREATE INDEX plugin_bind_codes_user_active_created_idx
    ON app.plugin_bind_codes (user_id, created_at)
    WHERE status = 'ACTIVE';

-- 2. Row-level security: the app role only ever sees its own rows.
--    Captured jobs live in app.job_posts whose RLS policy already limits the
--    app role to its own rows (V4), so no new policy is needed here.
ALTER TABLE app.plugin_bind_codes ENABLE ROW LEVEL SECURITY;

CREATE POLICY plugin_bind_codes_current_user_policy
    ON app.plugin_bind_codes FOR ALL TO ${app_role}
    USING (user_id = app.current_user_id())
    WITH CHECK (user_id = app.current_user_id());

-- 3. New plugin token scope jobs:write (job capture upload). Task execution
--    scopes stay untouched; historical tokens keep working for tasks and are
--    denied on capture endpoints until the device re-binds.
ALTER TABLE app.plugin_tokens DROP CONSTRAINT plugin_tokens_scopes_check;
ALTER TABLE app.plugin_tokens ADD CONSTRAINT plugin_tokens_scopes_check CHECK (
    jsonb_typeof(scopes) = 'array'
    AND scopes <@ '["device:read","tasks:read","tasks:write","jobs:write"]'::jsonb
);

-- 4. Missing device_type column (nullable; existing rows keep NULL).
ALTER TABLE app.plugin_devices ADD COLUMN device_type varchar(40);

-- 5. Narrow security-definer functions for bind-code creation and consumption.

-- 5a. Create a bind code under a per-user lock: the account must be ACTIVE,
--     expired codes are marked EXPIRED first, and beyond the cap the OLDEST
--     active code is auto-superseded in the same atomic step.
CREATE OR REPLACE FUNCTION app.create_plugin_bind_code(
    p_user_id uuid,
    p_code_hash char(64),
    p_expires_at timestamptz,
    p_max_active integer
)
RETURNS TABLE (outcome text, bind_code_id uuid)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_user_status varchar(24);
    v_active_count integer;
    v_code_id uuid;
    v_max_active integer := GREATEST(1, LEAST(p_max_active, 10));
BEGIN
    SELECT status INTO v_user_status
    FROM app.users
    WHERE id = p_user_id
    FOR UPDATE;

    IF v_user_status IS DISTINCT FROM 'ACTIVE' THEN
        RETURN QUERY SELECT 'ACCOUNT_DISABLED', NULL::uuid;
        RETURN;
    END IF;

    UPDATE app.plugin_bind_codes
    SET status = 'EXPIRED', version = version + 1
    WHERE user_id = p_user_id AND status = 'ACTIVE' AND expires_at <= now();

    SELECT count(*) INTO v_active_count
    FROM app.plugin_bind_codes
    WHERE user_id = p_user_id AND status = 'ACTIVE';

    IF v_active_count >= v_max_active THEN
        UPDATE app.plugin_bind_codes
        SET status = 'SUPERSEDED', version = version + 1
        WHERE id IN (
            SELECT id
            FROM app.plugin_bind_codes
            WHERE user_id = p_user_id AND status = 'ACTIVE'
            ORDER BY created_at ASC, id ASC
            LIMIT (v_active_count - v_max_active + 1)
        );
    END IF;

    INSERT INTO app.plugin_bind_codes (user_id, bind_code_hash, status, expires_at)
    VALUES (p_user_id, p_code_hash, 'ACTIVE', p_expires_at)
    RETURNING id INTO v_code_id;

    RETURN QUERY SELECT 'OK', v_code_id;
END;
$function$;

-- 5b. One-shot consumption: expired, consumed, superseded or unknown codes all
--     return NULL so callers cannot probe which code state caused the miss.
CREATE OR REPLACE FUNCTION app.consume_plugin_bind_code(
    p_code_hash char(64)
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_user_id uuid;
BEGIN
    SELECT user_id INTO v_user_id
    FROM app.plugin_bind_codes
    WHERE bind_code_hash = p_code_hash
      AND status = 'ACTIVE'
      AND expires_at > now()
    ORDER BY created_at ASC
    LIMIT 1
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    UPDATE app.plugin_bind_codes
    SET status = 'CONSUMED', consumed_at = now(), version = version + 1
    WHERE user_id = v_user_id
      AND bind_code_hash = p_code_hash
      AND status = 'ACTIVE'
      AND expires_at > now();

    RETURN v_user_id;
END;
$function$;

REVOKE ALL ON FUNCTION app.create_plugin_bind_code(uuid, char, timestamptz, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.consume_plugin_bind_code(char) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION app.create_plugin_bind_code(uuid, char, timestamptz, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.consume_plugin_bind_code(char) TO ${app_role};

-- 6. Touch trigger for the new bind-code table. job_posts already has its
--    touch trigger from V4 and needs no new one.
CREATE TRIGGER plugin_bind_codes_touch_updated_at
    BEFORE UPDATE ON app.plugin_bind_codes
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- 7. New audit event type for job capture uploads.
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
    'PLUGIN_TASK_PAUSED',
    'PLUGIN_TASKS_PULLED',
    'PLUGIN_JOB_CAPTURED'
));

COMMENT ON TABLE app.plugin_bind_codes IS 'One-time plugin bind codes; only the SHA-256 hex of the code is stored. Codes are single-use, expire after a bounded TTL and are capped per user.';
COMMENT ON COLUMN app.plugin_devices.device_type IS 'Optional free-form device type label supplied at bind time (never a hardware fingerprint).';
COMMENT ON FUNCTION app.create_plugin_bind_code(uuid, char, timestamptz, integer) IS 'Atomically create an ACTIVE bind code under a per-user lock; marks expired codes and auto-supersedes the oldest code beyond the cap.';
COMMENT ON FUNCTION app.consume_plugin_bind_code(char) IS 'One-shot bind code consumption; returns the owner user id or NULL for expired/consumed/superseded/unknown codes.';
