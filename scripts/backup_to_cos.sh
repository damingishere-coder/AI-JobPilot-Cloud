#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="$ROOT/backups"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
SET_NAME="ai-jobpilot-$TIMESTAMP"
COS_BACKUP_URI="${COS_BACKUP_URI:-}"
AGE_RECIPIENT="${AGE_RECIPIENT:-}"

for command in docker age coscli sha256sum jq; do
  command -v "$command" >/dev/null 2>&1 || { echo "错误：缺少命令 $command。" >&2; exit 1; }
done
[[ "$COS_BACKUP_URI" =~ ^cos://[A-Za-z0-9._/-]+$ ]] || { echo "错误：COS_BACKUP_URI 必须是 cos://桶别名/目录。" >&2; exit 1; }
[[ -n "$AGE_RECIPIENT" ]] || { echo "错误：必须通过环境变量提供 AGE_RECIPIENT 公钥。" >&2; exit 1; }

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
WORK_DIR="$(mktemp -d "$BACKUP_DIR/.cos-backup.XXXXXX")"
case "$WORK_DIR" in "$BACKUP_DIR"/.cos-backup.*) ;; *) echo "临时目录越界。" >&2; exit 1;; esac
cleanup() {
  find "$WORK_DIR" -type f -delete 2>/dev/null || true
  find "$WORK_DIR" -depth -type d -empty -delete 2>/dev/null || true
}
trap cleanup EXIT

cd "$ROOT"
"$ROOT/scripts/backup_postgres.sh" "$SET_NAME.dump"
mv "$BACKUP_DIR/$SET_NAME.dump" "$WORK_DIR/database.dump"

api_container="$(docker compose ps -q api)"
[[ -n "$api_container" ]] || { echo "错误：API 容器未运行。" >&2; exit 1; }
storage_volume="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/lib/ai-jobpilot/private"}}{{.Name}}{{end}}{{end}}' "$api_container")"
[[ "$storage_volume" =~ ^[A-Za-z0-9_.-]+$ ]] || { echo "错误：无法确认 private-storage Docker 卷。" >&2; exit 1; }
docker run --rm --read-only -v "$storage_volume:/source:ro" alpine:3.22 tar -C /source -czf - . > "$WORK_DIR/private-storage.tar.gz"

docker compose exec -T postgres sh -ec '
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --no-psqlrc --tuples-only --no-align -c "
    SELECT format(
      '\''SELECT app.replay_account_deletion(%L::uuid,%L::uuid,%L::timestamptz,%L::timestamptz);'\'',
      account_id, deletion_request_id, completed_at, backup_expires_at
    ) FROM app.account_deletion_tombstones WHERE backup_expires_at > now() ORDER BY completed_at;"
' > "$WORK_DIR/deletion-ledger.sql"

db_version="$(docker compose exec -T postgres sh -ec 'psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --tuples-only --no-align -c "SELECT COALESCE(max(version), '\''0'\'') FROM app.flyway_schema_history WHERE success"' | tr -d '\r\n ')"

for source in database.dump private-storage.tar.gz deletion-ledger.sql; do
  age -r "$AGE_RECIPIENT" -o "$WORK_DIR/$source.age" "$WORK_DIR/$source"
  rm -f "$WORK_DIR/$source"
done

jq -n --arg set "$SET_NAME" --arg created "$TIMESTAMP" --arg dbVersion "$db_version" \
  --arg dbSha "$(sha256sum "$WORK_DIR/database.dump.age" | awk '{print $1}')" \
  --arg filesSha "$(sha256sum "$WORK_DIR/private-storage.tar.gz.age" | awk '{print $1}')" \
  --arg ledgerSha "$(sha256sum "$WORK_DIR/deletion-ledger.sql.age" | awk '{print $1}')" \
  --argjson dbSize "$(stat -c %s "$WORK_DIR/database.dump.age")" \
  --argjson filesSize "$(stat -c %s "$WORK_DIR/private-storage.tar.gz.age")" \
  --argjson ledgerSize "$(stat -c %s "$WORK_DIR/deletion-ledger.sql.age")" \
  '{set:$set,createdAt:$created,databaseVersion:$dbVersion,files:{"database.dump.age":{sha256:$dbSha,size:$dbSize},"private-storage.tar.gz.age":{sha256:$filesSha,size:$filesSize},"deletion-ledger.sql.age":{sha256:$ledgerSha,size:$ledgerSize}}}' \
  > "$WORK_DIR/manifest.json"

destination="${COS_BACKUP_URI%/}/$SET_NAME"
for file in database.dump.age private-storage.tar.gz.age deletion-ledger.sql.age manifest.json; do
  coscli cp "$WORK_DIR/$file" "$destination/$file" --forbid-overwrite true
  coscli cp "$destination/$file" "$WORK_DIR/verify-$file"
  cmp --silent "$WORK_DIR/$file" "$WORK_DIR/verify-$file" || { echo "错误：COS 回读校验失败：$file" >&2; exit 1; }
done

echo "加密备份已上传并回读校验：$destination"
echo "备份集：$SET_NAME；数据库版本：$db_version"
