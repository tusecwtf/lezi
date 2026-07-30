#!/usr/bin/env bash
# Runs ON the NAS inside an unpacked release directory.
# - docker load image tar
# - inherit LEZI_BOOTSTRAP_SECRET from live lezi-sync when present
# - prefer zdocker bundled docker-compose; fall back to docker run
# - health-check; does not delete the data bind mount
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}"

CONTAINER_NAME="${LEZI_CONTAINER_NAME:-lezi-sync}"
COMPOSE_PROJECT="${LEZI_COMPOSE_PROJECT:-lezi}"
ZDOCKER_COMPOSE="${ZDOCKER_COMPOSE:-/zspace/applications/services/zdocker/bin/docker-compose}"
HEALTH_URL="${LEZI_HEALTH_URL:-http://127.0.0.1:8765/health}"
READY_URL="${LEZI_READY_URL:-http://127.0.0.1:8765/ready}"
EXPECTED_VERSION="${LEZI_SYNC_VERSION:-}"

if [[ -f MANIFEST.json ]]; then
  if command -v python3 >/dev/null 2>&1; then
    EXPECTED_VERSION="$(python3 -c 'import json,sys; print(json.load(open("MANIFEST.json"))["version"])' 2>/dev/null || true)"
  fi
  if [[ -z "${EXPECTED_VERSION}" ]]; then
    EXPECTED_VERSION="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' MANIFEST.json | head -1)"
  fi
fi

tar_file="$(ls -1 lezi-sync-*-linux-amd64.tar 2>/dev/null | head -1 || true)"
if [[ -z "${tar_file}" ]]; then
  echo "error: no lezi-sync-*-linux-amd64.tar in ${DIR}" >&2
  exit 1
fi

image="lezi-sync:${EXPECTED_VERSION:-unknown}"
if [[ -f MANIFEST.json ]]; then
  img_line="$(sed -n 's/.*"image"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' MANIFEST.json | head -1)"
  [[ -n "${img_line}" ]] && image="${img_line}"
fi

echo "==> remote deploy in ${DIR}"
echo "    tar:     ${tar_file}"
echo "    image:   ${image}"
echo "    project: ${COMPOSE_PROJECT}"

echo "==> docker load"
docker load -i "${tar_file}"

inherit_secret() {
  if [[ -n "${LEZI_BOOTSTRAP_SECRET:-}" ]]; then
    echo "${LEZI_BOOTSTRAP_SECRET}"
    return 0
  fi
  if docker inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
    docker inspect "${CONTAINER_NAME}" \
      --format '{{range .Config.Env}}{{println .}}{{end}}' \
      | sed -n 's/^LEZI_BOOTSTRAP_SECRET=//p' \
      | head -1
    return 0
  fi
  return 1
}

secret="$(inherit_secret || true)"
if [[ -z "${secret}" ]]; then
  echo "error: LEZI_BOOTSTRAP_SECRET not set and container ${CONTAINER_NAME} not found/has no secret" >&2
  echo "  export LEZI_BOOTSTRAP_SECRET=... and re-run" >&2
  exit 1
fi
if [[ "${#secret}" -lt 16 ]]; then
  echo "error: LEZI_BOOTSTRAP_SECRET must be at least 16 characters" >&2
  exit 1
fi

# .env for compose interpolation (mode 600)
umask 077
printf 'LEZI_BOOTSTRAP_SECRET=%s\n' "${secret}" > .env
chmod 600 .env
echo "==> wrote .env (secret inherited; not printed)"

# Bind source before ":/data" on the volume line.
data_path="$(
  grep -E '^[[:space:]]*-[[:space:]]+[^#]+:/data' docker-compose.yml \
    | head -1 \
    | sed -E 's/^[[:space:]]*-[[:space:]]+//; s,:/data.*,,; s/[[:space:]]*$//'
)"
if [[ -n "${data_path}" && -d "${data_path}" ]]; then
  echo "==> data path exists: ${data_path}"
else
  echo "warn: data path missing or unreadable: ${data_path:-unknown}" >&2
  echo "      ensure uid 10001 can write it (chown 10001:10001)" >&2
fi

echo "==> stop/remove existing container ${CONTAINER_NAME} (data bind kept)"
if docker inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
  docker stop "${CONTAINER_NAME}" >/dev/null || true
  docker rm "${CONTAINER_NAME}" >/dev/null || true
fi

use_zdocker_compose=0
if [[ -x "${ZDOCKER_COMPOSE}" ]]; then
  use_zdocker_compose=1
  echo "==> using zdocker compose: ${ZDOCKER_COMPOSE}"
  "${ZDOCKER_COMPOSE}" version || true
else
  echo "==> zdocker compose not found at ${ZDOCKER_COMPOSE}; falling back to docker run"
fi

if [[ "${use_zdocker_compose}" -eq 1 ]]; then
  # Project name only; container_name in yaml pins lezi-sync.
  "${ZDOCKER_COMPOSE}" -f docker-compose.yml --env-file .env -p "${COMPOSE_PROJECT}" up -d
else
  # Fallback mirrors the nas compose (keep in sync with template).
  docker run -d \
    --name "${CONTAINER_NAME}" \
    --user 10001:10001 \
    --restart unless-stopped \
    --init \
    --stop-timeout 30 \
    --security-opt no-new-privileges:true \
    --cap-drop ALL \
    -e LEZI_DATA_DIR=/data \
    -e LEZI_HOST=0.0.0.0 \
    -e LEZI_PORT=8765 \
    -e LEZI_INVITE_TTL_HOURS=24 \
    -e LEZI_MAX_MEDIA_BYTES=10485760 \
    -e LEZI_CREATE_RATE_LIMIT=20 \
    -e LEZI_JOIN_RATE_LIMIT=60 \
    -e LEZI_RATE_LIMIT_WINDOW_SECONDS=60 \
    -e LEZI_ALLOW_PERMISSION_HARDENING_SKIP=0 \
    -e "LEZI_BOOTSTRAP_SECRET=${secret}" \
    -v "${data_path}:/data" \
    -p 0.0.0.0:8765:8765 \
    "${image}"
fi

echo "==> wait for health"
ok=0
for i in $(seq 1 30); do
  if curl -fsS "${HEALTH_URL}" >/tmp/lezi-health.out 2>/dev/null \
    && curl -fsS "${READY_URL}" >/tmp/lezi-ready.out 2>/dev/null; then
    ok=1
    break
  fi
  sleep 1
done

if [[ "${ok}" -ne 1 ]]; then
  echo "error: health/ready failed" >&2
  docker ps -a --filter "name=^/${CONTAINER_NAME}$" || true
  docker logs --tail 80 "${CONTAINER_NAME}" 2>&1 || true
  exit 1
fi

echo "==> health: $(cat /tmp/lezi-health.out)"
echo "==> ready:  $(cat /tmp/lezi-ready.out)"

if [[ -n "${EXPECTED_VERSION}" ]]; then
  if ! grep -q "\"version\":\"${EXPECTED_VERSION}\"" /tmp/lezi-health.out \
    && ! grep -q "\"version\": \"${EXPECTED_VERSION}\"" /tmp/lezi-health.out; then
    echo "warn: /health version did not match expected ${EXPECTED_VERSION}" >&2
    cat /tmp/lezi-health.out >&2 || true
  fi
fi

docker ps --filter "name=^/${CONTAINER_NAME}$" --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
echo "==> deploy ok"
