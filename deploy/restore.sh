#!/usr/bin/env sh
# 从备份归档恢复线上数据卷；先自动备份当前卷，再清空目标卷并解压。
# 用法：sh deploy/restore.sh backups/gomoku-data-YYYYmmdd-HHMMSS.tgz [项目名] --confirm
set -eu

backup_file="${1:?请提供备份归档路径}"
project="${2:-gomoku}"
confirm="${3:-}"
[ "$confirm" = "--confirm" ] || { echo "恢复会覆盖现有数据卷；确认后追加 --confirm" >&2; exit 1; }

cd "$(dirname "$0")/.."
volume="${project}_gomoku-data"
stamp="$(date +%Y%m%d-%H%M%S)"
pre_dir="backups"
pre_name="pre-restore-${stamp}.tgz"
backup_dir="$(cd "$(dirname "$backup_file")" && pwd)"
backup_leaf="$(basename "$backup_file")"

docker volume inspect "$volume" >/dev/null
mkdir -p "$pre_dir"
pre_dir="$(cd "$pre_dir" && pwd)"

entries="$(docker run --rm -v "${backup_dir}:/backup:ro" alpine tar tzf "/backup/${backup_leaf}")"
echo "$entries" | grep -q 'games/archive\.sqlite' || { echo "备份缺少 games/archive.sqlite" >&2; exit 1; }
echo "$entries" | grep -q 'games/index\.jsonl' || { echo "备份缺少 games/index.jsonl" >&2; exit 1; }

echo "停止应用（等待优雅停机）..."
docker compose -p "$project" -f docker-compose.yml stop app
trap 'docker compose -p "$project" -f docker-compose.yml start app' EXIT INT TERM

echo "保护性备份当前卷..."
docker run --rm -v "${volume}:/data:ro" -v "${pre_dir}:/backup" alpine tar czf "/backup/${pre_name}" -C /data .
echo "PASS 当前卷已备份到 ${pre_dir}/${pre_name}"

echo "清空卷 ${volume} 并解压 ${backup_leaf} ..."
docker run --rm -v "${volume}:/data" alpine sh -c 'find /data -mindepth 1 -maxdepth 1 -exec rm -rf {} +'
docker run --rm -v "${volume}:/data" -v "${backup_dir}:/backup:ro" alpine tar xzf "/backup/${backup_leaf}" -C /data

echo "重新启动应用..."
docker compose -p "$project" -f docker-compose.yml start app
trap - EXIT INT TERM
echo "PASS 恢复完成。回滚归档：${pre_dir}/${pre_name}"
echo "请打开大厅抽查最近对局与复盘，再决定是否清理旧归档。"
