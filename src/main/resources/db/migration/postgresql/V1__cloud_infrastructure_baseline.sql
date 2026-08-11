-- Cloud PostgreSQL infrastructure baseline.
-- Business tables intentionally start in later product rounds.

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA app TO ${app_role};

ALTER DEFAULT PRIVILEGES IN SCHEMA app
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${app_role};

ALTER DEFAULT PRIVILEGES IN SCHEMA app
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${app_role};

COMMENT ON SCHEMA app IS
    'AI-JobPilot-Cloud application schema; business tables are added by later Flyway migrations.';
