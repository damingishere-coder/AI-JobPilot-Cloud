#!/bin/sh
set -eu

redis_password="$(cat /run/secrets/redis_password)"
case "$redis_password" in
  ''|*[!A-Za-z0-9_-]*)
    echo "redis_password 必须是非空的字母、数字、下划线或连字符" >&2
    exit 1
    ;;
esac

umask 077
{
  echo "bind 0.0.0.0"
  echo "protected-mode yes"
  echo "appendonly yes"
  echo "appendfsync everysec"
  echo "dir /data"
  printf 'requirepass %s\n' "$redis_password"
} > /tmp/redis.conf

exec redis-server /tmp/redis.conf
