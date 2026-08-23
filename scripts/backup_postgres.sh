#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="$ROOT/backups"
OUTPUT_NAME="${1:-ai-jobpilot-$(date +%Y%m%d-%H%M%S).dump}"
if [[ "$OUTPUT_NAME" == */* || "$OUTPUT_NAME" == *\\* || "$OUTPUT_NAME" != *.dump ]]; then
  echo "错误：备份文件名只能是 backups/ 下的 .dump 文件名。" >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
cd "$ROOT"
container_id="$(docker compose ps -q postgres)"
if [[ -z "$container_id" ]]; then
  echo "错误：PostgreSQL 容器未运行。" >&2
  exit 1
fi

docker compose exec -T postgres sh -ec 'exec pg_dump --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --format=custom --file=/tmp/ai-jobpilot-backup.dump'
docker cp "$container_id:/tmp/ai-jobpilot-backup.dump" "$BACKUP_DIR/$OUTPUT_NAME"
chmod 600 "$BACKUP_DIR/$OUTPUT_NAME"
docker compose exec -T postgres rm -f /tmp/ai-jobpilot-backup.dump >/dev/null
echo "PostgreSQL 备份完成：$BACKUP_DIR/$OUTPUT_NAME"
