#!/bin/sh
set -eu

app_password="$(cat /run/secrets/db_app_password)"
if [ -z "$app_password" ]; then
  echo "db_app_password 不能为空" >&2
  exit 1
fi

psql --set ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set app_password="$app_password" \
  --set db_name="$POSTGRES_DB" <<'EOSQL'
SELECT format('CREATE ROLE jobpilot_app LOGIN PASSWORD %L', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jobpilot_app') \gexec
SELECT format('ALTER ROLE jobpilot_app PASSWORD %L', :'app_password') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE :"db_name" FROM jobpilot_app;
GRANT CONNECT ON DATABASE :"db_name" TO jobpilot_app;
EOSQL
