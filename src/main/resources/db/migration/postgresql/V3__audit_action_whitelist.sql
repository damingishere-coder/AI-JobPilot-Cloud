-- Keep released migration V2 immutable; tighten audit events with a forward-only migration.

ALTER TABLE app.audit_logs
    DROP CONSTRAINT audit_logs_action_not_blank;

ALTER TABLE app.audit_logs
    ADD CONSTRAINT audit_logs_action_check CHECK (action IN (
        'AUTH_REGISTER',
        'AUTH_LOGIN',
        'AUTH_LOGOUT',
        'AUTH_LOGIN_FAILED',
        'AUTH_ACCOUNT_LOCKED',
        'AUTH_LOGIN_LOCKED',
        'AUTH_LOGIN_DISABLED',
        'AUTH_LOGIN_PENDING'
    ));
