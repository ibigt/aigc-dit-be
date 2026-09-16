#!/usr/bin/env bash
# 本机打包后端 jar，上传到远程服务器，重建后端镜像并等待健康检查通过。
set -Eeuo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if [[ -f "$ROOT/.deploy.env" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT/.deploy.env"
fi

DEPLOY_HOST="${DEPLOY_HOST:-172.171.113.14}"
DEPLOY_PORT="${DEPLOY_PORT:-22}"
DEPLOY_USER="${DEPLOY_USER:-root}"
DEPLOY_REMOTE_ROOT="${DEPLOY_REMOTE_ROOT:-/opt/aigc-dit}"
BACKEND_IMAGE="${BACKEND_IMAGE:-aigc-dit-backend:local}"
DEPLOY_PASSWORDLESS="${DEPLOY_PASSWORDLESS:-0}"
SKIP_BUILD=0

usage() {
  cat <<'EOF'
用法：./deploy.sh [--skip-build]

  --skip-build  跳过 Maven，发布 dit-server/target 中已有的 jar

连接参数可写入项目根目录的 .deploy.env，参考 .deploy.env.example。
EOF
}

case "${1:-}" in
  "") ;;
  --skip-build) SKIP_BUILD=1 ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

for command_name in ssh scp tar; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "缺少命令：$command_name" >&2
    exit 1
  }
done

SSH_OPTIONS=(-o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -p "$DEPLOY_PORT")
SCP_OPTIONS=(-o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -P "$DEPLOY_PORT")

if [[ "$DEPLOY_USER" == "root" ]]; then
  if [[ "$DEPLOY_PASSWORDLESS" == "1" ]]; then
    SSH=(ssh "${SSH_OPTIONS[@]}")
    SCP=(scp "${SCP_OPTIONS[@]}")
  else
    command -v sshpass >/dev/null 2>&1 || {
      echo "缺少 sshpass；macOS 可执行：brew install hudochenkov/sshpass/sshpass" >&2
      echo "或配置 SSH key，并设置 DEPLOY_PASSWORDLESS=1。" >&2
      exit 1
    }
    if [[ -z "${DEPLOY_PASSWORD:-}" ]]; then
      read -rsp "${DEPLOY_USER}@${DEPLOY_HOST} 的 SSH 密码：" DEPLOY_PASSWORD
      echo
    fi
    export SSHPASS="$DEPLOY_PASSWORD"
    SSH=(sshpass -e ssh "${SSH_OPTIONS[@]}")
    SCP=(sshpass -e scp "${SCP_OPTIONS[@]}")
  fi
  SUDO_COMMAND=(bash -s --)
elif [[ "$DEPLOY_PASSWORDLESS" == "1" ]]; then
  SSH=(ssh "${SSH_OPTIONS[@]}")
  SCP=(scp "${SCP_OPTIONS[@]}")
  SUDO_COMMAND=(sudo -n bash -s --)
else
  command -v sshpass >/dev/null 2>&1 || {
    echo "缺少 sshpass；macOS 可执行：brew install hudochenkov/sshpass/sshpass" >&2
    echo "或配置 SSH key + 免密 sudo，并设置 DEPLOY_PASSWORDLESS=1。" >&2
    exit 1
  }
  if [[ -z "${DEPLOY_PASSWORD:-}" ]]; then
    read -rsp "${DEPLOY_USER}@${DEPLOY_HOST} 的 SSH/sudo 密码：" DEPLOY_PASSWORD
    echo
  fi
  export SSHPASS="$DEPLOY_PASSWORD"
  SSH=(sshpass -e ssh "${SSH_OPTIONS[@]}")
  SCP=(sshpass -e scp "${SCP_OPTIONS[@]}")
  SUDO_COMMAND=(sudo -S -p '' bash -s --)
fi

run_remote_root() {
  local remote_script remote_command
  remote_script="$(cat)"
  printf -v remote_command '%q ' "${SUDO_COMMAND[@]}" "$@"
  if [[ "$DEPLOY_PASSWORDLESS" == "1" || "$DEPLOY_USER" == "root" ]]; then
    printf '%s\n' "$remote_script" | "${SSH[@]}" "$DEPLOY_USER@$DEPLOY_HOST" "$remote_command"
  else
    {
      printf '%s\n' "$DEPLOY_PASSWORD"
      printf '%s\n' "$remote_script"
    } | "${SSH[@]}" "$DEPLOY_USER@$DEPLOY_HOST" "$remote_command"
  fi
}

if [[ "$SKIP_BUILD" != "1" ]]; then
  command -v mvn >/dev/null 2>&1 || {
    echo "缺少 Maven（mvn）。" >&2
    exit 1
  }
  echo "[1/4] Maven 打包后端..."
  (cd "$ROOT" && mvn -B -pl dit-server -am package -DskipTests)
else
  echo "[1/4] 已跳过 Maven 打包。"
fi

JAR="$ROOT/dit-server/target/dit-server-0.0.1-SNAPSHOT.jar"
[[ -f "$JAR" ]] || {
  echo "找不到后端 jar：$JAR" >&2
  exit 1
}

LOCAL_STAGE="$(mktemp -d "${TMPDIR:-/tmp}/aigc-dit-backend.XXXXXX")"
ARCHIVE="$LOCAL_STAGE/backend.tgz"
REMOTE_ARCHIVE="/tmp/aigc-dit-backend-${USER:-local}-$$.tgz"
cleanup() { rm -rf -- "$LOCAL_STAGE"; }
trap cleanup EXIT

cp "$JAR" "$LOCAL_STAGE/dit-server.jar"
cp "$ROOT/deploy/Dockerfile.prebuilt" "$LOCAL_STAGE/Dockerfile.prebuilt"
tar -czf "$ARCHIVE" -C "$LOCAL_STAGE" dit-server.jar Dockerfile.prebuilt

echo "[2/4] 上传发布包到 $DEPLOY_USER@$DEPLOY_HOST..."
"${SCP[@]}" "$ARCHIVE" "$DEPLOY_USER@$DEPLOY_HOST:$REMOTE_ARCHIVE"

echo "[3/4] 更新远程后端容器..."
run_remote_root "$DEPLOY_REMOTE_ROOT" "$REMOTE_ARCHIVE" "$BACKEND_IMAGE" <<'REMOTE_SCRIPT'
set -Eeuo pipefail
remote_root="$1"
archive="$2"
image="$3"
backend_root="$remote_root/aigc-dit-be"
stage="$(mktemp -d /tmp/aigc-dit-backend.XXXXXX)"
cleanup() { rm -rf -- "$stage" "$archive"; }
trap cleanup EXIT

[[ -f "$backend_root/docker-compose.yml" ]] || {
  echo "远程缺少 $backend_root/docker-compose.yml" >&2
  exit 1
}
docker image inspect "$image" >/dev/null 2>&1 || {
  echo "远程缺少基础镜像 $image，请先完整部署一次。" >&2
  exit 1
}

tar -xzf "$archive" -C "$stage"
docker build --build-arg "BASE_IMAGE=$image" -f "$stage/Dockerfile.prebuilt" -t "$image" "$stage"
docker compose -f "$backend_root/docker-compose.yml" up -d --no-deps --no-build --force-recreate backend

for ((i=1; i<=36; i++)); do
  status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' dit-backend 2>/dev/null || true)"
  if [[ "$status" == "healthy" ]]; then
    echo "后端健康检查通过。"
    exit 0
  fi
  if [[ "$status" == "unhealthy" || "$status" == "exited" || "$status" == "dead" ]]; then
    docker compose -f "$backend_root/docker-compose.yml" logs --tail=100 backend
    echo "后端启动失败：$status" >&2
    exit 1
  fi
  echo "等待后端启动（$i/36，当前：${status:-unknown}）..."
  sleep 5
done

docker compose -f "$backend_root/docker-compose.yml" logs --tail=100 backend
echo "后端健康检查超时。" >&2
exit 1
REMOTE_SCRIPT

echo "[4/4] 后端发布完成：http://$DEPLOY_HOST:18080/api/health"
