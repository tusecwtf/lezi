#!/usr/bin/env bash
# From the dev machine: package (optional) → scp → SSH remote-deploy on NAS.
# Decisions:
#   - SSH automatic deploy
#   - prefer zdocker bundled docker-compose on NAS
#   - inherit LEZI_BOOTSTRAP_SECRET from the live container
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SYNC_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${SYNC_ROOT}/../.." && pwd)"

version="${LEZI_SYNC_VERSION:-}"
if [[ -z "${version}" ]]; then
  version="$(sed -n 's/^version = "\([^"]*\)"/\1/p' "${SYNC_ROOT}/Cargo.toml" | head -1)"
fi

NAS_SSH="${NAS_SSH:-13096920600@192.168.50.4}"
NAS_SSH_PORT="${NAS_SSH_PORT:-10000}"
# Zspace SSH users often have HOME=/home/ (not writable). Default to /tmp.
NAS_REMOTE_DIR="${NAS_REMOTE_DIR:-/tmp/lezi-sync-releases/lezi-sync-${version}-nas}"
PACKAGE_DIR="${LEZI_NAS_PACKAGE_DIR:-${REPO_ROOT}/dist/lezi-sync-${version}-nas}"
SKIP_PACKAGE="${LEZI_SKIP_PACKAGE:-0}"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -p "${NAS_SSH_PORT}")

echo "==> push-and-deploy lezi-sync ${version}"
echo "    nas:     ${NAS_SSH} port ${NAS_SSH_PORT}"
echo "    package: ${PACKAGE_DIR}"
echo "    remote:  ${NAS_REMOTE_DIR}"

if [[ "${SKIP_PACKAGE}" != "1" ]]; then
  if [[ ! -f "${PACKAGE_DIR}/remote-deploy.sh" ]] || [[ "${LEZI_FORCE_PACKAGE:-0}" == "1" ]]; then
    LEZI_SYNC_VERSION="${version}" "${SCRIPT_DIR}/package-nas.sh"
  else
    echo "==> reusing existing package (set LEZI_FORCE_PACKAGE=1 to rebuild)"
  fi
fi

if [[ ! -d "${PACKAGE_DIR}" ]]; then
  echo "error: package dir missing: ${PACKAGE_DIR}" >&2
  echo "  run: ${SCRIPT_DIR}/package-nas.sh" >&2
  exit 1
fi

echo "==> scp package → NAS"
ssh "${SSH_OPTS[@]}" "${NAS_SSH}" "mkdir -p '${NAS_REMOTE_DIR}'"
# Trailing slash: copy contents into remote dir
scp -P "${NAS_SSH_PORT}" -o BatchMode=yes -o ConnectTimeout=15 -r \
  "${PACKAGE_DIR}/." "${NAS_SSH}:${NAS_REMOTE_DIR}/"

echo "==> ssh remote-deploy.sh"
ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
  "cd '${NAS_REMOTE_DIR}' && chmod +x remote-deploy.sh && ./remote-deploy.sh"

echo "==> done"
