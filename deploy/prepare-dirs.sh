#!/bin/sh
# 在 Linux 服务器上创建统一数据目录（/mnt/ai-gc），供 docker compose 绑定挂载。
set -eu

ROOT="${DIT_DATA_ROOT:-/mnt/ai-gc}"

mkdir -p \
  "${ROOT}/postgres" \
  "${ROOT}/redis" \
  "${ROOT}/minio" \
  "${ROOT}/logs"

# postgres 官方镜像进程 uid=999
chown -R 999:999 "${ROOT}/postgres" || true
# redis 官方镜像进程 uid=999
chown -R 999:999 "${ROOT}/redis" || true

chmod 755 "${ROOT}" "${ROOT}/postgres" "${ROOT}/redis" "${ROOT}/minio" "${ROOT}/logs"

echo "ready:"
echo "  ${ROOT}/postgres  PostgreSQL 数据"
echo "  ${ROOT}/redis     Redis 持久化"
echo "  ${ROOT}/minio     图片/视频对象存储"
echo "  ${ROOT}/logs      应用日志"
