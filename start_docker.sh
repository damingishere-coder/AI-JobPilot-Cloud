#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_URL="http://localhost:8080"
SECRET_DIR="$ROOT/.secrets"

if ! command -v docker >/dev/null 2>&1; then
  echo "错误：没有找到 docker 命令。请先安装并启动 Docker Desktop。" >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  echo "错误：Docker 或 Docker Compose 没有正常运行。" >&2
  exit 1
fi

mkdir -p "$SECRET_DIR"
chmod 700 "$SECRET_DIR"
for name in db_owner_password db_app_password redis_password; do
  path="$SECRET_DIR/$name"
  if [[ ! -s "$path" ]]; then
    umask 077
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$path"
    echo "已生成本机 Secret：$name"
  fi
done

cd "$ROOT"
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 600

echo "Cloud 环境已启动：$APP_URL"
echo "健康检查：$APP_URL/api/health"
echo "查看日志：docker compose logs -f"
echo "停止服务：docker compose down"

if command -v open >/dev/null 2>&1; then
  open "$APP_URL" >/dev/null 2>&1 || true
elif command -v xdg-open >/dev/null 2>&1; then
  xdg-open "$APP_URL" >/dev/null 2>&1 || true
fi
