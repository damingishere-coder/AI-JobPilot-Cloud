-- Round 5: AI job matching infrastructure.
-- Threshold columns on preferences, match table, outbox, worker claim functions and audit events.
--
-- Key design decisions:
--   Outbox stays PENDING until Redis XADD succeeds → confirm sets PUBLISHED.
--   Retry uses next_attempt_at with bounded backoff (not just lease reset).
--   Match claim supports next_attempt_at for queue rescheduling.
--   DB fallback claims one PENDING/expired-PROCESSING match for the worker.
--   FAILED force re-queue: atomically reset to PENDING + write new Outbox under same user/matching.
--   All SECURITY DEFINER functions fix search_path, disable row_security, revoke PUBLIC.

-- 1. Add threshold columns to job_preferences
ALTER TABLE app.job_preferences
    ADD COLUMN review_threshold smallint NOT NULL DEFAULT 60,
    ADD COLUMN priority_apply_threshold smallint NOT NULL DEFAULT 65,
    ADD COLUMN apply_threshold smallint NOT NULL DEFAULT 75;

ALTER TABLE app.job_preferences
    ADD CONSTRAINT job_preferences_threshold_order_check CHECK (
        review_threshold >= 0
        AND review_threshold <= priority_apply_threshold
        AND priority_apply_threshold <= apply_threshold
        AND apply_threshold <= 100
    );

-- 2. Create job_matches table with composite foreign keys
CREATE TABLE app.job_matches (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE RESTRICT,
    job_post_id uuid NOT NULL,
    resume_id uuid NOT NULL,
    preference_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    score smallint,
    decision varchar(8),
    summary text,
    strengths jsonb,
    risks jsonb,
    greeting varchar(60),
    model_provider varchar(40),
    model_name varchar(80),
    prompt_version varchar(40),
    input_fingerprint char(64) NOT NULL,
    input_tokens integer,
    output_tokens integer,
    duration_ms integer,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    lease_token uuid,
    lease_until timestamptz,
    error_code varchar(40),
    error_message varchar(500),
    started_at timestamptz,
    completed_at timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, user_id),
    CONSTRAINT job_matches_id_user_unique UNIQUE (id, user_id),
    CONSTRAINT job_matches_user_fingerprint_unique UNIQUE (user_id, input_fingerprint),
    -- Composite FKs enforce user-ownership alignment
    CONSTRAINT job_matches_job_post_fk FOREIGN KEY (job_post_id, user_id)
        REFERENCES app.job_posts (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT job_matches_resume_fk FOREIGN KEY (resume_id, user_id)
        REFERENCES app.resumes (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT job_matches_preference_fk FOREIGN KEY (preference_id, user_id)
        REFERENCES app.job_preferences (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT job_matches_status_check CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT job_matches_score_check CHECK (score IS NULL OR (score >= 0 AND score <= 100)),
    CONSTRAINT job_matches_decision_check CHECK (decision IS NULL OR decision IN ('APPLY', 'REVIEW', 'SKIP')),
    CONSTRAINT job_matches_greeting_check CHECK (greeting IS NULL OR char_length(greeting) <= 60),
    CONSTRAINT job_matches_fingerprint_check CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT job_matches_attempt_check CHECK (attempt_count >= 0),
    CONSTRAINT job_matches_version_check CHECK (version > 0),
    CONSTRAINT job_matches_input_tokens_check CHECK (input_tokens IS NULL OR input_tokens >= 0),
    CONSTRAINT job_matches_output_tokens_check CHECK (output_tokens IS NULL OR output_tokens >= 0),
    CONSTRAINT job_matches_duration_check CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT job_matches_strengths_json_check CHECK (
        strengths IS NULL OR jsonb_typeof(strengths) = 'array'
    ),
    CONSTRAINT job_matches_risks_json_check CHECK (
        risks IS NULL OR jsonb_typeof(risks) = 'array'
    ),
    CONSTRAINT job_matches_completed_at_check CHECK (
        status <> 'SUCCEEDED' OR completed_at IS NOT NULL
    )
);

CREATE INDEX job_matches_user_status_idx ON app.job_matches (user_id, status);
CREATE INDEX job_matches_user_job_idx ON app.job_matches (user_id, job_post_id);
-- Supports DB fallback: find PENDING/PROCESSING records whose next_attempt_at is due
CREATE INDEX job_matches_lease_queue_idx
    ON app.job_matches (next_attempt_at)
    WHERE status IN ('PENDING', 'PROCESSING') AND lease_until IS NULL;

-- 3. Create job_match_outbox table with composite FK
CREATE TABLE app.job_match_outbox (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE RESTRICT,
    job_match_id uuid NOT NULL,
    event_type varchar(40) NOT NULL,
    event_key varchar(128) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    payload_timestamp timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    lease_token uuid,
    lease_until timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, user_id),
    CONSTRAINT job_match_outbox_id_user_unique UNIQUE (id, user_id),
    CONSTRAINT job_match_outbox_event_key_unique UNIQUE (event_key),
    CONSTRAINT job_match_outbox_match_fk FOREIGN KEY (job_match_id, user_id)
        REFERENCES app.job_matches (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT job_match_outbox_status_check CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT job_match_outbox_event_type_check CHECK (event_type IN (
        'JOB_ANALYSIS_REQUESTED',
        'JOB_ANALYSIS_SUCCEEDED',
        'JOB_ANALYSIS_FAILED',
        'JOB_ANALYSIS_REUSED'
    )),
    CONSTRAINT job_match_outbox_attempt_check CHECK (attempt_count >= 0),
    CONSTRAINT job_match_outbox_version_check CHECK (version > 0)
);

CREATE INDEX job_match_outbox_publish_queue_idx
    ON app.job_match_outbox (next_attempt_at)
    WHERE status = 'PENDING';

-- 4. RLS for match tables
ALTER TABLE app.job_matches ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.job_match_outbox ENABLE ROW LEVEL SECURITY;

CREATE POLICY job_matches_current_user_policy
    ON app.job_matches FOR ALL TO ${app_role}
    USING (user_id = app.current_user_id())
    WITH CHECK (user_id = app.current_user_id());

CREATE POLICY job_match_outbox_current_user_policy
    ON app.job_match_outbox FOR ALL TO ${app_role}
    USING (user_id = app.current_user_id())
    WITH CHECK (user_id = app.current_user_id());

-- 5. Narrow security-definer functions

-- 5a. Claim one PENDING outbox entry for publishing (stays PENDING until XADD confirmed)
CREATE OR REPLACE FUNCTION app.claim_match_outbox_publish(p_lease_seconds integer)
RETURNS TABLE (
    outbox_id uuid,
    owner_user_id uuid,
    match_id uuid,
    event varchar,
    lease_token uuid,
    attempt_number integer
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_lease uuid;
BEGIN
    v_lease := gen_random_uuid();
    RETURN QUERY
    WITH candidate AS (
        SELECT o.id
        FROM app.job_match_outbox o
        WHERE o.status = 'PENDING'
          AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= now())
          AND (o.lease_until IS NULL OR o.lease_until < now())
        ORDER BY o.next_attempt_at ASC NULLS FIRST, o.created_at ASC
        FOR UPDATE SKIP LOCKED
        LIMIT 1
    )
    UPDATE app.job_match_outbox o
    SET lease_token = v_lease,
        lease_until = now() + (GREATEST(10, LEAST(p_lease_seconds, 1800)) * interval '1 second'),
        attempt_count = o.attempt_count + 1,
        version = o.version + 1
    FROM candidate c
    WHERE o.id = c.id
    RETURNING o.id, o.user_id, o.job_match_id, o.event_type, v_lease, o.attempt_count;
END;
$function$;

-- 5b. Confirm outbox published after successful Redis XADD — sets PUBLISHED
CREATE OR REPLACE FUNCTION app.confirm_match_outbox_published(
    p_outbox_id uuid,
    p_lease_token uuid
)
RETURNS boolean
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
    WITH updated AS (
        UPDATE app.job_match_outbox
        SET status = 'PUBLISHED',
            published_at = now(),
            lease_token = NULL,
            lease_until = NULL,
            version = version + 1
        WHERE id = p_outbox_id
          AND lease_token = p_lease_token
          AND status = 'PENDING'
        RETURNING id
    )
    SELECT count(*) > 0 FROM updated
$function$;

-- 5c. Release outbox lease on XADD failure (resets to retryable state)
CREATE OR REPLACE FUNCTION app.release_match_outbox_lease(
    p_outbox_id uuid,
    p_lease_token uuid,
    p_retry_delay_seconds integer
)
RETURNS boolean
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
    WITH updated AS (
        UPDATE app.job_match_outbox
        SET lease_token = NULL,
            lease_until = NULL,
            next_attempt_at = now() + (GREATEST(1, LEAST(p_retry_delay_seconds, 3600)) * interval '1 second'),
            version = version + 1
        WHERE id = p_outbox_id
          AND lease_token = p_lease_token
          AND status = 'PENDING'
        RETURNING id
    )
    SELECT count(*) > 0 FROM updated
$function$;

-- 5d. Claim a match for AI processing (supports next_attempt_at scheduling).
--     Enforces attempt_count < p_max_attempts in the database so the worker can
--     never claim a 4th attempt when the configuration allows only 3.
CREATE OR REPLACE FUNCTION app.claim_match_for_processing(
    p_user_id uuid,
    p_match_id uuid,
    p_lease_seconds integer,
    p_max_attempts integer
)
RETURNS TABLE (
    match_id uuid,
    job_post_id uuid,
    resume_id uuid,
    preference_id uuid,
    lease_token uuid,
    attempt_number integer
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_lease uuid;
    v_max_attempts integer;
BEGIN
    v_lease := gen_random_uuid();
    v_max_attempts := GREATEST(1, LEAST(p_max_attempts, 10));
    RETURN QUERY
    UPDATE app.job_matches m
    SET status = 'PROCESSING',
        attempt_count = m.attempt_count + 1,
        lease_token = v_lease,
        lease_until = now() + (GREATEST(30, LEAST(p_lease_seconds, 1800)) * interval '1 second'),
        next_attempt_at = NULL,
        started_at = now(),
        version = m.version + 1
    WHERE m.user_id = p_user_id
      AND m.id = p_match_id
      AND m.status IN ('PENDING', 'PROCESSING')
      AND m.attempt_count < v_max_attempts
      AND (m.next_attempt_at IS NULL OR m.next_attempt_at <= now())
      AND (m.lease_until IS NULL OR m.lease_until < now())
    RETURNING m.id, m.job_post_id, m.resume_id, m.preference_id, v_lease, m.attempt_count;
END;
$function$;

-- 5e. Complete a match (SUCCEEDED or FAILED)
CREATE OR REPLACE FUNCTION app.complete_match(
    p_user_id uuid,
    p_match_id uuid,
    p_lease_token uuid,
    p_status varchar,
    p_score smallint,
    p_decision varchar,
    p_summary text,
    p_strengths jsonb,
    p_risks jsonb,
    p_greeting varchar,
    p_model_provider varchar,
    p_model_name varchar,
    p_prompt_version varchar,
    p_input_tokens integer,
    p_output_tokens integer,
    p_duration_ms integer,
    p_error_code varchar,
    p_error_message varchar
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
BEGIN
    UPDATE app.job_matches
    SET status = p_status,
        score = p_score,
        decision = p_decision,
        summary = p_summary,
        strengths = p_strengths,
        risks = p_risks,
        greeting = p_greeting,
        model_provider = p_model_provider,
        model_name = p_model_name,
        prompt_version = p_prompt_version,
        input_tokens = p_input_tokens,
        output_tokens = p_output_tokens,
        duration_ms = p_duration_ms,
        error_code = p_error_code,
        error_message = p_error_message,
        completed_at = CASE WHEN p_status IN ('SUCCEEDED', 'FAILED') THEN now() ELSE completed_at END,
        lease_token = NULL,
        lease_until = NULL,
        next_attempt_at = NULL,
        version = version + 1
    WHERE user_id = p_user_id
      AND id = p_match_id
      AND lease_token = p_lease_token
      AND status = 'PROCESSING';
    RETURN FOUND;
END;
$function$;

-- 5f. Reset match lease to PENDING for retryable errors (resets to pending with backoff)
CREATE OR REPLACE FUNCTION app.retry_match_later(
    p_user_id uuid,
    p_match_id uuid,
    p_lease_token uuid,
    p_retry_delay_seconds integer
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
BEGIN
    UPDATE app.job_matches
    SET status = 'PENDING',
        lease_token = NULL,
        lease_until = NULL,
        next_attempt_at = now() + (GREATEST(1, LEAST(p_retry_delay_seconds, 3600)) * interval '1 second'),
        error_code = NULL,
        error_message = NULL,
        version = version + 1
    WHERE user_id = p_user_id
      AND id = p_match_id
      AND lease_token = p_lease_token
      AND status = 'PROCESSING';
    RETURN FOUND;
END;
$function$;

-- 5g. Force re-queue a FAILED match: atomically reset to PENDING +
--     write new Outbox event. Only works when same user owns the match.
--     No-op if match is not FAILED or already SUCCEEDED/PENDING/PROCESSING.
CREATE OR REPLACE FUNCTION app.force_requeue_failed_match(
    p_user_id uuid,
    p_match_id uuid
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_event_key text;
BEGIN
    -- Only FAILED matches can be force-re-queued
    UPDATE app.job_matches
    SET status = 'PENDING',
        attempt_count = 0,
        next_attempt_at = now(),
        lease_token = NULL,
        lease_until = NULL,
        error_code = NULL,
        error_message = NULL,
        started_at = NULL,
        completed_at = NULL,
        score = NULL,
        decision = NULL,
        summary = NULL,
        strengths = NULL,
        risks = NULL,
        greeting = NULL,
        model_provider = NULL,
        model_name = NULL,
        prompt_version = NULL,
        input_tokens = NULL,
        output_tokens = NULL,
        duration_ms = NULL,
        version = version + 1
    WHERE user_id = p_user_id
      AND id = p_match_id
      AND status = 'FAILED';

    IF NOT FOUND THEN
        RETURN false;
    END IF;

    v_event_key := 'match:' || p_match_id || ':force-requeue:' || gen_random_uuid()::text;

    INSERT INTO app.job_match_outbox (user_id, job_match_id, event_type, event_key, status, next_attempt_at)
    VALUES (p_user_id, p_match_id, 'JOB_ANALYSIS_REQUESTED', v_event_key, 'PENDING', now());
    RETURN true;
END;
$function$;

-- 5h. DB fallback: claim one PENDING or expired-PROCESSING match for any user
--     Used by worker when Redis stream is unavailable or PEL has gaps.
--     Enforces attempt_count < p_max_attempts in the database.
CREATE OR REPLACE FUNCTION app.claim_one_pending_match(p_lease_seconds integer, p_max_attempts integer)
RETURNS TABLE (
    owner_user_id uuid,
    match_id uuid,
    job_post_id uuid,
    resume_id uuid,
    preference_id uuid,
    lease_token uuid,
    attempt_number integer
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
DECLARE
    v_lease uuid;
    v_max_attempts integer;
BEGIN
    v_lease := gen_random_uuid();
    v_max_attempts := GREATEST(1, LEAST(p_max_attempts, 10));
    RETURN QUERY
    WITH candidate AS (
        SELECT m.id, m.user_id
        FROM app.job_matches m
        WHERE m.status IN ('PENDING', 'PROCESSING')
          AND m.attempt_count < v_max_attempts
          AND (m.next_attempt_at IS NULL OR m.next_attempt_at <= now())
          AND (m.lease_until IS NULL OR m.lease_until < now())
        ORDER BY m.next_attempt_at ASC NULLS FIRST, m.created_at ASC
        FOR UPDATE SKIP LOCKED
        LIMIT 1
    )
    UPDATE app.job_matches m
    SET status = 'PROCESSING',
        attempt_count = m.attempt_count + 1,
        lease_token = v_lease,
        lease_until = now() + (GREATEST(30, LEAST(p_lease_seconds, 1800)) * interval '1 second'),
        next_attempt_at = NULL,
        started_at = now(),
        version = m.version + 1
    FROM candidate c
    WHERE m.id = c.id AND m.user_id = c.user_id
    RETURNING m.user_id, m.id, m.job_post_id, m.resume_id,
              m.preference_id, v_lease, m.attempt_count;
END;
$function$;

-- 5i. Release a match lease cleanly (for graceful worker shutdown or force-abort)
CREATE OR REPLACE FUNCTION app.release_match_lease(
    p_user_id uuid,
    p_match_id uuid,
    p_lease_token uuid
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
BEGIN
    UPDATE app.job_matches
    SET status = 'PENDING',
        lease_token = NULL,
        lease_until = NULL,
        version = version + 1
    WHERE user_id = p_user_id
      AND id = p_match_id
      AND lease_token = p_lease_token
      AND status = 'PROCESSING';
    RETURN FOUND;
END;
$function$;

-- Revoke PUBLIC and grant only to app_role
REVOKE ALL ON FUNCTION app.claim_match_outbox_publish(integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.confirm_match_outbox_published(uuid, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.release_match_outbox_lease(uuid, uuid, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.claim_match_for_processing(uuid, uuid, integer, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.complete_match(uuid, uuid, uuid, varchar, smallint, varchar, text, jsonb, jsonb, varchar, varchar, varchar, varchar, integer, integer, integer, varchar, varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.retry_match_later(uuid, uuid, uuid, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.force_requeue_failed_match(uuid, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.claim_one_pending_match(integer, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.release_match_lease(uuid, uuid, uuid) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION app.claim_match_outbox_publish(integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.confirm_match_outbox_published(uuid, uuid) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.release_match_outbox_lease(uuid, uuid, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.claim_match_for_processing(uuid, uuid, integer, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.complete_match(uuid, uuid, uuid, varchar, smallint, varchar, text, jsonb, jsonb, varchar, varchar, varchar, varchar, integer, integer, integer, varchar, varchar) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.retry_match_later(uuid, uuid, uuid, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.force_requeue_failed_match(uuid, uuid) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.claim_one_pending_match(integer, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.release_match_lease(uuid, uuid, uuid) TO ${app_role};

-- 6. Touch trigger for match tables
CREATE TRIGGER job_matches_touch_updated_at
    BEFORE UPDATE ON app.job_matches
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

CREATE TRIGGER job_match_outbox_touch_updated_at
    BEFORE UPDATE ON app.job_match_outbox
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- 7. Add new audit event types
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
    'JOB_ANALYSIS_REUSED'
));

COMMENT ON TABLE app.job_matches IS 'AI-scored job match results keyed by input fingerprint for deduplication. Composite FKs enforce cross-table user ownership.';
COMMENT ON TABLE app.job_match_outbox IS 'Reliable event publishing for match lifecycle; publisher claims PENDING, XADDs to Redis, then confirms PUBLISHED.';
COMMENT ON FUNCTION app.claim_match_outbox_publish(integer) IS 'Claim a PENDING outbox entry; stays PENDING until XADD confirmed. Bounded lease 10-1800s. Returns the new attempt number.';
COMMENT ON FUNCTION app.confirm_match_outbox_published(uuid, uuid) IS 'Mark outbox PUBLISHED after successful Redis XADD.';
COMMENT ON FUNCTION app.release_match_outbox_lease(uuid, uuid, integer) IS 'Release outbox lease on failure, scheduling retry.';
COMMENT ON FUNCTION app.claim_match_for_processing(uuid, uuid, integer, integer) IS 'Claim one match by user+id with next_attempt_at support and max-attempts enforcement.';
COMMENT ON FUNCTION app.complete_match(uuid, uuid, uuid, varchar, smallint, varchar, text, jsonb, jsonb, varchar, varchar, varchar, varchar, integer, integer, integer, varchar, varchar) IS 'Complete match with results or error. Lease-protected, only from PROCESSING.';
COMMENT ON FUNCTION app.retry_match_later(uuid, uuid, uuid, integer) IS 'Reset match to PENDING with bounded backoff next_attempt_at.';
COMMENT ON FUNCTION app.force_requeue_failed_match(uuid, uuid) IS 'Atomically reset FAILED match to PENDING and write new Outbox.';
COMMENT ON FUNCTION app.claim_one_pending_match(integer, integer) IS 'DB fallback: claim any PENDING/expired-PROCESSING match for worker processing with max-attempts enforcement.';
COMMENT ON FUNCTION app.release_match_lease(uuid, uuid, uuid) IS 'Release match lease, returning to PENDING.';
