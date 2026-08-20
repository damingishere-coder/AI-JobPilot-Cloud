-- Round 5.5: audit the plugin pending-task poll and pin down audit actor
-- consistency. Keeps released V1-V6 immutable; forward-only changes.

-- 1. New audit action for a successful plugin pending poll. The Java side
--    AuditWriter.ALLOWED_ACTIONS carries the identical list; both must stay in
--    sync so an unknown action can never reach this CHECK.
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
    'PLUGIN_TASKS_PULLED'
));

-- 2. Actor consistency: USER/PLUGIN/ADMIN rows always carry the acting
--    principal id (user id for USER/ADMIN, device id for PLUGIN); SYSTEM rows
--    (workers, sweeps) never carry one. Every existing writer already follows
--    this rule; the CHECK pins it down for direct inserts.
ALTER TABLE app.audit_logs ADD CONSTRAINT audit_logs_actor_id_check CHECK (
    (actor_type IN ('USER', 'PLUGIN', 'ADMIN') AND actor_id IS NOT NULL)
    OR (actor_type = 'SYSTEM' AND actor_id IS NULL)
);

COMMENT ON CONSTRAINT audit_logs_action_check ON app.audit_logs IS
    'Stable action whitelist; mirrored by Java AuditWriter.ALLOWED_ACTIONS (keep in sync).';
COMMENT ON CONSTRAINT audit_logs_actor_id_check ON app.audit_logs IS
    'USER/PLUGIN/ADMIN audits always carry the acting principal id; SYSTEM audits never do.';
