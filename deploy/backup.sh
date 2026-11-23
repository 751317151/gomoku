#!/usr/bin/env sh
# 备份线上数据卷。默认先停应用（保证 SQLite 主文件与 WAL 一致），打包后立即启动。
# 用法：sh deploy/backup.sh [项目名] [输出目录] [保留份数]
set -eu

project="${1:-gomoku}"
out_dir="${2:-backups}"
keep="${3:-7}"

cd "$(dirname "$0")/.."
volume="${project}_gomoku-data"
stamp="$(date +%Y%m%d-%H%M%S)"
archive="gomoku-data-${stamp}.tgz"

mkdir -p "$out_dir"
out_abs="$(cd "$out_dir" && pwd)"

echo "停止应用（等待优雅停机）..."
docker compose -p "$project" -f docker-compose.yml stop app

restart() {
  echo "重新启动应用..."
  docker compose -p "$project" -f docker-compose.yml start app
}
trap restart EXIT INT TERM

echo "备份卷 ${volume} -> ${out_abs}/${archive}"
docker run --rm -v "${volume}:/data" -v "${out_abs}:/backup" alpine \
  tar czf "/backup/${archive}" -C /data .

entries="$(docker run --rm -v "${out_abs}:/backup" alpine tar tzf "/backup/${archive}")"
echo "$entries" | grep -q 'games/archive\.sqlite' || { echo "备份缺少 games/archive.sqlite" >&2; exit 1; }
echo "$entries" | grep -q 'games/index\.jsonl' || { echo "备份缺少 games/index.jsonl" >&2; exit 1; }
echo "PASS 备份完成：${out_abs}/${archive}"

restart
trap - EXIT INT TERM

if [ "$keep" -gt 0 ]; then
  ls -1t "$out_abs"/gomoku-data-*.tgz 2>/dev/null | tail -n "+$((keep + 1))" | while read -r old; do
    rm -f "$old"
    echo "已清理旧备份 $(basename "$old")"
  done
fi

echo "提醒：备份请复制到另一台机器或对象存储；只留在同一块磁盘不算灾备。"
