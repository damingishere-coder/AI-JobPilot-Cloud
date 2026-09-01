#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="$ROOT/backups"
SET_URI="${1:-}"
TARGET_DATABASE="${2:-}"
AGE_IDENTITY_FILE="${AGE_IDENTITY_FILE:-}"

[[ "$SET_URI" =~ ^cos://[A-Za-z0-9._/-]+/ai-jobpilot-[0-9TZ]+$ ]] || { echo "请提供精确 COS 备份集 URI。" >&2; exit 1; }
[[ "$TARGET_DATABASE" =~ ^ai_jobpilot_restore_[a-z0-9_]+$ ]] || { echo "目标库必须以 ai_jobpilot_restore_ 开头。" >&2; exit 1; }
[[ -f "$AGE_IDENTITY_FILE" ]] || { echo "AGE_IDENTITY_FILE 必须指向离线恢复私钥副本。" >&2; exit 1; }
for command in age coscli jq sha256sum; do command -v "$command" >/dev/null || { echo "缺少命令 $command。" >&2; exit 1; }; done

set_name="${SET_URI##*/}"
RESTORE_DIR="$BACKUP_DIR/restore-$set_name"
[[ ! -e "$RESTORE_DIR" ]] || { echo "恢复目录已存在，请人工核对后再处理：$RESTORE_DIR" >&2; exit 1; }
mkdir -p "$RESTORE_DIR"
case "$RESTORE_DIR" in "$BACKUP_DIR"/restore-ai-jobpilot-*) ;; *) echo "恢复目录越界。" >&2; exit 1;; esac

for file in database.dump.age private-storage.tar.gz.age deletion-ledger.sql.age manifest.json; do
  coscli cp "$SET_URI/$file" "$RESTORE_DIR/$file"
done
for file in database.dump.age private-storage.tar.gz.age deletion-ledger.sql.age; do
  expected="$(jq -r --arg name "$file" '.files[$name].sha256' "$RESTORE_DIR/manifest.json")"
  actual="$(sha256sum "$RESTORE_DIR/$file" | awk '{print $1}')"
  [[ "$expected" == "$actual" ]] || { echo "校验失败：$file" >&2; exit 1; }
done

age -d -i "$AGE_IDENTITY_FILE" -o "$RESTORE_DIR/database.dump" "$RESTORE_DIR/database.dump.age"
age -d -i "$AGE_IDENTITY_FILE" -o "$RESTORE_DIR/private-storage.tar.gz" "$RESTORE_DIR/private-storage.tar.gz.age"
age -d -i "$AGE_IDENTITY_FILE" -o "$RESTORE_DIR/deletion-ledger.sql" "$RESTORE_DIR/deletion-ledger.sql.age"

plain_dump="$BACKUP_DIR/$set_name.dump"
cp "$RESTORE_DIR/database.dump" "$plain_dump"
trap 'rm -f "$plain_dump"' EXIT
"$ROOT/scripts/restore_postgres.sh" "$set_name.dump" "$TARGET_DATABASE"
rm -f "$plain_dump"
docker compose exec -T postgres sh -lc \
  'export PGPASSWORD="$(cat /run/secrets/db_owner_password)"; exec psql "$@"' \
  sh -U "${DB_MIGRATION_USERNAME:-jobpilot_owner}" -d "$TARGET_DATABASE" -v ON_ERROR_STOP=1 \
  < "$RESTORE_DIR/deletion-ledger.sql"

mkdir -p "$RESTORE_DIR/private-storage"
tar -xzf "$RESTORE_DIR/private-storage.tar.gz" -C "$RESTORE_DIR/private-storage" --no-same-owner --no-same-permissions
echo "隔离恢复完成：数据库=$TARGET_DATABASE，文件目录=$RESTORE_DIR/private-storage"
echo "删除账本已回放；请继续执行非空合成简历与附件验收，禁止直接切换生产流量。"
