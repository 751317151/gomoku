#!/usr/bin/env sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
command -v docker >/dev/null 2>&1 || { echo '请先安装 Docker 和 Compose v2' >&2; exit 1; }
[ -f .env ] || cp .env.example .env
mode=http
check_only=false
for arg do
  case "$arg" in
    http|https) mode=$arg ;;
    --check) check_only=true ;;
    *) echo '用法：sh deploy/start.sh [http|https] [--check]' >&2; exit 1 ;;
  esac
done
set -- -f docker-compose.yml
if [ "$mode" = https ]; then
  if grep -Eq '^DOMAIN=game.example.com[[:space:]]*$' .env; then echo '请先修改 .env 中的 DOMAIN 与 ACME_EMAIL' >&2; exit 1; fi
  set -- "$@" -f docker-compose.https.yml
fi
docker compose "$@" config --quiet
if [ "$check_only" = true ]; then
  exit
fi
docker compose "$@" up -d --build --wait --wait-timeout 180
docker compose "$@" ps
