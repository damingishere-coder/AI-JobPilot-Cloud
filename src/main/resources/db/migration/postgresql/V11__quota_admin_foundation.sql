-- Round 9 (P9 batch 1A): quota foundation — per-user/per-resource/per-period
-- quota rows, append-only usage logs and the reserved subscriptions table.
--
-- Key design decisions:
--   user_quotas keeps exactly one row per user/resource/UTC-natural-month; all
--     quota math (reserve/commit/release/consume) happens inside the caller's
--     business transaction under the RLS tenant, serialized by SELECT FOR UPDATE
--     on the current-period row. used + reserved <= limit is a database CHECK.
--   quota_usage_logs is append-only: the app role gets SELECT/INSERT only, every
--     real state change writes exactly one log row keyed by a stable
--     operation_key so idempotent replays never mint a second row.
--   subscriptions is reserved for later rounds: structure + RLS only, no
--     application service and no payment provider wiring.
--   Existing non-deleted users are backfilled with the current FREE month
--     (AI_ANALYSIS=20, DELIVERY_CONFIRM=10); newly registered users are
--     initialized by AuthService in the same registration transaction.

-- 1. Per-user quota snapshot rows.
CREATE TABLE app.user_quotas (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE RESTRICT,
    plan_code varchar(40) NOT NULL,
    resource_code varchar(40) NOT NULL,
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    limit_amount bigint NOT NULL,
    used_amount bigint NOT NULL DEFAULT 0,
    reserved_amount bigint NOT NULL DEFAULT 0,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, user_id),
    CONSTRAINT user_quotas_id_user_unique UNIQUE (id, user_id),
    CONSTRAINT user_quotas_user_resource_period_unique
        UNIQUE (user_id, resource_code, period_start, period_end),
    CONSTRAINT user_quotas_plan_check CHECK (plan_code IN (
        'FREE', 'MONTHLY', 'PREMIUM_MONTHLY', 'JOB_SEASON', 'COACHING'
    )),
    CONSTRAINT user_quotas_resource_check CHECK (
        resource_code IN ('AI_ANALYSIS', 'DELIVERY_CONFIRM')
    ),
    CONSTRAINT user_quotas_limit_check CHECK (limit_amount >= 0),
    CONSTRAINT user_quotas_used_check CHECK (used_amount >= 0),
    CONSTRAINT user_quotas_reserved_check CHECK (reserved_amount >= 0),
    CONSTRAINT user_quotas_used_reserved_check CHECK (
        used_amount + reserved_amount <= limit_amount
    ),
    CONSTRAINT user_quotas_period_check CHECK (period_end > period_start),
    CONSTRAINT user_quotas_version_check CHECK (version > 0)
);

CREATE INDEX user_quotas_user_period_idx
    ON app.user_quotas (user_id, period_end);

-- 2. Append-only quota usage log (design doc 3.12 + reason and the minimal
--    operation/reservation identifiers needed for reliable settlement).
CREATE TABLE app.quota_usage_logs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE RESTRICT,
    quota_id uuid NOT NULL,
    resource_code varchar(40) NOT NULL,
    action varchar(24) NOT NULL,
    amount bigint NOT NULL,
    reference_type varchar(40) NOT NULL,
    reference_id uuid,
    operation_key varchar(120) NOT NULL,
    reservation_id uuid,
    reason varchar(200) NOT NULL,
    balance_after bigint NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT quota_usage_logs_quota_fk FOREIGN KEY (quota_id, user_id)
        REFERENCES app.user_quotas (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT quota_usage_logs_operation_key_unique UNIQUE (user_id, operation_key),
    CONSTRAINT quota_usage_logs_action_check CHECK (
        action IN ('RESERVE', 'COMMIT', 'RELEASE', 'ADJUST')
    ),
    CONSTRAINT quota_usage_logs_resource_check CHECK (
        resource_code IN ('AI_ANALYSIS', 'DELIVERY_CONFIRM')
    ),
    CONSTRAINT quota_usage_logs_amount_check CHECK (amount > 0),
    CONSTRAINT quota_usage_logs_balance_check CHECK (balance_after >= 0),
    CONSTRAINT quota_usage_logs_reference_check CHECK (length(trim(reference_type)) > 0),
    CONSTRAINT quota_usage_logs_reason_check CHECK (length(trim(reason)) > 0),
    CONSTRAINT quota_usage_logs_key_check CHECK (length(trim(operation_key)) > 0),
    CONSTRAINT quota_usage_logs_metadata_check CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX quota_usage_logs_user_created_idx
    ON app.quota_usage_logs (user_id, created_at DESC);
CREATE INDEX quota_usage_logs_user_ref_idx
    ON app.quota_usage_logs (user_id, reference_type, reference_id);

-- 3. Reserved subscriptions table (design doc 3.13): structure + RLS only.
CREATE TABLE app.subscriptions (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE RESTRICT,
    plan_code varchar(40) NOT NULL,
    status varchar(24) NOT NULL,
    provider varchar(40),
    provider_subscription_id varchar(160),
    current_period_start timestamptz NOT NULL,
    current_period_end timestamptz NOT NULL,
    cancel_at_period_end boolean NOT NULL DEFAULT false,
    cancelled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, user_id),
    CONSTRAINT subscriptions_id_user_unique UNIQUE (id, user_id),
    CONSTRAINT subscriptions_plan_check CHECK (plan_code IN (
        'FREE', 'MONTHLY', 'PREMIUM_MONTHLY', 'JOB_SEASON', 'COACHING'
    )),
    CONSTRAINT subscriptions_status_check CHECK (
        status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT subscriptions_provider_id_check CHECK (
        (provider IS NULL AND provider_subscription_id IS NULL)
        OR (provider IS NOT NULL AND provider_subscription_id IS NOT NULL)
    ),
    CONSTRAINT subscriptions_period_check CHECK (
        current_period_end > current_period_start
    ),
    CONSTRAINT subscriptions_cancelled_state_check CHECK (
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
        OR (status <> 'CANCELLED' AND cancelled_at IS NULL)
    )
);

CREATE INDEX subscriptions_user_status_period_idx
    ON app.subscriptions (user_id, status, current_period_end);
-- One live subscription per user while it is not cancelled/expired.
CREATE UNIQUE INDEX subscriptions_user_active_unique
    ON app.subscriptions (user_id)
    WHERE status IN ('TRIALING', 'ACTIVE', 'PAST_DUE');
CREATE UNIQUE INDEX subscriptions_provider_id_unique
    ON app.subscriptions (provider, provider_subscription_id)
    WHERE provider_subscription_id IS NOT NULL;

-- 4. Matches carry an optional quota reservation key for later settlement;
--    historical terminal rows stay NULL.
ALTER TABLE app.job_matches
    ADD COLUMN quota_reservation_key varchar(120);

ALTER TABLE app.job_matches
    ADD CONSTRAINT job_matches_quota_reservation_key_check CHECK (
        quota_reservation_key IS NULL
        OR (length(trim(quota_reservation_key)) > 0 AND length(quota_reservation_key) <= 120)
    );

-- 5. Row-level security: app role sees only its own rows; the usage log is
--    append-only (SELECT/INSERT only, no UPDATE/DELETE ever).
ALTER TABLE app.user_quotas ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.quota_usage_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.subscriptions ENABLE ROW LEVEL SECURITY;

CREATE POLICY user_quotas_current_user_policy
    ON app.user_quotas FOR ALL TO ${app_role}
    USING (user_id = app.current_user_id())
    WITH CHECK (user_id = app.current_user_id());

CREATE POLICY subscriptions_current_user_policy
    ON app.subscriptions FOR ALL TO ${app_role}
    USING (user_id = app.current_user_id())
    WITH CHECK (user_id = app.current_user_id());

REVOKE UPDATE, DELETE ON app.quota_usage_logs FROM ${app_role};

CREATE POLICY quota_usage_logs_insert_policy
    ON app.quota_usage_logs FOR INSERT TO ${app_role}
    WITH CHECK (user_id = app.current_user_id());

CREATE POLICY quota_usage_logs_select_policy
    ON app.quota_usage_logs FOR SELECT TO ${app_role}
    USING (user_id = app.current_user_id());

-- 6. Backfill every existing non-deleted user with the current UTC natural
--    month FREE quotas (AI_ANALYSIS=20, DELIVERY_CONFIRM=10). New users are
--    initialized by the application in the registration transaction; the
--    ON CONFLICT keeps the backfill idempotent.
INSERT INTO app.user_quotas (
    user_id, plan_code, resource_code, period_start, period_end, limit_amount
)
SELECT u.id, 'FREE', backfill.resource_code,
       date_trunc('month', now())::timestamptz,
       (date_trunc('month', now()) + interval '1 month')::timestamptz,
       backfill.limit_amount
FROM app.users u
CROSS JOIN (
    VALUES ('AI_ANALYSIS', 20), ('DELIVERY_CONFIRM', 10)
) AS backfill(resource_code, limit_amount)
WHERE u.deleted_at IS NULL
ON CONFLICT (user_id, resource_code, period_start, period_end) DO NOTHING;

-- 7. Touch triggers keep updated_at honest for quota and subscription rows.
CREATE TRIGGER user_quotas_touch_updated_at
    BEFORE UPDATE ON app.user_quotas
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

CREATE TRIGGER subscriptions_touch_updated_at
    BEFORE UPDATE ON app.subscriptions
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- 8. Audit action whitelist: reserve the admin quota-adjust action for the
--    later admin batch. Java AuditWriter.ALLOWED_ACTIONS must stay in sync.
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
    'PLUGIN_TASKS_BATCH_PAUSED',
    'PLUGIN_JOB_CAPTURED',
    'ADMIN_QUOTA_ADJUSTED'
));

COMMENT ON TABLE app.user_quotas IS 'One quota snapshot per user/resource/UTC-natural-month; used+reserved<=limit enforced by the database.';
COMMENT ON TABLE app.quota_usage_logs IS 'Append-only quota ledger; every real state change writes one row keyed by a stable operation_key so idempotent replays never duplicate.';
COMMENT ON TABLE app.subscriptions IS 'Reserved subscription structure for later rounds; no payment provider wiring yet.';
COMMENT ON COLUMN app.job_matches.quota_reservation_key IS 'Optional quota reservation key written by new AI analysis requests for later settlement; historical rows stay NULL.';
COMMENT ON CONSTRAINT audit_logs_action_check ON app.audit_logs IS 'Stable action whitelist including ADMIN_QUOTA_ADJUSTED; mirrored by Java AuditWriter.ALLOWED_ACTIONS (keep in sync).';

-- 9. Admin backend (P9 batch 2A): narrow SECURITY DEFINER functions only.
--
-- Security boundary:
--   * Every admin function verifies the ACTOR (p_actor_id) is an ACTIVE ADMIN
--     as its first step via app.require_active_admin and raises a permission
--     SQLSTATE (42501) otherwise.
--   * Every admin function runs SECURITY DEFINER with a fixed search_path and
--     row_security=off so it may read across users — but never through a broad
--     table grant: REVOKE ALL FROM PUBLIC and only GRANT EXECUTE to the app
--     role for the exact function. Existing business-table RLS policies are
--     never touched.
--   * Emails are masked inside SQL before Java ever sees them; no function
--     returns password_hash, token/API-key/cookie columns, resume payloads,
--     prompts or raw model responses.

-- 9.1 Internal guard: only an ACTIVE ADMIN may use admin functions. This is
--     deliberately NOT granted to the app role; the admin functions call it
--     internally under their own SECURITY DEFINER ownership.
CREATE OR REPLACE FUNCTION app.require_active_admin(p_actor_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_role varchar;
    v_status varchar;
BEGIN
    IF p_actor_id IS NULL THEN
        RAISE EXCEPTION 'ADMIN_REQUIRED: only active admins may use admin functions'
            USING ERRCODE = '42501';
    END IF;
    SELECT u.role, u.status INTO v_role, v_status
    FROM app.users u
    WHERE u.id = p_actor_id AND u.deleted_at IS NULL;
    IF v_role IS NULL OR v_role <> 'ADMIN' OR v_status IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'ADMIN_REQUIRED: only active admins may use admin functions'
            USING ERRCODE = '42501';
    END IF;
END;
$function$;

REVOKE ALL ON FUNCTION app.require_active_admin(uuid) FROM PUBLIC;

-- 9.2 Email masking helper: keeps only the first two characters of the local
--     part plus the full domain, so Java never receives a complete email.
CREATE OR REPLACE FUNCTION app.mask_email(p_email text)
RETURNS text
LANGUAGE sql
IMMUTABLE
AS $function$
    SELECT CASE
        WHEN p_email IS NULL OR p_email = '' THEN NULL
        WHEN position('@' in p_email) <= 1 THEN '***'
        ELSE left(split_part(p_email, '@', 1), 2) || '***@' || split_part(p_email, '@', 2)
    END
$function$;

-- 9.3 Paginated user list with per-user stats and the overall total. Page size
--     is clamped to [1,100]; ordering is fixed (created_at DESC) and never
--     driven by client input.
CREATE OR REPLACE FUNCTION app.admin_list_users(
    p_actor_id uuid,
    p_page bigint,
    p_page_size bigint
)
RETURNS TABLE (
    total_count bigint,
    user_id uuid,
    email_masked text,
    role varchar,
    status varchar,
    created_at timestamptz,
    plan_code varchar,
    analysis_total bigint,
    analysis_used bigint,
    analysis_reserved bigint,
    analysis_remaining bigint,
    delivery_total bigint,
    delivery_used bigint,
    delivery_reserved bigint,
    delivery_remaining bigint,
    job_count bigint,
    match_count bigint,
    delivery_count bigint,
    success_count bigint,
    failed_count bigint,
    active_device_count bigint
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_limit bigint;
    v_offset bigint;
BEGIN
    PERFORM app.require_active_admin(p_actor_id);
    v_limit := LEAST(GREATEST(p_page_size, 1), 100);
    v_offset := GREATEST(p_page, 0) * v_limit;
    RETURN QUERY
    SELECT
        count(*) OVER () AS total_count,
        u.id,
        app.mask_email(u.email::text),
        u.role,
        u.status,
        u.created_at,
        COALESCE(ai.plan_code, d.plan_code, 'FREE'),
        COALESCE(ai.limit_amount, 0),
        COALESCE(ai.used_amount, 0),
        COALESCE(ai.reserved_amount, 0),
        GREATEST(COALESCE(ai.limit_amount, 0) - COALESCE(ai.used_amount, 0) - COALESCE(ai.reserved_amount, 0), 0),
        COALESCE(d.limit_amount, 0),
        COALESCE(d.used_amount, 0),
        COALESCE(d.reserved_amount, 0),
        GREATEST(COALESCE(d.limit_amount, 0) - COALESCE(d.used_amount, 0) - COALESCE(d.reserved_amount, 0), 0),
        COALESCE(j.job_count, 0),
        COALESCE(m.match_count, 0),
        COALESCE(t.delivery_count, 0),
        COALESCE(s.success_count, 0),
        COALESCE(f.failed_count, 0),
        COALESCE(dev.active_device_count, 0)
    FROM app.users u
    LEFT JOIN app.user_quotas ai ON ai.user_id = u.id AND ai.resource_code = 'AI_ANALYSIS'
        AND ai.period_start <= now() AND now() < ai.period_end
    LEFT JOIN app.user_quotas d ON d.user_id = u.id AND d.resource_code = 'DELIVERY_CONFIRM'
        AND d.period_start <= now() AND now() < d.period_end
    LEFT JOIN (
        SELECT jp.user_id, count(*) AS job_count FROM app.job_posts jp GROUP BY jp.user_id
    ) j ON j.user_id = u.id
    LEFT JOIN (
        SELECT jm.user_id, count(*) AS match_count FROM app.job_matches jm GROUP BY jm.user_id
    ) m ON m.user_id = u.id
    LEFT JOIN (
        SELECT dt.user_id, count(*) AS delivery_count FROM app.delivery_tasks dt GROUP BY dt.user_id
    ) t ON t.user_id = u.id
    LEFT JOIN (
        SELECT dt.user_id, count(*) AS success_count FROM app.delivery_tasks dt
        WHERE dt.status = 'SUCCESS' GROUP BY dt.user_id
    ) s ON s.user_id = u.id
    LEFT JOIN (
        SELECT dt.user_id, count(*) AS failed_count FROM app.delivery_tasks dt
        WHERE dt.status = 'FAILED' GROUP BY dt.user_id
    ) f ON f.user_id = u.id
    LEFT JOIN (
        SELECT pd.user_id, count(*) AS active_device_count FROM app.plugin_devices pd
        WHERE pd.status = 'ACTIVE' GROUP BY pd.user_id
    ) dev ON dev.user_id = u.id
    WHERE u.deleted_at IS NULL
    ORDER BY u.created_at DESC, u.id DESC
    LIMIT v_limit OFFSET v_offset;
END;
$function$;

REVOKE ALL ON FUNCTION app.admin_list_users(uuid, bigint, bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.admin_list_users(uuid, bigint, bigint) TO ${app_role};

-- 9.4 Single-user detail (same shape as one list row). Missing or soft-deleted
--     targets raise a recognizable marker so the API can answer 404 without
--     leaking whether the account ever existed.
CREATE OR REPLACE FUNCTION app.admin_get_user_detail(p_actor_id uuid, p_target_user_id uuid)
RETURNS TABLE (
    total_count bigint,
    user_id uuid,
    email_masked text,
    role varchar,
    status varchar,
    created_at timestamptz,
    plan_code varchar,
    analysis_total bigint,
    analysis_used bigint,
    analysis_reserved bigint,
    analysis_remaining bigint,
    delivery_total bigint,
    delivery_used bigint,
    delivery_reserved bigint,
    delivery_remaining bigint,
    job_count bigint,
    match_count bigint,
    delivery_count bigint,
    success_count bigint,
    failed_count bigint,
    active_device_count bigint
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
BEGIN
    PERFORM app.require_active_admin(p_actor_id);
    IF NOT EXISTS (
        SELECT 1 FROM app.users WHERE id = p_target_user_id AND deleted_at IS NULL
    ) THEN
        RAISE EXCEPTION 'ADMIN_TARGET_NOT_FOUND' USING ERRCODE = 'P0001';
    END IF;
    RETURN QUERY
    SELECT
        1::bigint AS total_count,
        u.id,
        app.mask_email(u.email::text),
        u.role,
        u.status,
        u.created_at,
        COALESCE(ai.plan_code, d.plan_code, 'FREE'),
        COALESCE(ai.limit_amount, 0),
        COALESCE(ai.used_amount, 0),
        COALESCE(ai.reserved_amount, 0),
        GREATEST(COALESCE(ai.limit_amount, 0) - COALESCE(ai.used_amount, 0) - COALESCE(ai.reserved_amount, 0), 0),
        COALESCE(d.limit_amount, 0),
        COALESCE(d.used_amount, 0),
        COALESCE(d.reserved_amount, 0),
        GREATEST(COALESCE(d.limit_amount, 0) - COALESCE(d.used_amount, 0) - COALESCE(d.reserved_amount, 0), 0),
        COALESCE(j.job_count, 0),
        COALESCE(m.match_count, 0),
        COALESCE(t.delivery_count, 0),
        COALESCE(s.success_count, 0),
        COALESCE(f.failed_count, 0),
        COALESCE(dev.active_device_count, 0)
    FROM app.users u
    LEFT JOIN app.user_quotas ai ON ai.user_id = u.id AND ai.resource_code = 'AI_ANALYSIS'
        AND ai.period_start <= now() AND now() < ai.period_end
    LEFT JOIN app.user_quotas d ON d.user_id = u.id AND d.resource_code = 'DELIVERY_CONFIRM'
        AND d.period_start <= now() AND now() < d.period_end
    LEFT JOIN (
        SELECT jp.user_id, count(*) AS job_count FROM app.job_posts jp GROUP BY jp.user_id
    ) j ON j.user_id = u.id
    LEFT JOIN (
        SELECT jm.user_id, count(*) AS match_count FROM app.job_matches jm GROUP BY jm.user_id
    ) m ON m.user_id = u.id
    LEFT JOIN (
        SELECT dt.user_id, count(*) AS delivery_count FROM app.delivery_tasks dt GROUP BY dt.user_id
    ) t ON t.user_id = u.id
    LEFT JOIN (
        SELECT dt.user_id, count(*) AS success_count FROM app.delivery_tasks dt
        WHERE dt.status = 'SUCCESS' GROUP BY dt.user_id
    ) s ON s.user_id = u.id
    LEFT JOIN (
        SELECT dt.user_id, count(*) AS failed_count FROM app.delivery_tasks dt
        WHERE dt.status = 'FAILED' GROUP BY dt.user_id
    ) f ON f.user_id = u.id
    LEFT JOIN (
        SELECT pd.user_id, count(*) AS active_device_count FROM app.plugin_devices pd
        WHERE pd.status = 'ACTIVE' GROUP BY pd.user_id
    ) dev ON dev.user_id = u.id
    WHERE u.id = p_target_user_id AND u.deleted_at IS NULL;
END;
$function$;

REVOKE ALL ON FUNCTION app.admin_get_user_detail(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.admin_get_user_detail(uuid, uuid) TO ${app_role};

-- 9.5 Current-period quota rows of one user (both resources).
CREATE OR REPLACE FUNCTION app.admin_get_user_quota_rows(p_actor_id uuid, p_target_user_id uuid)
RETURNS TABLE (
    quota_id uuid,
    plan_code varchar,
    resource_code varchar,
    total bigint,
    used bigint,
    reserved bigint,
    remaining bigint,
    reset_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
BEGIN
    PERFORM app.require_active_admin(p_actor_id);
    IF NOT EXISTS (
        SELECT 1 FROM app.users WHERE id = p_target_user_id AND deleted_at IS NULL
    ) THEN
        RAISE EXCEPTION 'ADMIN_TARGET_NOT_FOUND' USING ERRCODE = 'P0001';
    END IF;
    RETURN QUERY
    SELECT q.id, q.plan_code, q.resource_code, q.limit_amount, q.used_amount, q.reserved_amount,
           GREATEST(q.limit_amount - q.used_amount - q.reserved_amount, 0),
           q.period_end
    FROM app.user_quotas q
    WHERE q.user_id = p_target_user_id AND q.period_start <= now() AND now() < q.period_end
    ORDER BY q.resource_code;
END;
$function$;

REVOKE ALL ON FUNCTION app.admin_get_user_quota_rows(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.admin_get_user_quota_rows(uuid, uuid) TO ${app_role};

-- 9.6 Dashboard aggregates.
CREATE OR REPLACE FUNCTION app.admin_dashboard(p_actor_id uuid)
RETURNS TABLE (
    total_users bigint,
    active_users bigint,
    jobs bigint,
    ai_analyses bigint,
    delivery_tasks bigint,
    success_count bigint,
    failed_count bigint,
    active_devices bigint,
    recent_failures bigint
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
BEGIN
    PERFORM app.require_active_admin(p_actor_id);
    RETURN QUERY
    SELECT
        (SELECT count(*) FROM app.users WHERE deleted_at IS NULL),
        (SELECT count(*) FROM app.users WHERE deleted_at IS NULL AND status = 'ACTIVE'),
        (SELECT count(*) FROM app.job_posts),
        (SELECT count(*) FROM app.job_matches),
        (SELECT count(*) FROM app.delivery_tasks),
        (SELECT count(*) FROM app.delivery_tasks WHERE status = 'SUCCESS'),
        (SELECT count(*) FROM app.delivery_tasks WHERE status = 'FAILED'),
        (SELECT count(*) FROM app.plugin_devices WHERE status = 'ACTIVE'),
        (SELECT count(*) FROM app.delivery_tasks
            WHERE status = 'FAILED' AND updated_at >= now() - interval '7 days');
END;
$function$;

REVOKE ALL ON FUNCTION app.admin_dashboard(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.admin_dashboard(uuid) TO ${app_role};

-- 9.7 Recent audit events. Never returns details, ip_hash, user_agent_summary
--     or request_id; the target user's email is masked in SQL.
CREATE OR REPLACE FUNCTION app.admin_list_audit_logs(p_actor_id uuid, p_limit bigint)
RETURNS TABLE (
    id bigint,
    user_id uuid,
    user_email_masked text,
    actor_type varchar,
    action varchar,
    target_type varchar,
    target_id uuid,
    result varchar,
    created_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
BEGIN
    PERFORM app.require_active_admin(p_actor_id);
    RETURN QUERY
    SELECT a.id, a.user_id, app.mask_email(u.email::text), a.actor_type, a.action,
           a.target_type, a.target_id, a.result, a.created_at
    FROM app.audit_logs a
    LEFT JOIN app.users u ON u.id = a.user_id
    ORDER BY a.created_at DESC, a.id DESC
    LIMIT LEAST(GREATEST(p_limit, 1), 100);
END;
$function$;

REVOKE ALL ON FUNCTION app.admin_list_audit_logs(uuid, bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.admin_list_audit_logs(uuid, bigint) TO ${app_role};

-- 9.8 Recent FAILED delivery tasks. Returns only the short sanitized error
--     message (schema-bounded to 500 chars, trimmed to 120 here); no resumes,
--     page evidence, URLs or tokens.
CREATE OR REPLACE FUNCTION app.admin_list_delivery_failures(p_actor_id uuid, p_limit bigint)
RETURNS TABLE (
    task_id uuid,
    user_id uuid,
    email_masked text,
    platform varchar,
    status varchar,
    last_error_code varchar,
    error_message varchar,
    updated_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
BEGIN
    PERFORM app.require_active_admin(p_actor_id);
    RETURN QUERY
    SELECT t.id, t.user_id, app.mask_email(u.email::text), jp.platform, t.status,
           t.last_error_code, left(t.last_error_message, 120)::varchar, t.updated_at
    FROM app.delivery_tasks t
    JOIN app.users u ON u.id = t.user_id
    JOIN app.job_posts jp ON jp.id = t.job_post_id AND jp.user_id = t.user_id
    WHERE t.status = 'FAILED'
    ORDER BY t.updated_at DESC, t.id DESC
    LIMIT LEAST(GREATEST(p_limit, 1), 100);
END;
$function$;

REVOKE ALL ON FUNCTION app.admin_list_delivery_failures(uuid, bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.admin_list_delivery_failures(uuid, bigint) TO ${app_role};

-- 9.9 Admin quota adjustment. Idempotently upserts the target's two current
--     UTC-month rows under a row lock, validates both new totals against the
--     used+reserved floor BEFORE mutating anything, writes ADJUST ledger rows
--     only for real total changes (replay-safe via the stable operation key),
--     and returns either OK or a recognizable QUOTA_BELOW_USAGE outcome.
CREATE OR REPLACE FUNCTION app.admin_set_quota(
    p_actor_id uuid,
    p_target_user_id uuid,
    p_plan varchar,
    p_analysis_total bigint,
    p_delivery_total bigint,
    p_reason varchar,
    p_operation_key varchar
)
RETURNS TABLE (
    outcome varchar,
    applied boolean,
    plan_code varchar,
    analysis_old_total bigint,
    analysis_total bigint,
    analysis_used bigint,
    analysis_reserved bigint,
    analysis_remaining bigint,
    delivery_old_total bigint,
    delivery_total bigint,
    delivery_used bigint,
    delivery_reserved bigint,
    delivery_remaining bigint
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    -- The app initializes quotas against the UTC natural month (QuotaService
    -- uses ZoneOffset.UTC), so the admin adjust must use the same boundaries
    -- regardless of the session TimeZone.
    v_period_start timestamptz :=
        (date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')::timestamptz;
    v_period_end timestamptz :=
        ((date_trunc('month', now() AT TIME ZONE 'UTC') + interval '1 month') AT TIME ZONE 'UTC')::timestamptz;
    v_analysis app.user_quotas%ROWTYPE;
    v_delivery app.user_quotas%ROWTYPE;
    v_reason varchar(200);
    v_analysis_key varchar(120);
    v_delivery_key varchar(120);
    v_analysis_delta bigint;
    v_delivery_delta bigint;
    v_applied boolean := false;
BEGIN
    PERFORM app.require_active_admin(p_actor_id);

    IF p_plan IS NULL OR p_plan NOT IN (
        'FREE', 'MONTHLY', 'PREMIUM_MONTHLY', 'JOB_SEASON', 'COACHING'
    ) THEN
        RAISE EXCEPTION 'ADMIN_INVALID_PLAN' USING ERRCODE = 'P0001';
    END IF;
    IF p_analysis_total IS NULL OR p_delivery_total IS NULL
       OR p_analysis_total < 0 OR p_analysis_total > 1000000
       OR p_delivery_total < 0 OR p_delivery_total > 1000000 THEN
        RAISE EXCEPTION 'ADMIN_QUOTA_OUT_OF_RANGE' USING ERRCODE = 'P0001';
    END IF;
    IF p_reason IS NULL OR length(trim(p_reason)) = 0 OR char_length(p_reason) > 200 THEN
        RAISE EXCEPTION 'ADMIN_REASON_INVALID' USING ERRCODE = 'P0001';
    END IF;
    v_reason := left(trim(p_reason), 200);
    IF p_operation_key IS NULL OR length(trim(p_operation_key)) = 0 OR length(p_operation_key) > 120 THEN
        RAISE EXCEPTION 'ADMIN_OPERATION_KEY_INVALID' USING ERRCODE = 'P0001';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM app.users WHERE id = p_target_user_id AND deleted_at IS NULL
    ) THEN
        RAISE EXCEPTION 'ADMIN_TARGET_NOT_FOUND' USING ERRCODE = 'P0001';
    END IF;

    -- Idempotent upsert of both current-month rows, then row locks so
    -- concurrent adjusts serialize on the same rows.
    INSERT INTO app.user_quotas (
        user_id, plan_code, resource_code, period_start, period_end, limit_amount
    ) VALUES (
        p_target_user_id, p_plan, 'AI_ANALYSIS', v_period_start, v_period_end, p_analysis_total
    )
    ON CONFLICT (user_id, resource_code, period_start, period_end) DO NOTHING;
    INSERT INTO app.user_quotas (
        user_id, plan_code, resource_code, period_start, period_end, limit_amount
    ) VALUES (
        p_target_user_id, p_plan, 'DELIVERY_CONFIRM', v_period_start, v_period_end, p_delivery_total
    )
    ON CONFLICT (user_id, resource_code, period_start, period_end) DO NOTHING;

    SELECT * INTO v_analysis FROM app.user_quotas
    WHERE user_id = p_target_user_id AND resource_code = 'AI_ANALYSIS'
      AND period_start <= now() AND now() < period_end
    FOR UPDATE;
    SELECT * INTO v_delivery FROM app.user_quotas
    WHERE user_id = p_target_user_id AND resource_code = 'DELIVERY_CONFIRM'
      AND period_start <= now() AND now() < period_end
    FOR UPDATE;

    IF v_analysis.id IS NULL OR v_delivery.id IS NULL THEN
        RAISE EXCEPTION 'ADMIN_QUOTA_ROWS_MISSING' USING ERRCODE = 'P0001';
    END IF;

    -- Validate before mutating anything: a rejected adjust is a no-op.
    IF p_analysis_total < v_analysis.used_amount + v_analysis.reserved_amount
       OR p_delivery_total < v_delivery.used_amount + v_delivery.reserved_amount THEN
        RETURN QUERY SELECT
            'QUOTA_BELOW_USAGE'::varchar,
            false::boolean,
            p_plan::varchar,
            v_analysis.limit_amount, v_analysis.limit_amount, v_analysis.used_amount, v_analysis.reserved_amount,
            GREATEST(v_analysis.limit_amount - v_analysis.used_amount - v_analysis.reserved_amount, 0),
            v_delivery.limit_amount, v_delivery.limit_amount, v_delivery.used_amount, v_delivery.reserved_amount,
            GREATEST(v_delivery.limit_amount - v_delivery.used_amount - v_delivery.reserved_amount, 0);
        RETURN;
    END IF;

    v_analysis_delta := p_analysis_total - v_analysis.limit_amount;
    v_delivery_delta := p_delivery_total - v_delivery.limit_amount;
    v_applied := v_analysis.plan_code IS DISTINCT FROM p_plan
        OR v_delivery.plan_code IS DISTINCT FROM p_plan
        OR v_analysis_delta <> 0
        OR v_delivery_delta <> 0;

    UPDATE app.user_quotas AS q
    SET plan_code = p_plan, limit_amount = p_analysis_total, version = version + 1, updated_at = now()
    WHERE q.id = v_analysis.id AND q.user_id = p_target_user_id
      AND (q.plan_code IS DISTINCT FROM p_plan OR q.limit_amount IS DISTINCT FROM p_analysis_total);
    UPDATE app.user_quotas AS q
    SET plan_code = p_plan, limit_amount = p_delivery_total, version = version + 1, updated_at = now()
    WHERE q.id = v_delivery.id AND q.user_id = p_target_user_id
      AND (q.plan_code IS DISTINCT FROM p_plan OR q.limit_amount IS DISTINCT FROM p_delivery_total);

    -- Real changes only: a replay (same operation key) or an unchanged total
    -- never mints a new ledger row (amount > 0 is enforced by the table).
    v_analysis_key := left(p_operation_key || ':ANALYSIS', 120);
    IF v_analysis_delta <> 0 AND NOT EXISTS (
        SELECT 1 FROM app.quota_usage_logs
        WHERE user_id = p_target_user_id AND operation_key = v_analysis_key
    ) THEN
        INSERT INTO app.quota_usage_logs (
            user_id, quota_id, resource_code, action, amount, reference_type, reference_id,
            operation_key, reservation_id, reason, balance_after, metadata
        ) VALUES (
            p_target_user_id, v_analysis.id, 'AI_ANALYSIS', 'ADJUST', abs(v_analysis_delta),
            'ADMIN_QUOTA_ADJUST', p_actor_id, v_analysis_key, NULL, v_reason,
            v_analysis.used_amount,
            jsonb_build_object(
                'oldTotal', v_analysis.limit_amount,
                'newTotal', p_analysis_total,
                'adminId', p_actor_id
            )
        );
    END IF;
    v_delivery_key := left(p_operation_key || ':DELIVERY', 120);
    IF v_delivery_delta <> 0 AND NOT EXISTS (
        SELECT 1 FROM app.quota_usage_logs
        WHERE user_id = p_target_user_id AND operation_key = v_delivery_key
    ) THEN
        INSERT INTO app.quota_usage_logs (
            user_id, quota_id, resource_code, action, amount, reference_type, reference_id,
            operation_key, reservation_id, reason, balance_after, metadata
        ) VALUES (
            p_target_user_id, v_delivery.id, 'DELIVERY_CONFIRM', 'ADJUST', abs(v_delivery_delta),
            'ADMIN_QUOTA_ADJUST', p_actor_id, v_delivery_key, NULL, v_reason,
            v_delivery.used_amount,
            jsonb_build_object(
                'oldTotal', v_delivery.limit_amount,
                'newTotal', p_delivery_total,
                'adminId', p_actor_id
            )
        );
    END IF;

    RETURN QUERY SELECT
        'OK'::varchar,
        v_applied::boolean,
        p_plan::varchar,
        v_analysis.limit_amount, p_analysis_total, v_analysis.used_amount, v_analysis.reserved_amount,
        GREATEST(p_analysis_total - v_analysis.used_amount - v_analysis.reserved_amount, 0),
        v_delivery.limit_amount, p_delivery_total, v_delivery.used_amount, v_delivery.reserved_amount,
        GREATEST(p_delivery_total - v_delivery.used_amount - v_delivery.reserved_amount, 0);
END;
$function$;

REVOKE ALL ON FUNCTION app.admin_set_quota(uuid, uuid, varchar, bigint, bigint, varchar, varchar) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.admin_set_quota(uuid, uuid, varchar, bigint, bigint, varchar, varchar) TO ${app_role};

COMMENT ON FUNCTION app.require_active_admin(uuid) IS 'Internal guard: only an ACTIVE ADMIN passes; failure raises 42501. Not granted to the app role.';
COMMENT ON FUNCTION app.admin_list_users(uuid, bigint, bigint) IS 'Admin: paginated user list with stats and total; emails masked in SQL.';
COMMENT ON FUNCTION app.admin_get_user_detail(uuid, uuid) IS 'Admin: single-user detail with stats; emails masked in SQL; unknown target raises ADMIN_TARGET_NOT_FOUND.';
COMMENT ON FUNCTION app.admin_get_user_quota_rows(uuid, uuid) IS 'Admin: current-period quota rows of one user.';
COMMENT ON FUNCTION app.admin_dashboard(uuid) IS 'Admin: platform-wide aggregates; requires active admin.';
COMMENT ON FUNCTION app.admin_list_audit_logs(uuid, bigint) IS 'Admin: recent audit events without details/ip_hash/user_agent/request_id.';
COMMENT ON FUNCTION app.admin_list_delivery_failures(uuid, bigint) IS 'Admin: recent FAILED delivery tasks with short sanitized error messages.';
COMMENT ON FUNCTION app.admin_set_quota(uuid, uuid, varchar, bigint, bigint, varchar, varchar) IS 'Admin: idempotent plan/limit adjustment for the target user current month; returns QUOTA_BELOW_USAGE instead of mutating when a new total is below used+reserved.';
