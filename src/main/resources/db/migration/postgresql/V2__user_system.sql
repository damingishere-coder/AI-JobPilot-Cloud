-- Round 3: Cloud user identities, profiles and security audit trail.

CREATE TABLE app.users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email citext NOT NULL,
    password_hash varchar(255) NOT NULL,
    role varchar(24) NOT NULL DEFAULT 'USER',
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    email_verified_at timestamptz,
    last_login_at timestamptz,
    failed_login_count integer NOT NULL DEFAULT 0,
    locked_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT users_email_unique UNIQUE (email),
    CONSTRAINT users_email_not_blank CHECK (length(trim(email::text)) > 0),
    CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED', 'PENDING')),
    CONSTRAINT users_failed_login_count_check CHECK (failed_login_count >= 0),
    CONSTRAINT users_locked_state_check CHECK (
        (status = 'LOCKED' AND locked_until IS NOT NULL)
        OR (status <> 'LOCKED' AND locked_until IS NULL)
    )
);

CREATE INDEX users_status_idx ON app.users (status);
CREATE INDEX users_deleted_at_idx ON app.users (deleted_at) WHERE deleted_at IS NOT NULL;

CREATE TABLE app.user_profiles (
    user_id uuid PRIMARY KEY REFERENCES app.users (id) ON DELETE RESTRICT,
    display_name varchar(80),
    city varchar(80),
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai',
    locale varchar(16) NOT NULL DEFAULT 'zh-CN',
    avatar_storage_key varchar(512),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT user_profiles_display_name_check CHECK (
        display_name IS NULL OR length(trim(display_name)) > 0
    ),
    CONSTRAINT user_profiles_timezone_check CHECK (length(trim(timezone)) > 0),
    CONSTRAINT user_profiles_locale_check CHECK (length(trim(locale)) > 0)
);

CREATE TABLE app.audit_logs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id uuid REFERENCES app.users (id) ON DELETE SET NULL,
    actor_type varchar(24) NOT NULL,
    actor_id uuid,
    action varchar(80) NOT NULL,
    target_type varchar(60),
    target_id uuid,
    result varchar(16) NOT NULL,
    request_id varchar(64),
    ip_hash char(64),
    user_agent_summary varchar(255),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT audit_logs_actor_type_check CHECK (actor_type IN ('USER', 'PLUGIN', 'ADMIN', 'SYSTEM')),
    CONSTRAINT audit_logs_result_check CHECK (result IN ('SUCCESS', 'DENIED', 'FAILED')),
    CONSTRAINT audit_logs_action_not_blank CHECK (length(trim(action)) > 0),
    CONSTRAINT audit_logs_details_object_check CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX audit_logs_user_created_idx ON app.audit_logs (user_id, created_at DESC);
CREATE INDEX audit_logs_actor_created_idx ON app.audit_logs (actor_type, actor_id, created_at DESC);
CREATE INDEX audit_logs_action_created_idx ON app.audit_logs (action, created_at DESC);

CREATE OR REPLACE FUNCTION app.touch_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$function$;

CREATE TRIGGER users_touch_updated_at
BEFORE UPDATE ON app.users
FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

CREATE TRIGGER user_profiles_touch_updated_at
BEFORE UPDATE ON app.user_profiles
FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

CREATE OR REPLACE FUNCTION app.current_user_id()
RETURNS uuid
LANGUAGE sql
STABLE
AS $function$
    SELECT NULLIF(current_setting('app.current_user_id', true), '')::uuid
$function$;

ALTER TABLE app.user_profiles ENABLE ROW LEVEL SECURITY;

CREATE POLICY user_profiles_current_user_policy
ON app.user_profiles
FOR ALL
TO ${app_role}
USING (user_id = app.current_user_id())
WITH CHECK (user_id = app.current_user_id());

CREATE OR REPLACE FUNCTION app.append_audit_log(
    p_user_id uuid,
    p_actor_type varchar,
    p_actor_id uuid,
    p_action varchar,
    p_target_type varchar,
    p_target_id uuid,
    p_result varchar,
    p_request_id varchar,
    p_ip_hash char(64),
    p_user_agent_summary varchar,
    p_details jsonb
)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
AS $function$
DECLARE
    inserted_id bigint;
BEGIN
    INSERT INTO app.audit_logs (
        user_id, actor_type, actor_id, action, target_type, target_id,
        result, request_id, ip_hash, user_agent_summary, details
    ) VALUES (
        p_user_id, p_actor_type, p_actor_id, p_action, p_target_type, p_target_id,
        p_result, p_request_id, p_ip_hash, p_user_agent_summary, COALESCE(p_details, '{}'::jsonb)
    )
    RETURNING id INTO inserted_id;
    RETURN inserted_id;
END;
$function$;

REVOKE ALL ON TABLE app.audit_logs FROM ${app_role};
REVOKE ALL ON SEQUENCE app.audit_logs_id_seq FROM ${app_role};
REVOKE ALL ON FUNCTION app.append_audit_log(
    uuid, varchar, uuid, varchar, varchar, uuid, varchar, varchar, char, varchar, jsonb
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.append_audit_log(
    uuid, varchar, uuid, varchar, varchar, uuid, varchar, varchar, char, varchar, jsonb
) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.current_user_id() TO ${app_role};

COMMENT ON TABLE app.users IS 'Cloud account identities; passwords are stored only as one-way hashes.';
COMMENT ON TABLE app.user_profiles IS 'User-scoped profile data protected by PostgreSQL row-level security.';
COMMENT ON TABLE app.audit_logs IS 'Append-only security audit events with sensitive fields removed or keyed-hashed.';
