-- Limited beta accounts: invitation-only registration, one-shot email tokens,
-- versioned consent evidence, and replay-safe account deletion.

ALTER TABLE app.users DROP CONSTRAINT users_status_check;
ALTER TABLE app.users ADD CONSTRAINT users_status_check CHECK (status IN (
    'ACTIVE', 'LOCKED', 'DISABLED', 'PENDING', 'DELETION_PENDING', 'DELETED'
));

CREATE TABLE app.beta_invites (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code_hash char(64) NOT NULL UNIQUE,
    intended_email citext,
    expires_at timestamptz NOT NULL,
    consumed_by uuid,
    consumed_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT beta_invites_hash_check CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT beta_invites_consumed_check CHECK (
        (consumed_by IS NULL AND consumed_at IS NULL)
        OR (consumed_by IS NOT NULL AND consumed_at IS NOT NULL)
    )
);

CREATE INDEX beta_invites_active_idx ON app.beta_invites (expires_at)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE TABLE app.auth_email_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE RESTRICT,
    purpose varchar(32) NOT NULL,
    token_hash char(64) NOT NULL UNIQUE,
    email citext NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT auth_email_tokens_purpose_check CHECK (
        purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')
    ),
    CONSTRAINT auth_email_tokens_hash_check CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX auth_email_tokens_user_purpose_idx
    ON app.auth_email_tokens (user_id, purpose, created_at DESC);
CREATE INDEX auth_email_tokens_active_idx
    ON app.auth_email_tokens (expires_at) WHERE used_at IS NULL;

CREATE TABLE app.user_consents (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE RESTRICT,
    document_type varchar(32) NOT NULL,
    document_version varchar(80) NOT NULL,
    accepted_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT user_consents_document_type_check CHECK (
        document_type IN ('TERMS', 'PRIVACY', 'AI_DISCLOSURE')
    ),
    CONSTRAINT user_consents_version_check CHECK (length(trim(document_version)) > 0),
    CONSTRAINT user_consents_unique UNIQUE (user_id, document_type, document_version)
);

CREATE INDEX user_consents_user_idx ON app.user_consents (user_id, accepted_at DESC);

CREATE TABLE app.account_deletion_requests (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    idempotency_key_hash char(64) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    requested_at timestamptz NOT NULL DEFAULT now(),
    lease_until timestamptz,
    attempt_count integer NOT NULL DEFAULT 0,
    last_error_code varchar(80),
    completed_at timestamptz,
    CONSTRAINT account_deletion_requests_hash_check CHECK (
        idempotency_key_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT account_deletion_requests_status_check CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT account_deletion_requests_attempt_check CHECK (attempt_count >= 0),
    CONSTRAINT account_deletion_requests_complete_check CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (status <> 'COMPLETED' AND completed_at IS NULL)
    ),
    CONSTRAINT account_deletion_requests_idempotency_unique
        UNIQUE (user_id, idempotency_key_hash)
);

CREATE INDEX account_deletion_requests_claim_idx
    ON app.account_deletion_requests (requested_at)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE TABLE app.account_deletion_tombstones (
    account_id uuid PRIMARY KEY,
    deletion_request_id uuid NOT NULL UNIQUE,
    completed_at timestamptz NOT NULL,
    backup_expires_at timestamptz NOT NULL,
    CONSTRAINT account_deletion_tombstones_expiry_check CHECK (
        backup_expires_at > completed_at
    )
);

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
    'AUTH_EMAIL_VERIFICATION_SENT',
    'AUTH_EMAIL_VERIFIED',
    'AUTH_PASSWORD_RESET_REQUESTED',
    'AUTH_PASSWORD_RESET_COMPLETED',
    'AUTH_ACCOUNT_DELETION_REQUESTED',
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

CREATE OR REPLACE FUNCTION app.consume_beta_invite(
    p_code_hash char(64),
    p_user_id uuid,
    p_email citext,
    p_max_users integer
)
RETURNS varchar
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
AS $function$
DECLARE
    affected integer;
    active_users bigint;
BEGIN
    IF p_max_users < 1 OR p_max_users > 1000 THEN
        RETURN 'LIMIT_INVALID';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtext('ai-jobpilot-beta-registration'));
    SELECT count(*) INTO active_users
      FROM app.users
     WHERE role = 'USER' AND deleted_at IS NULL AND status <> 'DELETED';
    IF active_users >= p_max_users THEN
        RETURN 'LIMIT_REACHED';
    END IF;

    UPDATE app.beta_invites
       SET consumed_by = p_user_id,
           consumed_at = now()
     WHERE code_hash = p_code_hash
       AND consumed_at IS NULL
       AND revoked_at IS NULL
       AND expires_at > now()
       AND (intended_email IS NULL OR intended_email = p_email);
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN
        RETURN 'INVITE_INVALID';
    END IF;
    RETURN 'OK';
END;
$function$;

CREATE OR REPLACE FUNCTION app.record_user_consent(
    p_user_id uuid,
    p_document_type varchar,
    p_document_version varchar
)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, app
AS $function$
    INSERT INTO app.user_consents (user_id, document_type, document_version)
    VALUES (p_user_id, p_document_type, p_document_version)
    ON CONFLICT (user_id, document_type, document_version) DO NOTHING
$function$;

CREATE OR REPLACE FUNCTION app.create_auth_email_token(
    p_user_id uuid,
    p_purpose varchar,
    p_token_hash char(64),
    p_email citext,
    p_expires_at timestamptz
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
AS $function$
BEGIN
    IF p_purpose NOT IN ('VERIFY_EMAIL', 'RESET_PASSWORD') OR p_expires_at <= now() THEN
        RAISE EXCEPTION 'invalid email token input';
    END IF;
    UPDATE app.auth_email_tokens
       SET used_at = now()
     WHERE user_id = p_user_id AND purpose = p_purpose AND used_at IS NULL;
    INSERT INTO app.auth_email_tokens (user_id, purpose, token_hash, email, expires_at)
    VALUES (p_user_id, p_purpose, p_token_hash, p_email, p_expires_at);
END;
$function$;

CREATE OR REPLACE FUNCTION app.consume_auth_email_token(
    p_token_hash char(64),
    p_purpose varchar
)
RETURNS TABLE (token_user_id uuid, token_email text)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
AS $function$
BEGIN
    RETURN QUERY
    UPDATE app.auth_email_tokens
       SET used_at = now()
     WHERE token_hash = p_token_hash
       AND purpose = p_purpose
       AND used_at IS NULL
       AND expires_at > now()
    RETURNING user_id, email::text;
END;
$function$;

CREATE OR REPLACE FUNCTION app.request_account_deletion(
    p_user_id uuid,
    p_request_id uuid,
    p_idempotency_key_hash char(64)
)
RETURNS TABLE (deletion_request_id uuid, deletion_status varchar, deletion_requested_at timestamptz)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
AS $function$
DECLARE
    current_status varchar;
BEGIN
    RETURN QUERY
    SELECT id, status, requested_at
      FROM app.account_deletion_requests
     WHERE user_id = p_user_id AND idempotency_key_hash = p_idempotency_key_hash;
    IF FOUND THEN
        RETURN;
    END IF;

    SELECT status INTO current_status FROM app.users WHERE id = p_user_id FOR UPDATE;
    IF current_status IS NULL THEN
        RAISE EXCEPTION 'account not found';
    END IF;
    IF current_status = 'DELETION_PENDING' THEN
        RETURN QUERY
        SELECT id, status, requested_at
          FROM app.account_deletion_requests
         WHERE user_id = p_user_id AND status IN ('PENDING', 'PROCESSING')
         ORDER BY requested_at DESC LIMIT 1;
        RETURN;
    END IF;
    IF current_status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'account is not active';
    END IF;

    UPDATE app.users
       SET status = 'DELETION_PENDING', deleted_at = now(), locked_until = NULL
     WHERE id = p_user_id;

    UPDATE app.plugin_tokens
       SET status = 'REVOKED', revoked_at = now()
     WHERE user_id = p_user_id AND status = 'ACTIVE';
    UPDATE app.plugin_bind_codes
       SET status = 'SUPERSEDED', consumed_at = COALESCE(consumed_at, now())
     WHERE user_id = p_user_id AND status = 'ACTIVE';
    UPDATE app.plugin_devices
       SET status = 'REVOKED', revoked_at = now(), revoke_reason = 'ACCOUNT_DELETION'
     WHERE user_id = p_user_id AND status = 'ACTIVE';
    UPDATE app.delivery_tasks
       SET status = 'SKIPPED', assigned_device_id = NULL,
           lease_id = NULL, leased_at = NULL, lease_expires_at = NULL,
           execution_id = NULL, confirmed_at = NULL, confirmed_by = NULL,
           last_error_code = NULL, last_error_message = NULL,
           finished_at = now(), version = version + 1
     WHERE user_id = p_user_id
       AND status IN ('WAITING_CONFIRM', 'CONFIRMED', 'PULLED_BY_PLUGIN', 'RUNNING', 'PAUSED_NEED_USER');

    INSERT INTO app.account_deletion_requests (
        id, user_id, idempotency_key_hash, status
    ) VALUES (
        p_request_id, p_user_id, p_idempotency_key_hash, 'PENDING'
    );

    RETURN QUERY
    SELECT id, status, requested_at
      FROM app.account_deletion_requests WHERE id = p_request_id;
END;
$function$;

CREATE OR REPLACE FUNCTION app.find_account_deletion(
    p_user_id uuid,
    p_idempotency_key_hash char(64)
)
RETURNS TABLE (deletion_request_id uuid, deletion_status varchar, deletion_requested_at timestamptz)
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, app
AS $function$
    SELECT id, status, requested_at
      FROM app.account_deletion_requests
     WHERE user_id = p_user_id AND idempotency_key_hash = p_idempotency_key_hash
$function$;

CREATE OR REPLACE FUNCTION app.claim_account_deletion(p_lease_seconds integer)
RETURNS TABLE (deletion_request_id uuid, deletion_user_id uuid)
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, app
AS $function$
    WITH candidate AS (
        SELECT id
          FROM app.account_deletion_requests
         WHERE status = 'PENDING'
            OR (status = 'PROCESSING' AND lease_until < now())
         ORDER BY requested_at
         FOR UPDATE SKIP LOCKED
         LIMIT 1
    )
    UPDATE app.account_deletion_requests request
       SET status = 'PROCESSING',
           lease_until = now() + make_interval(secs => LEAST(GREATEST(p_lease_seconds, 30), 3600)),
           attempt_count = attempt_count + 1,
           last_error_code = NULL
      FROM candidate
     WHERE request.id = candidate.id
    RETURNING request.id, request.user_id
$function$;

CREATE OR REPLACE FUNCTION app.retry_account_deletion(
    p_request_id uuid,
    p_error_code varchar,
    p_max_attempts integer
)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, app
AS $function$
    UPDATE app.account_deletion_requests
       SET status = CASE WHEN attempt_count >= p_max_attempts THEN 'FAILED' ELSE 'PENDING' END,
           lease_until = NULL,
           last_error_code = left(COALESCE(NULLIF(trim(p_error_code), ''), 'UNKNOWN_ERROR'), 80)
     WHERE id = p_request_id AND status = 'PROCESSING'
$function$;

CREATE OR REPLACE FUNCTION app.purge_account_data(p_user_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
AS $function$
BEGIN
    DELETE FROM app.delivery_task_events WHERE user_id = p_user_id;
    DELETE FROM app.delivery_tasks WHERE user_id = p_user_id;
    DELETE FROM app.job_match_outbox WHERE user_id = p_user_id;
    DELETE FROM app.job_matches WHERE user_id = p_user_id;
    DELETE FROM app.plugin_tokens WHERE user_id = p_user_id;
    DELETE FROM app.plugin_bind_codes WHERE user_id = p_user_id;
    DELETE FROM app.plugin_devices WHERE user_id = p_user_id;
    DELETE FROM app.quota_usage_logs WHERE user_id = p_user_id;
    DELETE FROM app.user_quotas WHERE user_id = p_user_id;
    DELETE FROM app.subscriptions WHERE user_id = p_user_id;
    DELETE FROM app.resumes WHERE user_id = p_user_id;
    DELETE FROM app.job_preferences WHERE user_id = p_user_id;
    DELETE FROM app.job_posts WHERE user_id = p_user_id;
    DELETE FROM app.user_consents WHERE user_id = p_user_id;
    DELETE FROM app.auth_email_tokens WHERE user_id = p_user_id;
    DELETE FROM app.user_profiles WHERE user_id = p_user_id;
    DELETE FROM app.beta_invites WHERE consumed_by = p_user_id;

    UPDATE app.audit_logs
       SET user_id = NULL,
           actor_id = CASE WHEN actor_id = p_user_id THEN NULL ELSE actor_id END,
           target_id = CASE WHEN target_id = p_user_id THEN NULL ELSE target_id END,
           details = '{}'::jsonb
     WHERE user_id = p_user_id OR actor_id = p_user_id OR target_id = p_user_id;

    DELETE FROM app.users WHERE id = p_user_id;
END;
$function$;

CREATE OR REPLACE FUNCTION app.replay_account_deletion(
    p_account_id uuid,
    p_deletion_request_id uuid,
    p_completed_at timestamptz,
    p_backup_expires_at timestamptz
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
AS $function$
BEGIN
    IF p_completed_at IS NULL OR p_backup_expires_at <= p_completed_at THEN
        RAISE EXCEPTION 'invalid deletion tombstone';
    END IF;
    PERFORM app.purge_account_data(p_account_id);
    DELETE FROM app.account_deletion_requests WHERE user_id = p_account_id;
    INSERT INTO app.account_deletion_tombstones (
        account_id, deletion_request_id, completed_at, backup_expires_at
    ) VALUES (
        p_account_id, p_deletion_request_id, p_completed_at, p_backup_expires_at
    )
    ON CONFLICT (account_id) DO UPDATE SET
        deletion_request_id = EXCLUDED.deletion_request_id,
        completed_at = GREATEST(app.account_deletion_tombstones.completed_at, EXCLUDED.completed_at),
        backup_expires_at = GREATEST(app.account_deletion_tombstones.backup_expires_at, EXCLUDED.backup_expires_at);
END;
$function$;

CREATE OR REPLACE FUNCTION app.complete_account_deletion(
    p_request_id uuid,
    p_backup_expires_at timestamptz
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
AS $function$
DECLARE
    target_user_id uuid;
BEGIN
    SELECT user_id INTO target_user_id
      FROM app.account_deletion_requests
     WHERE id = p_request_id AND status = 'PROCESSING'
     FOR UPDATE;
    IF target_user_id IS NULL THEN
        RETURN false;
    END IF;

    PERFORM app.purge_account_data(target_user_id);

    INSERT INTO app.account_deletion_tombstones (
        account_id, deletion_request_id, completed_at, backup_expires_at
    ) VALUES (
        target_user_id, p_request_id, now(), p_backup_expires_at
    )
    ON CONFLICT (account_id) DO UPDATE SET
        deletion_request_id = EXCLUDED.deletion_request_id,
        completed_at = EXCLUDED.completed_at,
        backup_expires_at = EXCLUDED.backup_expires_at;

    DELETE FROM app.account_deletion_requests WHERE id = p_request_id;
    RETURN true;
END;
$function$;

REVOKE ALL ON TABLE app.beta_invites FROM ${app_role};
REVOKE ALL ON TABLE app.auth_email_tokens FROM ${app_role};
REVOKE ALL ON TABLE app.user_consents FROM ${app_role};
REVOKE ALL ON SEQUENCE app.user_consents_id_seq FROM ${app_role};
REVOKE ALL ON TABLE app.account_deletion_requests FROM ${app_role};
REVOKE ALL ON TABLE app.account_deletion_tombstones FROM ${app_role};

REVOKE ALL ON FUNCTION app.consume_beta_invite(char, uuid, citext, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.record_user_consent(uuid, varchar, varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.create_auth_email_token(uuid, varchar, char, citext, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.consume_auth_email_token(char, varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.request_account_deletion(uuid, uuid, char) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.find_account_deletion(uuid, char) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.claim_account_deletion(integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.retry_account_deletion(uuid, varchar, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.purge_account_data(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.replay_account_deletion(uuid, uuid, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.complete_account_deletion(uuid, timestamptz) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION app.consume_beta_invite(char, uuid, citext, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.record_user_consent(uuid, varchar, varchar) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.create_auth_email_token(uuid, varchar, char, citext, timestamptz) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.consume_auth_email_token(char, varchar) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.request_account_deletion(uuid, uuid, char) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.find_account_deletion(uuid, char) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.claim_account_deletion(integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.retry_account_deletion(uuid, varchar, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.complete_account_deletion(uuid, timestamptz) TO ${app_role};

COMMENT ON TABLE app.beta_invites IS 'One-shot invitation hashes for the limited beta; plaintext codes are never stored.';
COMMENT ON TABLE app.auth_email_tokens IS 'One-shot hashed email verification and password reset tokens.';
COMMENT ON TABLE app.user_consents IS 'Versioned evidence of terms, privacy and third-party AI disclosure acceptance.';
COMMENT ON TABLE app.account_deletion_tombstones IS 'Pseudonymous deletion ledger replayed before a restored backup reopens.';
