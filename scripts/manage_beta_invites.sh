#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTION="${1:-list}"
VALUE="${2:-}"
DB_NAME="${POSTGRES_DB:-ai_jobpilot}"
DB_OWNER="${DB_MIGRATION_USERNAME:-jobpilot_owner}"
cd "$ROOT"

owner_psql() {
  docker compose exec -T postgres sh -lc \
    'export PGPASSWORD="$(cat /run/secrets/db_owner_password)"; exec psql "$@"' \
    sh -U "$DB_OWNER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

case "$ACTION" in
  generate)
    days="${VALUE:-7}"
    [[ "$days" =~ ^[0-9]+$ ]] && (( days >= 1 && days <= 30 )) || { echo "有效天数必须为 1-30。" >&2; exit 1; }
    code="BETA-$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n' | tr '[:lower:]' '[:upper:]')"
    code_hash="$(printf '%s' "$code" | sha256sum | awk '{print $1}')"
    owner_psql -v "invite_hash=$code_hash" -v "valid_days=$days" -c \
      "INSERT INTO app.beta_invites (code_hash, expires_at) VALUES (:'invite_hash', now() + make_interval(days => :'valid_days'::int)) RETURNING id, expires_at;"
    echo "邀请码（仅此处显示一次）：$code"
    echo "有效期：$days 天。请通过安全私聊单独发送给一名测试者。"
    ;;
  revoke)
    [[ "$VALUE" =~ ^[0-9a-fA-F-]{36}$ ]] || { echo "请提供有效邀请码 UUID。" >&2; exit 1; }
    owner_psql -v "invite_id=$VALUE" -c \
      "UPDATE app.beta_invites SET revoked_at = now() WHERE id = :'invite_id'::uuid AND consumed_at IS NULL RETURNING id, revoked_at;"
    ;;
  list)
    owner_psql -c "SELECT id, expires_at, consumed_at, revoked_at FROM app.beta_invites ORDER BY created_at DESC LIMIT 50;"
    ;;
  *)
    echo "用法：$0 generate [有效天数] | list | revoke <邀请码UUID>" >&2
    exit 1
    ;;
esac
