-- Round 4: user-scoped resumes, versioned preferences and the read-only job pool.

CREATE TABLE app.resumes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE RESTRICT,
    original_filename varchar(255) NOT NULL,
    storage_key varchar(512) NOT NULL,
    content_type varchar(100) NOT NULL,
    file_size bigint NOT NULL,
    sha256 char(64) NOT NULL,
    upload_idempotency_key_hash char(64) NOT NULL,
    parse_status varchar(24) NOT NULL DEFAULT 'UPLOADED',
    parse_message varchar(500),
    extracted_text_ciphertext bytea,
    extracted_text_nonce bytea,
    encryption_key_id varchar(64) NOT NULL,
    text_version integer NOT NULL DEFAULT 1,
    is_current boolean NOT NULL DEFAULT false,
    version integer NOT NULL DEFAULT 1,
    parse_attempts integer NOT NULL DEFAULT 0,
    parse_lease_token uuid,
    parse_lease_until timestamptz,
    purge_attempts integer NOT NULL DEFAULT 0,
    purge_lease_token uuid,
    purge_lease_until timestamptz,
    parsed_at timestamptz,
    deleted_at timestamptz,
    purged_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT resumes_id_user_unique UNIQUE (id, user_id),
    CONSTRAINT resumes_filename_not_blank CHECK (length(trim(original_filename)) > 0),
    CONSTRAINT resumes_content_type_check CHECK (content_type IN (
        'application/pdf',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'text/plain'
    )),
    CONSTRAINT resumes_file_size_check CHECK (file_size > 0 AND file_size <= 10485760),
    CONSTRAINT resumes_sha256_check CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT resumes_upload_key_check CHECK (upload_idempotency_key_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT resumes_parse_status_check CHECK (parse_status IN ('UPLOADED', 'PARSING', 'PARSED', 'FAILED')),
    CONSTRAINT resumes_text_version_check CHECK (text_version > 0),
    CONSTRAINT resumes_version_check CHECK (version > 0),
    CONSTRAINT resumes_attempts_check CHECK (parse_attempts >= 0 AND purge_attempts >= 0),
    CONSTRAINT resumes_text_encryption_check CHECK (
        (extracted_text_ciphertext IS NULL AND extracted_text_nonce IS NULL)
        OR (extracted_text_ciphertext IS NOT NULL AND octet_length(extracted_text_nonce) = 12)
    )
);

CREATE UNIQUE INDEX resumes_current_user_unique
    ON app.resumes (user_id)
    WHERE is_current AND deleted_at IS NULL;
CREATE INDEX resumes_user_created_idx ON app.resumes (user_id, created_at DESC);
CREATE INDEX resumes_user_sha256_idx ON app.resumes (user_id, sha256);
CREATE UNIQUE INDEX resumes_user_upload_key_unique
    ON app.resumes (user_id, upload_idempotency_key_hash);
CREATE INDEX resumes_parse_queue_idx
    ON app.resumes (created_at)
    WHERE deleted_at IS NULL AND parse_status IN ('UPLOADED', 'PARSING');
CREATE INDEX resumes_purge_queue_idx
    ON app.resumes (deleted_at)
    WHERE deleted_at IS NOT NULL AND purged_at IS NULL;

CREATE TABLE app.job_preferences (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE RESTRICT,
    version integer NOT NULL,
    is_current boolean NOT NULL DEFAULT true,
    target_titles jsonb NOT NULL DEFAULT '[]'::jsonb,
    cities jsonb NOT NULL DEFAULT '[]'::jsonb,
    salary_min_k numeric(8,2),
    salary_max_k numeric(8,2),
    experience_levels jsonb NOT NULL DEFAULT '[]'::jsonb,
    degree_levels jsonb NOT NULL DEFAULT '[]'::jsonb,
    industries jsonb NOT NULL DEFAULT '[]'::jsonb,
    company_scales jsonb NOT NULL DEFAULT '[]'::jsonb,
    preferred_companies jsonb NOT NULL DEFAULT '[]'::jsonb,
    excluded_companies jsonb NOT NULL DEFAULT '[]'::jsonb,
    excluded_keywords jsonb NOT NULL DEFAULT '[]'::jsonb,
    extra_filters jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT job_preferences_id_user_unique UNIQUE (id, user_id),
    CONSTRAINT job_preferences_user_version_unique UNIQUE (user_id, version),
    CONSTRAINT job_preferences_version_check CHECK (version > 0),
    CONSTRAINT job_preferences_salary_check CHECK (
        (salary_min_k IS NULL OR (salary_min_k >= 0 AND salary_min_k <= 1000))
        AND (salary_max_k IS NULL OR (salary_max_k >= 0 AND salary_max_k <= 1000))
        AND (salary_min_k IS NULL OR salary_max_k IS NULL OR salary_min_k <= salary_max_k)
    ),
    CONSTRAINT job_preferences_json_check CHECK (
        jsonb_typeof(target_titles) = 'array'
        AND jsonb_typeof(cities) = 'array'
        AND jsonb_typeof(experience_levels) = 'array'
        AND jsonb_typeof(degree_levels) = 'array'
        AND jsonb_typeof(industries) = 'array'
        AND jsonb_typeof(company_scales) = 'array'
        AND jsonb_typeof(preferred_companies) = 'array'
        AND jsonb_typeof(excluded_companies) = 'array'
        AND jsonb_typeof(excluded_keywords) = 'array'
        AND jsonb_typeof(extra_filters) = 'object'
    )
);

CREATE UNIQUE INDEX job_preferences_current_user_unique
    ON app.job_preferences (user_id)
    WHERE is_current;
CREATE INDEX job_preferences_user_created_idx
    ON app.job_preferences (user_id, created_at DESC);

CREATE TABLE app.job_posts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app.users (id) ON DELETE RESTRICT,
    platform varchar(32) NOT NULL,
    external_job_id varchar(160),
    fingerprint char(64) NOT NULL,
    title varchar(240) NOT NULL,
    company_name varchar(240) NOT NULL,
    salary_text varchar(120),
    salary_min_k numeric(8,2),
    salary_max_k numeric(8,2),
    salary_months smallint,
    location varchar(160),
    experience varchar(120),
    degree varchar(120),
    description text,
    job_url text NOT NULL,
    company_info jsonb NOT NULL DEFAULT '{}'::jsonb,
    skills jsonb NOT NULL DEFAULT '[]'::jsonb,
    welfare jsonb NOT NULL DEFAULT '[]'::jsonb,
    source_captured_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT job_posts_id_user_unique UNIQUE (id, user_id),
    CONSTRAINT job_posts_platform_check CHECK (platform IN ('BOSS', 'ZHILIAN', 'LIEPIN', 'JOB51')),
    CONSTRAINT job_posts_fingerprint_check CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT job_posts_title_not_blank CHECK (length(trim(title)) > 0),
    CONSTRAINT job_posts_company_not_blank CHECK (length(trim(company_name)) > 0),
    CONSTRAINT job_posts_url_not_blank CHECK (length(trim(job_url)) > 0),
    CONSTRAINT job_posts_status_check CHECK (status IN ('ACTIVE', 'EXPIRED', 'REMOVED')),
    CONSTRAINT job_posts_salary_check CHECK (
        (salary_min_k IS NULL OR salary_min_k >= 0)
        AND (salary_max_k IS NULL OR salary_max_k >= 0)
        AND (salary_min_k IS NULL OR salary_max_k IS NULL OR salary_min_k <= salary_max_k)
        AND (salary_months IS NULL OR salary_months BETWEEN 1 AND 36)
    ),
    CONSTRAINT job_posts_json_check CHECK (
        jsonb_typeof(company_info) = 'object'
        AND jsonb_typeof(skills) = 'array'
        AND jsonb_typeof(welfare) = 'array'
    )
);

CREATE UNIQUE INDEX job_posts_user_platform_fingerprint_unique
    ON app.job_posts (user_id, platform, fingerprint);
CREATE UNIQUE INDEX job_posts_user_platform_external_unique
    ON app.job_posts (user_id, platform, external_job_id)
    WHERE external_job_id IS NOT NULL;
CREATE INDEX job_posts_user_created_idx ON app.job_posts (user_id, created_at DESC);
CREATE INDEX job_posts_user_platform_status_seen_idx
    ON app.job_posts (user_id, platform, status, last_seen_at DESC);

CREATE TRIGGER resumes_touch_updated_at
BEFORE UPDATE ON app.resumes
FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

CREATE TRIGGER job_preferences_touch_updated_at
BEFORE UPDATE ON app.job_preferences
FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

CREATE TRIGGER job_posts_touch_updated_at
BEFORE UPDATE ON app.job_posts
FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

ALTER TABLE app.resumes ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.job_preferences ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.job_posts ENABLE ROW LEVEL SECURITY;

CREATE POLICY resumes_current_user_policy
ON app.resumes FOR ALL TO ${app_role}
USING (user_id = app.current_user_id())
WITH CHECK (user_id = app.current_user_id());

CREATE POLICY job_preferences_current_user_policy
ON app.job_preferences FOR ALL TO ${app_role}
USING (user_id = app.current_user_id())
WITH CHECK (user_id = app.current_user_id());

CREATE POLICY job_posts_current_user_policy
ON app.job_posts FOR ALL TO ${app_role}
USING (user_id = app.current_user_id())
WITH CHECK (user_id = app.current_user_id());

-- Worker claims work through a narrow function because it has no Web Session from
-- which to establish the RLS context. Parsing happens outside this short transaction.
CREATE OR REPLACE FUNCTION app.claim_resume_parse_job(p_lease_seconds integer)
RETURNS TABLE (
    resume_id uuid,
    owner_user_id uuid,
    object_storage_key varchar,
    object_content_type varchar,
    object_encryption_key_id varchar,
    text_version integer,
    lease_token uuid,
    attempt_number integer
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
BEGIN
    UPDATE app.resumes
    SET parse_status = 'FAILED',
        parse_message = '解析多次失败，请重新上传简历',
        parse_lease_token = NULL,
        parse_lease_until = NULL,
        version = version + 1
    WHERE deleted_at IS NULL
      AND parse_status = 'PARSING'
      AND parse_lease_until < now()
      AND parse_attempts >= 3;

    RETURN QUERY
    WITH candidate AS (
        SELECT r.id
        FROM app.resumes r
        WHERE r.deleted_at IS NULL
          AND r.parse_attempts < 3
          AND (
              r.parse_status = 'UPLOADED'
              OR (r.parse_status = 'PARSING' AND r.parse_lease_until < now())
          )
        ORDER BY r.created_at
        FOR UPDATE SKIP LOCKED
        LIMIT 1
    )
    UPDATE app.resumes r
    SET parse_status = 'PARSING',
        parse_message = NULL,
        parse_attempts = r.parse_attempts + 1,
        parse_lease_token = gen_random_uuid(),
        parse_lease_until = now() + (greatest(30, least(p_lease_seconds, 1800)) * interval '1 second'),
        version = r.version + 1
    FROM candidate c
    WHERE r.id = c.id
    RETURNING r.id, r.user_id, r.storage_key, r.content_type,
              r.encryption_key_id, r.text_version, r.parse_lease_token, r.parse_attempts;
END;
$function$;

CREATE OR REPLACE FUNCTION app.claim_resume_purge_job(p_lease_seconds integer)
RETURNS TABLE (
    resume_id uuid,
    owner_user_id uuid,
    object_storage_key varchar,
    lease_token uuid
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, app
SET row_security = off
AS $function$
    WITH candidate AS (
        SELECT r.id
        FROM app.resumes r
        WHERE r.deleted_at IS NOT NULL
          AND r.purged_at IS NULL
          AND r.purge_attempts < 10
          AND (r.purge_lease_until IS NULL OR r.purge_lease_until < now())
        ORDER BY r.deleted_at
        FOR UPDATE SKIP LOCKED
        LIMIT 1
    )
    UPDATE app.resumes r
    SET purge_attempts = r.purge_attempts + 1,
        purge_lease_token = gen_random_uuid(),
        purge_lease_until = now() + (greatest(30, least(p_lease_seconds, 1800)) * interval '1 second'),
        version = r.version + 1
    FROM candidate c
    WHERE r.id = c.id
    RETURNING r.id, r.user_id, r.storage_key, r.purge_lease_token
$function$;

REVOKE ALL ON FUNCTION app.claim_resume_parse_job(integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.claim_resume_purge_job(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.claim_resume_parse_job(integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION app.claim_resume_purge_job(integer) TO ${app_role};

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
    'PREFERENCE_UPDATED'
));

COMMENT ON TABLE app.resumes IS 'Encrypted user resume objects and asynchronous extraction state.';
COMMENT ON TABLE app.job_preferences IS 'Versioned user job-search preferences with one current row.';
COMMENT ON TABLE app.job_posts IS 'User-owned normalized job pool; capture APIs are added in a later round.';
