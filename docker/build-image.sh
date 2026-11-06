#!/usr/bin/env bash
# ============================================================
# 构建带版本信息的应用镜像（Linux / WSL）
#   ./docker/build-image.sh                  # 默认 tag = sha-<git短sha>
#   ./docker/build-image.sh 0.1.0            # 发布版：tag = 0.1.0
#   ALIAS_LATEST=1 ./docker/build-image.sh   # 额外打 latest（仅用于正式发布）
#
# 版本策略：镜像 tag 与 APP_VERSION/GIT_SHA 构建参数同时落 OCI labels；
#           compose 按 IMAGE_TAG 选择版本，回滚 = 改回旧 tag + up -d --no-build。
# ============================================================
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

SHA=$(git rev-parse --short=8 HEAD 2>/dev/null || echo unknown)
VERSION=$(awk '/<artifactId>javaHarness<\/artifactId>/{f=1;next}
               f && /<version>/{gsub(/.*<version>|<\/version>.*/,"");print;exit}' pom.xml)
VERSION=${VERSION:-dev}
TAG=${1:-sha-${SHA}}
IMAGE="deerfect-harness:${TAG}"

ARGS=(build -f docker/Dockerfile -t "$IMAGE"
      --build-arg "APP_VERSION=${VERSION}" --build-arg "GIT_SHA=${SHA}")
[ -n "${BUILD_BASE:-}" ] && ARGS+=(--build-arg "BUILD_BASE=${BUILD_BASE}")
[ -n "${RUN_BASE:-}" ] && ARGS+=(--build-arg "RUN_BASE=${RUN_BASE}")
ARGS+=(.)

echo "Building ${IMAGE} (version=${VERSION}, sha=${SHA}) ..."
docker "${ARGS[@]}"

if [ "${ALIAS_LATEST:-0}" = "1" ]; then
  docker tag "$IMAGE" deerfect-harness:latest
  echo "Aliased deerfect-harness:latest -> ${IMAGE}"
fi

cat <<EOF

Done. Start / upgrade:
  IMAGE_TAG='${TAG}' docker compose -f docker/docker-compose.yml up -d --no-build
Rollback: set IMAGE_TAG to the previous tag and re-run the same up command.
EOF
