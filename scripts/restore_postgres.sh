#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_NAME="${1:-}"
TARGET_DATABASE="${2:-ai_jobpilot_restore_$(date +%Y%m%d%H%M%S)}"
ALLOW_PRODUCTION="${3:-}"
PRODUCTION_CONFIRMATION="${4:-}"

if [[ -z "$BACKUP_NAME" || "$BACKUP_NAME" == */* || "$BACKUP_NAME" == *\\* ]]; then
  echo "用法：./scripts/restore_postgres.sh <backups目录内文件名> [目标数据库] [--allow-production-target] [CONFIRM-ai_jobpilot]" >&2
  exit 1
fi
BACKUP_PATH="$ROOT/backups/$BACKUP_NAME"
if [[ ! -f "$BACKUP_PATH" ]]; then
  echo "错误：备份文件不存在：$BACKUP_PATH" >&2
  exit 1
fi
if [[ ! "$TARGET_DATABASE" =~ ^ai_jobpilot(_restore_[A-Za-z0-9_]+)?$ ]]; then
  echo "错误：目标数据库名不合法。" >&2
  exit 1
fi
if [[ "$TARGET_DATABASE" == "ai_jobpilot" ]]; then
  if [[ "$ALLOW_PRODUCTION" != "--allow-production-target" || "$PRODUCTION_CONFIRMATION" != "CONFIRM-ai_jobpilot" ]]; then
    echo "错误：默认禁止覆盖主数据库；必须同时传入 --allow-production-target 和 CONFIRM-ai_jobpilot。" >&2
    exit 1
  fi
fi

cd "$ROOT"
container_id="$(docker compose ps -q postgres)"
docker cp "$BACKUP_PATH" "$container_id:/tmp/ai-jobpilot-restore.dump"
docker compose exec -T postgres sh -ec "dropdb --if-exists --force --username \"\$POSTGRES_USER\" '$TARGET_DATABASE'; createdb --username \"\$POSTGRES_USER\" '$TARGET_DATABASE'; pg_restore --exit-on-error --username \"\$POSTGRES_USER\" --dbname '$TARGET_DATABASE' /tmp/ai-jobpilot-restore.dump; rm -f /tmp/ai-jobpilot-restore.dump"
echo "恢复演练完成，目标数据库：$TARGET_DATABASE"
