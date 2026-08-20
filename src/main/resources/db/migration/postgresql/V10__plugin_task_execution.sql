-- Round 8 (P8): plugin task execution — canonical status vocabulary, atomic
-- pending pull, batch pause and the unified failure codes.
--
-- Key design decisions:
--   The persistent delivery_tasks.status vocabulary is canonicalized to exactly
--     eight values: WAITING_CONFIRM, CONFIRMED, PULLED_BY_PLUGIN, RUNNING,
--     SUCCESS, FAILED, SKIPPED, PAUSED_NEED_USER. Legacy rows are migrated
--     in place: PENDING_CONFIRMATION -> WAITING_CONFIRM, EXECUTING -> RUNNING,
--     SUCCEEDED -> SUCCESS, LEASED -> PULLED_BY_PLUGIN, PAUSED ->
--     PAUSED_NEED_USER, CANCELLED -> SKIPPED. Legacy last_error_code
--     JOB_CLOSED is migrated to the canonical JOB_EXPIRED and JOB_CLOSED is
--     never accepted again by the API.
--   The plugin pending endpoint becomes an atomic pull: CONFIRMED ->
--     PULLED_BY_PLUGIN binds the calling device and writes a PULLED event in
--     one transaction (narrow SECURITY DEFINER function). A repeated pull from
--     the same device returns the not-yet-started PULLED_BY_PLUGIN tasks so a
--     restarted service worker can never lose a handed-out task, and it never
--     writes a second event.
--   Execution flow: start moves PULLED_BY_PLUGIN -> RUNNING with a short
--     lease; success/fail/pause answer from RUNNING only; batch-pause moves
--     every RUNNING task of the calling user+device to PAUSED_NEED_USER.
--   Failure codes are the unified eight: LOGIN_REQUIRED, CAPTCHA_REQUIRED,
--     RISK_CONTROL pause; JOB_EXPIRED, BUTTON_NOT_FOUND, PAGE_STRUCTURE_CHANGED
--     fail terminally; NETWORK_ERROR, UNKNOWN_ERROR fail retryably.
--     last_error_code is CHECK-constrained to NULL or exactly those eight
--     values. Trigger-only reasons (USER_REQUESTED, FAILURE_THRESHOLD,
--     MAX_ATTEMPTS_EXCEEDED) never persist into last_error_code: batch pause
--     and lease recovery fold them into UNKNOWN_ERROR while the event details
--     keep the original triggerReason.
--   All functions stay SECURITY DEFINER with fixed search_path, row_security
--     off, revoked from PUBLIC and granted only to the app role; Web RLS
--     policies are untouched. V1-V9 databases migrate in place; a fresh
--     database runs V1-V10 in order.

-- 1. Drop the old CHECK constraints FIRST: they only accept the legacy
--    vocabulary and would reject the in-place status migration below.
ALTER TABLE app.delivery_tasks DROP CONSTRAINT delivery_tasks_status_check;
ALTER TABLE app.delivery_tasks DROP CONSTRAINT delivery_tasks_status_confirmation_check;
ALTER TABLE app.delivery_tasks DROP CONSTRAINT delivery_tasks_execution_state_check;
ALTER TABLE app.delivery_tasks DROP CONSTRAINT delivery_tasks_finished_check;
ALTER TABLE app.delivery_task_events DROP CONSTRAINT delivery_task_events_status_check;

-- 2. Migrate legacy status values and error codes in place. Legacy LEASED
--    rows (which do not exist in practice) map to PULLED_BY_PLUGIN and lose
--    any lease/execution attribution so the new field-consistency CHECK holds.
UPDATE app.delivery_tasks SET status = 'WAITING_CONFIRM' WHERE status = 'PENDING_CONFIRMATION';
UPDATE app.delivery_tasks SET status = 'RUNNING' WHERE status = 'EXECUTING';
UPDATE app.delivery_tasks SET status = 'SUCCESS' WHERE status = 'SUCCEEDED';
UPDATE app.delivery_tasks SET status = 'PULLED_BY_PLUGIN',
    lease_id = NULL, leased_at = NULL, lease_expires_at = NULL, execution_id = NULL
    WHERE status = 'LEASED';
UPDATE app.delivery_tasks SET status = 'PAUSED_NEED_USER', finished_at = NULL
    WHERE status = 'PAUSED';
UPDATE app.delivery_tasks SET status = 'SKIPPED' WHERE status = 'CANCELLED';

UPDATE app.delivery_tasks SET last_error_code = 'JOB_EXPIRED'
WHERE last_error_code = 'JOB_CLOSED';

-- Every other non-NULL legacy/system-generated value (e.g. the pre-P8
-- MAX_ATTEMPTS_EXCEEDED from older lease sweeps) folds into the canonical
-- UNKNOWN_ERROR so the new error-code CHECK below can never reject the
-- in-place migration; NULL stays NULL untouched.
UPDATE app.delivery_tasks SET last_error_code = 'UNKNOWN_ERROR'
WHERE last_error_code IS NOT NULL
  AND last_error_code NOT IN (
      'LOGIN_REQUIRED', 'CAPTCHA_REQUIRED', 'RISK_CONTROL',
      'JOB_EXPIRED', 'BUTTON_NOT_FOUND', 'PAGE_STRUCTURE_CHANGED',
      'NETWORK_ERROR', 'UNKNOWN_ERROR'
  );

UPDATE app.delivery_task_events SET from_status = 'WAITING_CONFIRM' WHERE from_status = 'PENDING_CONFIRMATION';
UPDATE app.delivery_task_events SET to_status = 'WAITING_CONFIRM' WHERE to_status = 'PENDING_CONFIRMATION';
UPDATE app.delivery_task_events SET from_status = 'RUNNING' WHERE from_status = 'EXECUTING';
UPDATE app.delivery_task_events SET to_status = 'RUNNING' WHERE to_status = 'EXECUTING';
UPDATE app.delivery_task_events SET from_status = 'SUCCESS' WHERE from_status = 'SUCCEEDED';
UPDATE app.delivery_task_events SET to_status = 'SUCCESS' WHERE to_status = 'SUCCEEDED';
UPDATE app.delivery_task_events SET from_status = 'PULLED_BY_PLUGIN' WHERE from_status = 'LEASED';
UPDATE app.delivery_task_events SET to_status = 'PULLED_BY_PLUGIN' WHERE to_status = 'LEASED';
UPDATE app.delivery_task_events SET from_status = 'PAUSED_NEED_USER' WHERE from_status = 'PAUSED';
UPDATE app.delivery_task_events SET to_status = 'PAUSED_NEED_USER' WHERE to_status = 'PAUSED';
UPDATE app.delivery_task_events SET from_status = 'SKIPPED' WHERE from_status = 'CANCELLED';
UPDATE app.delivery_task_events SET to_status = 'SKIPPED' WHERE to_status = 'CANCELLED';

-- 3. Status CHECK constraint: exactly the eight canonical values.
ALTER TABLE app.delivery_tasks ADD CONSTRAINT delivery_tasks_status_check CHECK (status IN (
    'WAITING_CONFIRM', 'CONFIRMED', 'PULLED_BY_PLUGIN', 'RUNNING',
    'SUCCESS', 'FAILED', 'SKIPPED', 'PAUSED_NEED_USER'
));

-- 3b. Error-code CHECK constraint: NULL or exactly the unified eight.
--     Trigger-only reasons (USER_REQUESTED / FAILURE_THRESHOLD /
--     MAX_ATTEMPTS_EXCEEDED) can never persist; batch pause and the lease
--     sweep fold them into UNKNOWN_ERROR and keep the original value in the
--     event details. The database now rejects any future non-canonical write.
ALTER TABLE app.delivery_tasks ADD CONSTRAINT delivery_tasks_last_error_code_check CHECK (
    last_error_code IS NULL OR last_error_code IN (
        'LOGIN_REQUIRED', 'CAPTCHA_REQUIRED', 'RISK_CONTROL',
        'JOB_EXPIRED', 'BUTTON_NOT_FOUND', 'PAGE_STRUCTURE_CHANGED',
        'NETWORK_ERROR', 'UNKNOWN_ERROR'
    )
);

-- 4. Field-consistency CHECKs for the canonical state machine.
ALTER TABLE app.delivery_tasks ADD CONSTRAINT delivery_tasks_status_confirmation_check CHECK (
    (status = 'WAITING_CONFIRM' AND confirmed_at IS NULL AND confirmed_by IS NULL)
    OR (status IN ('CONFIRMED', 'PULLED_BY_PLUGIN', 'RUNNING', 'SUCCESS', 'FAILED', 'PAUSED_NEED_USER')
        AND confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL)
    OR (status = 'SKIPPED' AND confirmed_at IS NULL AND confirmed_by IS NULL)
);

ALTER TABLE app.delivery_tasks ADD CONSTRAINT delivery_tasks_execution_state_check CHECK (
    (status = 'WAITING_CONFIRM'
        AND assigned_device_id IS NULL
        AND lease_id IS NULL AND leased_at IS NULL AND lease_expires_at IS NULL
        AND execution_id IS NULL)
    OR (status = 'CONFIRMED'
        AND lease_id IS NULL AND leased_at IS NULL AND lease_expires_at IS NULL
        AND execution_id IS NULL)
    OR (status = 'PULLED_BY_PLUGIN'
        AND assigned_device_id IS NOT NULL
        AND lease_id IS NULL AND leased_at IS NULL AND lease_expires_at IS NULL
        AND execution_id IS NULL)
    OR (status = 'RUNNING'
        AND assigned_device_id IS NOT NULL
        AND lease_id IS NOT NULL AND leased_at IS NOT NULL AND lease_expires_at IS NOT NULL
        AND execution_id IS NOT NULL)
    OR (status = 'SKIPPED'
        AND assigned_device_id IS NULL
        AND lease_id IS NULL AND leased_at IS NULL AND lease_expires_at IS NULL
        AND execution_id IS NULL)
    OR (status IN ('SUCCESS', 'FAILED', 'PAUSED_NEED_USER')
        AND lease_id IS NULL AND leased_at IS NULL AND lease_expires_at IS NULL)
);

ALTER TABLE app.delivery_tasks ADD CONSTRAINT delivery_tasks_finished_check CHECK (
    status NOT IN ('SUCCESS', 'FAILED', 'SKIPPED') OR finished_at IS NOT NULL
);

-- 5. Partial unique indexes pinned to the new status vocabulary.
DROP INDEX app.delivery_tasks_user_job_active_unique;
CREATE UNIQUE INDEX delivery_tasks_user_job_active_unique
    ON app.delivery_tasks (user_id, job_post_id)
    WHERE status IN ('WAITING_CONFIRM', 'CONFIRMED', 'PULLED_BY_PLUGIN', 'RUNNING', 'PAUSED_NEED_USER');

DROP INDEX app.delivery_tasks_lease_recovery_idx;
CREATE INDEX delivery_tasks_lease_recovery_idx
    ON app.delivery_tasks (lease_expires_at)
    WHERE status = 'RUNNING';

-- 6. Event vocabulary: status lists use the canonical values; two new event
--    types (PULLED, BATCH_PAUSED) join the historical ones which stay
--    accepted because events are append-only.
ALTER TABLE app.delivery_task_events DROP CONSTRAINT delivery_task_events_type_check;
ALTER TABLE app.delivery_task_events ADD CONSTRAINT delivery_task_events_type_check CHECK (event_type IN (
    'CREATED', 'CONFIRMED', 'GREETING_UPDATED', 'CONFIRMATION_INVALIDATED',
    'SKIPPED', 'LEASED', 'STARTED', 'SUCCEEDED', 'FAILED', 'PAUSED',
    'LEASE_EXPIRED', 'DEVICE_REVOKED', 'PULLED', 'BATCH_PAUSED'
));

ALTER TABLE app.delivery_task_events ADD CONSTRAINT delivery_task_events_status_check CHECK (
    (from_status IS NULL OR from_status IN (
        'WAITING_CONFIRM', 'CONFIRMED', 'PULLED_BY_PLUGIN', 'RUNNING',
        'SUCCESS', 'FAILED', 'SKIPPED', 'PAUSED_NEED_USER'
    ))
    AND (to_status IS NULL OR to_status IN (
        'WAITING_CONFIRM', 'CONFIRMED', 'PULLED_BY_PLUGIN', 'RUNNING',
        'SUCCESS', 'FAILED', 'SKIPPED', 'PAUSED_NEED_USER'
    ))
);

-- 7. Match-apply trigger and device revocation use the canonical vocabulary.
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
               'WAITING_CONFIRM',
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
                NEW.user_id, v_task_id, 'CREATED', NULL, 'WAITING_CONFIRM',
                'SYSTEM', NULL,
                'auto-apply:' || NEW.id::text,
                jsonb_build_object('source', 'MATCH_APPLY', 'matchId', NEW.id)
            );
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;

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
      AND status IN ('WAITING_CONFIRM', 'CONFIRMED', 'PAUSED_NEED_USER', 'FAILED');

    FOR v_task IN
        SELECT id, status FROM app.delivery_tasks
        WHERE user_id = p_user_id
          AND assigned_device_id = p_device_id
          AND status IN ('PULLED_BY_PLUGIN', 'RUNNING')
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

-- 8. Atomic pending pull: the service pre-selects the trusted CONFIRMED task
--    ids (job URL trust is a Java allowlist judgment) and this function
--    claims exactly those ids: CONFIRMED -> PULLED_BY_PLUGIN bound to the
--    calling device with one PULLED event per task, all in one transaction.
--    A repeated pull returns the not-yet-started PULLED_BY_PLUGIN tasks of
--    this device (a plain read, never a second event) so a restarted service
--    worker can never lose a handed-out task. Only the device owner's own
--    CONFIRMED tasks are ever touched; lost races are skipped silently.
CREATE OR REPLACE FUNCTION app.plugin_tasks_pull(
    p_user_id uuid,
    p_device_id uuid,
    p_task_ids uuid[]
)
RETURNS TABLE (
    task_id uuid,
    task_version integer,
    job_platform varchar,
    job_url text,
    external_job_id varchar,
    job_title varchar,
    job_company varchar,
    task_greeting varchar,
    confirmed_at timestamptz,
    confirmation_version integer,
    task_status text
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_device_status varchar(24);
    v_task app.delivery_tasks%ROWTYPE;
BEGIN
    -- The calling device must belong to the user and still be ACTIVE; a
    -- revoked device can never pull anything.
    SELECT status INTO v_device_status
    FROM app.plugin_devices
    WHERE id = p_device_id AND user_id = p_user_id;
    IF NOT FOUND OR v_device_status <> 'ACTIVE' THEN
        RETURN;
    END IF;

    FOR v_task IN
        SELECT t.*
        FROM app.delivery_tasks t
        WHERE t.user_id = p_user_id
          AND t.id = ANY (p_task_ids)
          AND t.status = 'CONFIRMED'
          AND (t.assigned_device_id IS NULL OR t.assigned_device_id = p_device_id)
        ORDER BY t.confirmed_at ASC, t.created_at ASC
        FOR UPDATE SKIP LOCKED
    LOOP
        UPDATE app.delivery_tasks
        SET status = 'PULLED_BY_PLUGIN',
            assigned_device_id = p_device_id,
            version = version + 1
        WHERE id = v_task.id
          AND user_id = p_user_id
          AND status = 'CONFIRMED'
          AND (assigned_device_id IS NULL OR assigned_device_id = p_device_id)
        RETURNING version INTO v_task.version;

        IF NOT FOUND THEN
            CONTINUE;
        END IF;

        INSERT INTO app.delivery_task_events (
            user_id, delivery_task_id, event_type, from_status, to_status,
            actor_type, actor_id, event_key, details
        ) VALUES (
            p_user_id, v_task.id, 'PULLED', 'CONFIRMED', 'PULLED_BY_PLUGIN',
            'PLUGIN', p_device_id,
            'pulled:' || v_task.id::text || ':v' || v_task.version,
            jsonb_build_object('deviceId', p_device_id)
        );

        v_task.status := 'PULLED_BY_PLUGIN';
        RETURN QUERY
        SELECT t.id, t.version, jp.platform, jp.job_url, jp.external_job_id,
               jp.title, jp.company_name, t.greeting, t.confirmed_at,
               t.confirmation_version, 'PULLED_BY_PLUGIN'
        FROM app.delivery_tasks t
        JOIN app.job_posts jp ON jp.id = t.job_post_id AND jp.user_id = t.user_id
        WHERE t.id = v_task.id AND t.user_id = p_user_id;
    END LOOP;
END;
$function$;

-- 9. Atomic plugin start: PULLED_BY_PLUGIN -> RUNNING with a short lease.
--    Same replay/idempotency/device contracts as V6; only the vocabulary and
--    the entry status changed.
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
        IF v_task.status = 'RUNNING'
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

    IF v_task.status <> 'PULLED_BY_PLUGIN' THEN
        RETURN QUERY SELECT
            CASE WHEN v_task.status IN ('PULLED_BY_PLUGIN', 'RUNNING')
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

    IF v_task.assigned_device_id IS DISTINCT FROM p_device_id THEN
        RETURN QUERY SELECT 'TASK_ALREADY_CLAIMED', NULL::uuid, NULL::timestamptz, NULL::integer,
            NULL::integer, NULL::text, NULL::varchar, NULL::text, NULL::varchar,
            NULL::varchar, NULL::varchar;
        RETURN;
    END IF;

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
    SET status = 'RUNNING',
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
      AND status = 'PULLED_BY_PLUGIN'
      AND version = p_expected_version
      AND assigned_device_id = p_device_id
    RETURNING attempt_count, version INTO v_attempt_number, v_new_version;

    IF NOT FOUND THEN
        RETURN QUERY SELECT 'TASK_ALREADY_CLAIMED', NULL::uuid, NULL::timestamptz, NULL::integer,
            NULL::integer, NULL::text, NULL::varchar, NULL::text, NULL::varchar,
            NULL::varchar, NULL::varchar;
        RETURN;
    END IF;

    INSERT INTO app.delivery_task_events (
        user_id, delivery_task_id, event_type, from_status, to_status,
        actor_type, actor_id, event_key, idempotency_key_hash, details
    ) VALUES (
        p_user_id, p_task_id, 'STARTED', 'PULLED_BY_PLUGIN', 'RUNNING',
        'PLUGIN', p_device_id,
        'start:' || p_execution_id,
        p_idempotency_key_hash,
        jsonb_build_object('attemptNumber', v_attempt_number, 'leaseSeconds', v_lease_seconds,
                           'payloadHash', p_payload_hash)
    );

    RETURN QUERY
    SELECT 'OK', t.lease_id, t.lease_expires_at,
           v_attempt_number, v_new_version, 'RUNNING',
           jp.platform, jp.job_url, jp.title, jp.company_name, t.greeting
    FROM app.delivery_tasks t
    JOIN app.job_posts jp ON jp.id = t.job_post_id AND jp.user_id = t.user_id
    WHERE t.id = p_task_id AND t.user_id = p_user_id;
END;
$function$;

-- 10. Plugin success: RUNNING -> SUCCESS (terminal, never overwritten).
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

    IF v_task.assigned_device_id IS DISTINCT FROM p_device_id THEN
        RETURN QUERY SELECT 'LEASE_INVALID', NULL::integer, NULL::timestamptz;
        RETURN;
    END IF;

    IF v_task.version <> p_expected_version THEN
        RETURN QUERY SELECT 'VERSION_CONFLICT', NULL::integer, NULL::timestamptz;
        RETURN;
    END IF;

    IF v_task.status = 'RUNNING' THEN
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
    SET status = 'SUCCESS',
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
        p_user_id, p_task_id, 'SUCCEEDED', 'RUNNING', 'SUCCESS',
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

-- 11. Plugin fail: RUNNING -> FAILED with the unified error codes. The
--     server decides retryability per code: NETWORK_ERROR / UNKNOWN_ERROR are
--     retryable (re-confirmable), JOB_EXPIRED / BUTTON_NOT_FOUND /
--     PAGE_STRUCTURE_CHANGED are not. The client value is never trusted.
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

    IF v_task.assigned_device_id IS DISTINCT FROM p_device_id THEN
        RETURN QUERY SELECT 'LEASE_INVALID', NULL::integer, NULL::timestamptz, NULL::integer;
        RETURN;
    END IF;

    IF v_task.version <> p_expected_version THEN
        RETURN QUERY SELECT 'VERSION_CONFLICT', NULL::integer, NULL::timestamptz, NULL::integer;
        RETURN;
    END IF;

    IF v_task.status = 'RUNNING' THEN
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
        p_user_id, p_task_id, 'FAILED', 'RUNNING', 'FAILED',
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

-- 12. Plugin pause: RUNNING -> PAUSED_NEED_USER (only the three canonical
--     pause reasons), lease released, the user must re-confirm.
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
        IF v_task.status = 'PAUSED_NEED_USER' AND v_task.execution_id = p_execution_id THEN
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

    IF v_task.assigned_device_id IS DISTINCT FROM p_device_id THEN
        RETURN QUERY SELECT 'LEASE_INVALID', NULL::integer;
        RETURN;
    END IF;

    IF v_task.version <> p_expected_version THEN
        RETURN QUERY SELECT 'VERSION_CONFLICT', NULL::integer;
        RETURN;
    END IF;

    IF v_task.status = 'RUNNING' THEN
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
    SET status = 'PAUSED_NEED_USER',
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
        p_user_id, p_task_id, 'PAUSED', 'RUNNING', 'PAUSED_NEED_USER',
        'PLUGIN', p_device_id,
        'pause:' || p_execution_id || ':a' || v_task.attempt_count,
        p_idempotency_key_hash,
        jsonb_build_object('pauseReason', p_reason, 'attemptNumber', v_task.attempt_count,
                           'payloadHash', p_payload_hash)
    );

    RETURN QUERY SELECT 'OK', v_new_version;
END;
$function$;

-- 13. Batch pause: every RUNNING task of the calling user+device moves to
--     PAUSED_NEED_USER with one BATCH_PAUSED event each. Tasks of other
--     devices/states are never touched; repeated calls find no RUNNING tasks
--     and are naturally idempotent. The trigger reason and the persisted
--     canonical error code are separated: only the three canonical pause
--     reasons persist as-is, USER_REQUESTED / FAILURE_THRESHOLD fold into
--     UNKNOWN_ERROR, and the event details keep the original triggerReason.
CREATE OR REPLACE FUNCTION app.plugin_tasks_batch_pause(
    p_user_id uuid,
    p_device_id uuid,
    p_reason varchar,
    p_message varchar
)
RETURNS TABLE (paused_task_id uuid, paused_version integer)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_task record;
    v_error_code varchar(24);
BEGIN
    v_error_code := CASE WHEN p_reason IN ('LOGIN_REQUIRED', 'CAPTCHA_REQUIRED', 'RISK_CONTROL')
                         THEN p_reason ELSE 'UNKNOWN_ERROR' END;
    FOR v_task IN
        SELECT id, version, status FROM app.delivery_tasks
        WHERE user_id = p_user_id
          AND assigned_device_id = p_device_id
          AND status = 'RUNNING'
        FOR UPDATE
    LOOP
        UPDATE app.delivery_tasks
        SET status = 'PAUSED_NEED_USER',
            lease_id = NULL, leased_at = NULL, lease_expires_at = NULL,
            last_error_code = v_error_code,
            last_error_message = p_message,
            last_error_retryable = false,
            version = version + 1
        WHERE id = v_task.id AND user_id = p_user_id
          AND status = 'RUNNING'
        RETURNING version INTO v_task.version;

        IF NOT FOUND THEN
            CONTINUE;
        END IF;

        INSERT INTO app.delivery_task_events (
            user_id, delivery_task_id, event_type, from_status, to_status,
            actor_type, actor_id, event_key, details
        ) VALUES (
            p_user_id, v_task.id, 'BATCH_PAUSED', 'RUNNING', 'PAUSED_NEED_USER',
            'PLUGIN', p_device_id,
            'batch-pause:' || v_task.id::text || ':v' || v_task.version,
            jsonb_build_object('triggerReason', p_reason, 'errorCode', v_error_code)
        );

        RETURN QUERY SELECT v_task.id, v_task.version;
    END LOOP;
END;
$function$;

-- 14. Lease expiry sweep: expired RUNNING leases go back to CONFIRMED (device
--     released), or to FAILED once attempts are exhausted. The exhausted
--     branch persists the canonical UNKNOWN_ERROR; the original
--     MAX_ATTEMPTS_EXCEEDED trigger reason is preserved only in the event
--     details.
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
        WHERE status = 'RUNNING'
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
                last_error_code = 'UNKNOWN_ERROR',
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
            v_task.user_id, v_task.id, 'LEASE_EXPIRED', 'RUNNING',
            CASE WHEN v_task.attempt_count >= v_max_attempts THEN 'FAILED' ELSE 'CONFIRMED' END,
            'SYSTEM', NULL,
            'lease-expired:' || v_task.id::text || ':a' || v_task.attempt_count,
            CASE WHEN v_task.attempt_count >= v_max_attempts
                 THEN jsonb_build_object('attemptNumber', v_task.attempt_count, 'deviceReleased', true,
                                         'triggerReason', 'MAX_ATTEMPTS_EXCEEDED')
                 ELSE jsonb_build_object('attemptNumber', v_task.attempt_count, 'deviceReleased', true)
            END
        );

        RETURN QUERY SELECT v_task.id, v_task.user_id,
            CASE WHEN v_task.attempt_count >= v_max_attempts THEN 'FAILED' ELSE 'CONFIRMED' END;
    END LOOP;
    RETURN;
END;
$function$;

-- 15. Function grants: revoke PUBLIC, grant only the app role.
REVOKE ALL ON FUNCTION app.auto_create_delivery_task_on_match_apply() FROM PUBLIC;
REVOKE ALL ON FUNCTION app.revoke_plugin_device(uuid, uuid, varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.plugin_tasks_pull(uuid, uuid, uuid[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.plugin_task_start(uuid, uuid, uuid, integer, varchar, char, varchar, integer, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.plugin_task_success(uuid, uuid, uuid, uuid, varchar, integer, timestamptz, varchar, jsonb, char, varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.plugin_task_fail(uuid, uuid, uuid, uuid, varchar, integer, timestamptz, varchar, varchar, boolean, char, varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.plugin_task_pause(uuid, uuid, uuid, uuid, varchar, integer, varchar, varchar, char, varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.plugin_tasks_batch_pause(uuid, uuid, varchar, varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.recover_expired_delivery_leases(integer) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION app.revoke_plugin_device(uuid, uuid, varchar) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.plugin_tasks_pull(uuid, uuid, uuid[]) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.plugin_task_start(uuid, uuid, uuid, integer, varchar, char, varchar, integer, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.plugin_task_success(uuid, uuid, uuid, uuid, varchar, integer, timestamptz, varchar, jsonb, char, varchar) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.plugin_task_fail(uuid, uuid, uuid, uuid, varchar, integer, timestamptz, varchar, varchar, boolean, char, varchar) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.plugin_task_pause(uuid, uuid, uuid, uuid, varchar, integer, varchar, varchar, char, varchar) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.plugin_tasks_batch_pause(uuid, uuid, varchar, varchar) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.recover_expired_delivery_leases(integer) TO ${app_role};

-- 16. New audit event type for batch pause.
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
    'PLUGIN_JOB_CAPTURED'
));

COMMENT ON FUNCTION app.plugin_tasks_pull(uuid, uuid, uuid[]) IS 'Atomic pending pull: claims exactly the trusted CONFIRMED task ids supplied by the service (CONFIRMED -> PULLED_BY_PLUGIN bound to the calling device, one PULLED event per task); lost races are skipped silently.';
COMMENT ON FUNCTION app.plugin_task_start(uuid, uuid, uuid, integer, varchar, char, varchar, integer, integer) IS 'Atomic PULLED_BY_PLUGIN to RUNNING claim with lease, capability, attempt and idempotency enforcement.';
COMMENT ON FUNCTION app.plugin_task_success(uuid, uuid, uuid, uuid, varchar, integer, timestamptz, varchar, jsonb, char, varchar) IS 'RUNNING to SUCCESS; terminal, lease-holder only and replay-safe for identical payloads.';
COMMENT ON FUNCTION app.plugin_task_fail(uuid, uuid, uuid, uuid, varchar, integer, timestamptz, varchar, varchar, boolean, char, varchar) IS 'RUNNING to FAILED with the unified error codes; server decides retryability per code; lease-holder only and replay-safe.';
COMMENT ON FUNCTION app.plugin_task_pause(uuid, uuid, uuid, uuid, varchar, integer, varchar, varchar, char, varchar) IS 'RUNNING to PAUSED_NEED_USER for LOGIN_REQUIRED/CAPTCHA_REQUIRED/RISK_CONTROL, releasing the lease; the user must re-confirm.';
COMMENT ON FUNCTION app.plugin_tasks_batch_pause(uuid, uuid, varchar, varchar) IS 'Batch-pause every RUNNING task of the calling user+device to PAUSED_NEED_USER with one BATCH_PAUSED event each; naturally idempotent.';
COMMENT ON FUNCTION app.recover_expired_delivery_leases(integer) IS 'Sweep expired RUNNING leases back to CONFIRMED, or FAILED after max attempts.';
