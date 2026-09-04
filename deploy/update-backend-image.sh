#!/bin/sh
# 用 Windows/本机 Maven 打好的胖 jar 刷新 aigc-dit-backend:local，不在服务器上编译。
# 用法：把 dit-server.jar 放到本目录后执行
#   bash deploy/update-backend-image.sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/deploy/dit-server.jar"
IMAGE="${BACKEND_IMAGE:-aigc-dit-backend:local}"

if [ ! -f "$JAR" ]; then
  echo "缺少 $JAR"
  echo "在 Windows 工程根目录执行："
  echo "  mvn -pl dit-server -am package -DskipTests"
  echo "然后把 dit-server\\target\\dit-server-0.0.1-SNAPSHOT.jar"
  echo "拷到服务器 $JAR"
  exit 1
fi

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "本地没有基础镜像 $IMAGE"
  echo "需要先完整编过一次：docker compose up --build -d backend"
  exit 1
fi

echo "using base $IMAGE"
echo "replacing /app/app.jar <- $JAR"
docker build \
  -f "$ROOT/deploy/Dockerfile.prebuilt" \
  --build-arg "BASE_IMAGE=$IMAGE" \
  -t "$IMAGE" \
  "$ROOT/deploy"

cd "$ROOT"
docker compose up -d --no-deps --no-build --force-recreate backend
echo "done. check: docker compose logs -f --tail=50 backend"
