#!/usr/bin/env bash
# Copy-back (ticket 06) — step 3 only: validated local out/ → NAS data bind.
#
# Fixed maintenance order (see copy-back-tls-cutover-runbook.md):
#   1. stop live container
#   2. confirm dual backup (local copy-out + NAS-side)
#   3. copy-back upgraded out/          ← this script
#   4. start schema-12-compatible TLS deploy (CD) ← NOT this script
#   5. health/ready by actual protocol  ← NOT this script
#
# Does NOT start the container, does NOT run init-tls, does NOT claim live cutover
# success (ticket 07). `lezi-sync offline-migrate copy-back-help` points here.
#
# Required env:
#   LEZI_OUT_DIR                     migrate --out directory (must pass offline-migrate validate)
#   LEZI_CONFIRM_CONTAINER_STOPPED=1 operator + remote probe: container absent
#   LEZI_CONFIRM_DUAL_BACKUP=1       operator asserts dual backup intent
#   LEZI_NAS_PACKAGE_DIR              exact prebuilt package to reuse for deploy
#   LEZI_SCHEMA12_COMPATIBLE_IMAGE_ID exact attested image config digest from its manifest
#   LEZI_NAS_BACKUP_PATH             remote path to NAS-side v3 snapshot (non-dry-run)
#
# Explicit operator inputs (required; no personal defaults):
#   NAS_SSH              required explicit approved user@host
#   NAS_SSH_PORT         required explicit approved port
#   LEZI_DATA_HOST_PATH  required explicit approved absolute data path
# Optional behavior/runtime inputs:
#   LEZI_CONTAINER_NAME  default lezi-sync
#   LEZI_COPY_BACK_DRY_RUN=1  local gates + plan only; no SSH write
#   LEZI_SYNC_BIN        path to lezi-sync binary (for offline-migrate validate)
#   LEZI_COPY_BACK_SKIP_VALIDATE=1  dry-run tests only; refused for live write
#   LEZI_ALLOW_USER_VERSION_OVERRIDE=1  allow LEZI_EXPECTED_USER_VERSION ≠ shipped
#   LEZI_COPY_BACK_VIA_DOCKER=auto|1|0
#                        auto (default): if SSH user cannot mkdir sibling of data
#                        bind or cannot chown 10001, use docker (root) for stage
#                        swap + chown. 1 = always docker path; 0 = host-only.
#   LEZI_COPY_BACK_DOCKER_IMAGE  default alpine:3.20 (pulled if missing)
#
# Shipped gates (legacy target version is frozen independently; secret bytes remain
# aligned with the runtime gate by offline_migrate::cutover contract tests):
#   SHIPPED_USER_VERSION=12
#   SHIPPED_MIN_SECRET_BYTES=32
#
# Live write requires: rsync (no scp), sqlite3, full validate, remote container absent,
# NAS v3 backup path, staging dir + rename swap, uid 10001 ownership verified.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# BEGIN shipped constants (cutover.rs asserts these match Rust)
SHIPPED_USER_VERSION=12
SHIPPED_MIN_SECRET_BYTES=32
# END shipped constants

NAS_SSH="${NAS_SSH:?Set NAS_SSH to the explicitly approved user@NAS host}"
NAS_SSH_PORT="${NAS_SSH_PORT:?Set NAS_SSH_PORT to the explicitly approved NAS port}"
LEZI_DATA_HOST_PATH="${LEZI_DATA_HOST_PATH:?Set LEZI_DATA_HOST_PATH to the explicitly approved absolute NAS data path}"
LEZI_CONTAINER_NAME="${LEZI_CONTAINER_NAME:-lezi-sync}"
LEZI_COPY_BACK_DRY_RUN="${LEZI_COPY_BACK_DRY_RUN:-0}"
LEZI_COPY_BACK_SKIP_VALIDATE="${LEZI_COPY_BACK_SKIP_VALIDATE:-0}"
LEZI_COPY_BACK_VIA_DOCKER="${LEZI_COPY_BACK_VIA_DOCKER:-auto}"
LEZI_COPY_BACK_DOCKER_IMAGE="${LEZI_COPY_BACK_DOCKER_IMAGE:-alpine:3.20}"

die() {
  echo "copy-back failed: $*" >&2
  exit 1
}

# --- pin expected user_version to shipped constant unless dual override ---
if [[ -n "${LEZI_EXPECTED_USER_VERSION:-}" && "${LEZI_EXPECTED_USER_VERSION}" != "${SHIPPED_USER_VERSION}" ]]; then
  if [[ "${LEZI_ALLOW_USER_VERSION_OVERRIDE:-}" != "1" ]]; then
    die "LEZI_EXPECTED_USER_VERSION=${LEZI_EXPECTED_USER_VERSION} differs from shipped ${SHIPPED_USER_VERSION}; set LEZI_ALLOW_USER_VERSION_OVERRIDE=1 to force (not recommended)"
  fi
  EXPECTED_USER_VERSION="${LEZI_EXPECTED_USER_VERSION}"
else
  EXPECTED_USER_VERSION="${SHIPPED_USER_VERSION}"
fi
MIN_SECRET_BYTES="${SHIPPED_MIN_SECRET_BYTES}"

if [[ -z "${LEZI_OUT_DIR:-}" ]]; then
  die "LEZI_OUT_DIR is required (validated migrate --out)"
fi
if [[ "${LEZI_CONFIRM_CONTAINER_STOPPED:-}" != "1" ]]; then
  die "set LEZI_CONFIRM_CONTAINER_STOPPED=1 after stopping lezi-sync (data bind kept)"
fi
if [[ "${LEZI_CONFIRM_DUAL_BACKUP:-}" != "1" ]]; then
  die "set LEZI_CONFIRM_DUAL_BACKUP=1 after confirming local copy-out + NAS-side backup"
fi

OUT="${LEZI_OUT_DIR}"
[[ -d "${OUT}" ]] || die "LEZI_OUT_DIR is not a directory: ${OUT}"
[[ -f "${OUT}/lezi.db" ]] || die "missing ${OUT}/lezi.db"
[[ -f "${OUT}/server.secret" ]] || die "missing ${OUT}/server.secret (migrator must RegenerateAlways)"

secret_bytes="$(wc -c < "${OUT}/server.secret" | tr -d '[:space:]')"
if [[ "${secret_bytes}" -lt "${MIN_SECRET_BYTES}" ]]; then
  die "server.secret must be ≥ ${MIN_SECRET_BYTES} bytes (got ${secret_bytes})"
fi

# Fail-closed schema gate: sqlite3 required (live and dry-run). No silent skip.
if ! command -v sqlite3 >/dev/null 2>&1; then
  die "sqlite3 is required to prove PRAGMA user_version=${EXPECTED_USER_VERSION} before copy-back (install sqlite3)"
fi
UV="$(sqlite3 "${OUT}/lezi.db" 'PRAGMA user_version;')"
if [[ "${UV}" != "${EXPECTED_USER_VERSION}" ]]; then
  die "expected frozen legacy PRAGMA user_version=${EXPECTED_USER_VERSION} on out/ (got ${UV}); refuse copy-back of a different schema contract"
fi
# Residual WAL/SHM beside out/ must not exist (migrator cleans; refuse dirty handoff).
for side in lezi.db-wal lezi.db-shm lezi.db-journal; do
  if [[ -e "${OUT}/${side}" ]]; then
    die "refusing out/ with residual ${side}; run validate/migrate cleanup first"
  fi
done

resolve_lezi_sync_bin() {
  if [[ -n "${LEZI_SYNC_BIN:-}" ]]; then
    echo "${LEZI_SYNC_BIN}"
    return 0
  fi
  if command -v lezi-sync >/dev/null 2>&1; then
    command -v lezi-sync
    return 0
  fi
  # Prefer cargo-target from agent env, then local target dirs.
  local candidates=(
    "${CARGO_TARGET_DIR:-}/debug/lezi-sync"
    "${SCRIPT_DIR}/../target/debug/lezi-sync"
    "${SCRIPT_DIR}/../../target/debug/lezi-sync"
  )
  local c
  for c in "${candidates[@]}"; do
    if [[ -n "${c}" && -x "${c}" ]]; then
      echo "${c}"
      return 0
    fi
  done
  return 1
}

run_validate() {
  local bin
  if ! bin="$(resolve_lezi_sync_bin)"; then
    return 2
  fi
  "${bin}" offline-migrate validate --out "${OUT}"
}

# Live write refuses skip-validate even if set (check before attempting binary).
if [[ "${LEZI_COPY_BACK_DRY_RUN}" != "1" && "${LEZI_COPY_BACK_SKIP_VALIDATE}" == "1" ]]; then
  die "LEZI_COPY_BACK_SKIP_VALIDATE=1 is refused for live copy-back"
fi

# Full preflight (shape + secret) before any destructive remote write.
if [[ "${LEZI_COPY_BACK_DRY_RUN}" == "1" && "${LEZI_COPY_BACK_SKIP_VALIDATE}" == "1" ]]; then
  echo "warning: LEZI_COPY_BACK_SKIP_VALIDATE=1 (dry-run test harness only)" >&2
elif ! run_validate; then
  rc=$?
  if [[ "${LEZI_COPY_BACK_DRY_RUN}" == "1" && "${rc}" -eq 2 ]]; then
    die "lezi-sync binary not found for validate; set LEZI_SYNC_BIN or LEZI_COPY_BACK_SKIP_VALIDATE=1 (dry-run only)"
  fi
  if [[ "${rc}" -eq 2 ]]; then
    die "lezi-sync binary not found; set LEZI_SYNC_BIN to run offline-migrate validate before live copy-back"
  fi
  die "offline-migrate validate --out failed; refuse copy-back of non-preflight out/"
fi

echo "copy-back plan:" >&2
echo "  out=              ${OUT}" >&2
echo "  nas=              ${NAS_SSH}:${LEZI_DATA_HOST_PATH}/" >&2
echo "  dry_run=          ${LEZI_COPY_BACK_DRY_RUN}" >&2
echo "  user_version_exp= ${EXPECTED_USER_VERSION}" >&2
echo "  nas_backup=       ${LEZI_NAS_BACKUP_PATH:-"(dry-run may omit)"}" >&2
echo "  transport=        rsync --delete via staging+rename (scp refused)" >&2
echo "  next after copy:  start only the attested schema-12-compatible TLS image; 11/12→13 is not implemented here" >&2
echo "  deploy env:       export LEZI_BOOTSTRAP_SECRET=<migration new root password> LEZI_FORWARD_BOOTSTRAP_SECRET=1 LEZI_ALLOW_SECRET_RESEED=1 LEZI_ALLOW_TLS_BOOTSTRAP=1; guarded push-and-deploy" >&2
echo "  ticket 07 owns live cutover success claims" >&2

if [[ "${LEZI_COPY_BACK_DRY_RUN}" == "1" ]]; then
  echo "copy-back dry-run ok (no network write)"
  echo "out=${OUT}"
  echo "nas=${NAS_SSH}:${LEZI_DATA_HOST_PATH}/"
  echo "next: bind the prebuilt package with LEZI_NAS_PACKAGE_DIR + LEZI_SCHEMA12_COMPATIBLE_IMAGE_ID, then set LEZI_COPY_BACK_DRY_RUN=0 + LEZI_NAS_BACKUP_PATH=…; push must reuse it with LEZI_SKIP_PACKAGE=1"
  exit 0
fi

# --- live path only below ---
if [[ -z "${LEZI_NAS_PACKAGE_DIR:-}" || ! -d "${LEZI_NAS_PACKAGE_DIR}" || -L "${LEZI_NAS_PACKAGE_DIR}" ]]; then
  die "LEZI_NAS_PACKAGE_DIR must name the real prebuilt schema-12-compatible package directory"
fi
if [[ ! "${LEZI_SCHEMA12_COMPATIBLE_IMAGE_ID:-}" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  die "LEZI_SCHEMA12_COMPATIBLE_IMAGE_ID must be the attested sha256 config digest for the schema-12-compatible image"
fi
manifest="${LEZI_NAS_PACKAGE_DIR}/MANIFEST.json"
[[ -f "${manifest}" ]] || die "schema-12-compatible package is missing MANIFEST.json: ${manifest}"
mapfile -t manifest_image_ids < <(
  sed -nE 's/^[[:space:]]*"image_id"[[:space:]]*:[[:space:]]*"([^"]*)"[[:space:]]*,?[[:space:]]*$/\1/p' "${manifest}"
)
if [[ "${#manifest_image_ids[@]}" -ne 1 || "${manifest_image_ids[0]}" != "${LEZI_SCHEMA12_COMPATIBLE_IMAGE_ID}" ]]; then
  die "schema-12-compatible image digest does not exactly match package MANIFEST.json"
fi
if ! command -v rsync >/dev/null 2>&1; then
  die "rsync is required for live copy-back (scp fallback refused: no --delete mirror; residual WAL/SHM risk)"
fi

if [[ -z "${LEZI_NAS_BACKUP_PATH:-}" ]]; then
  die "LEZI_NAS_BACKUP_PATH is required for live copy-back (remote NAS-side v3 snapshot path)"
fi

SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -p "${NAS_SSH_PORT}")

# Positive absence proof: `docker ps` itself must succeed. An SSH/auth/daemon/
# permission failure is not allowed to masquerade as Docker's "not found".
printf -v container_name_q '%q' "${LEZI_CONTAINER_NAME}"
ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
  "names=\$(docker ps -a --filter name=^/${container_name_q}\$ --format '{{.Names}}') && test -z \"\${names}\"" \
  || die "could not positively prove remote container ${LEZI_CONTAINER_NAME} is absent; stop/rm it and verify Docker access before copy-back"

# Probe: NAS-side backup exists with v3 lezi.db.
ssh "${SSH_OPTS[@]}" "${NAS_SSH}" bash -s <<REMOTE_BACKUP_CHECK
set -euo pipefail
bak='${LEZI_NAS_BACKUP_PATH}'
if [[ ! -f "\${bak}/lezi.db" ]]; then
  echo "copy-back failed: NAS backup missing \${bak}/lezi.db" >&2
  exit 1
fi
if ! command -v sqlite3 >/dev/null 2>&1; then
  echo "copy-back failed: remote sqlite3 required to prove NAS backup user_version=3" >&2
  exit 1
fi
uv="\$(sqlite3 "\${bak}/lezi.db" 'PRAGMA user_version;')"
if [[ "\${uv}" != "3" ]]; then
  echo "copy-back failed: NAS backup user_version=\${uv} (want 3 at \${bak})" >&2
  exit 1
fi
REMOTE_BACKUP_CHECK

STAGING="${LEZI_DATA_HOST_PATH}.copy-back-staging.$$"
PREV="${LEZI_DATA_HOST_PATH}.pre-copy-back.$$"
PARENT="$(dirname "${LEZI_DATA_HOST_PATH}")"
BASE="$(basename "${LEZI_DATA_HOST_PATH}")"
# User-writable staging when host cannot mkdir under root-owned parent (docker path).
TMP_STAGING="/tmp/lezi-copy-back-staging.$$"

# Decide host vs docker-assisted remote write.
use_docker=0
case "${LEZI_COPY_BACK_VIA_DOCKER}" in
  1|true|yes) use_docker=1 ;;
  0|false|no) use_docker=0 ;;
  auto|*)
    # Probe: can SSH user create sibling of data bind and chown 10001?
    if ssh "${SSH_OPTS[@]}" "${NAS_SSH}" bash -s <<PROBE_HOST
set -euo pipefail
parent='${PARENT}'
probe="\${parent}/.copy-back-host-probe.\$\$"
mkdir -p "\${probe}" 2>/dev/null || exit 10
touch "\${probe}/f" 2>/dev/null || { rm -rf "\${probe}"; exit 11; }
if ! chown 10001:10001 "\${probe}/f" 2>/dev/null; then
  rm -rf "\${probe}"
  exit 12
fi
rm -rf "\${probe}"
exit 0
PROBE_HOST
    then
      use_docker=0
    else
      echo "copy-back: host cannot stage/chown under ${PARENT}; using docker-assisted path" >&2
      use_docker=1
    fi
    ;;
esac

if [[ "${use_docker}" -eq 0 ]]; then
  # Stage into sibling dir, then atomic directory rename into the bind path.
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
    "rm -rf '${STAGING}' && mkdir -p '${STAGING}' && mkdir -p '${PARENT}'"

  rsync -aH --delete --info=stats2 -e "ssh -p ${NAS_SSH_PORT} -o BatchMode=yes -o ConnectTimeout=15" \
    "${OUT}/" \
    "${NAS_SSH}:${STAGING}/"

  # Strip any residual sqlite sidecars on the staged tree (should already be absent).
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" bash -s <<REMOTE_STRIP
set -euo pipefail
st='${STAGING}'
rm -f "\${st}/lezi.db-wal" "\${st}/lezi.db-shm" "\${st}/lezi.db-journal"
test -f "\${st}/lezi.db"
test -f "\${st}/server.secret"
# Ownership required for container uid 10001 (fail closed).
chown -R 10001:10001 "\${st}" || {
  echo "copy-back failed: chown 10001:10001 staging failed (need root/CAP_CHOWN on NAS)" >&2
  exit 1
}
owner="\$(stat -c '%u' "\${st}/lezi.db" 2>/dev/null || stat -f '%u' "\${st}/lezi.db")"
if [[ "\${owner}" != "10001" ]]; then
  echo "copy-back failed: staging lezi.db owner=\${owner} want 10001" >&2
  exit 1
fi
REMOTE_STRIP

  # Directory swap: old bind → .pre-copy-back.$$ ; staging → bind path.
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" bash -s <<REMOTE_SWAP
set -euo pipefail
data='${LEZI_DATA_HOST_PATH}'
st='${STAGING}'
prev='${PREV}'
if [[ -e "\${data}" ]]; then
  rm -rf "\${prev}"
  mv "\${data}" "\${prev}"
fi
mv "\${st}" "\${data}"
# Ensure no leftover WAL from previous live tree is reachable under bind path.
rm -f "\${data}/lezi.db-wal" "\${data}/lezi.db-shm" "\${data}/lezi.db-journal"
owner="\$(stat -c '%u' "\${data}/lezi.db" 2>/dev/null || stat -f '%u' "\${data}/lezi.db")"
if [[ "\${owner}" != "10001" ]]; then
  echo "copy-back failed: live lezi.db owner=\${owner} after swap want 10001" >&2
  exit 1
fi
echo "copy-back remote swap ok; previous tree at \${prev} (remove after health green)"
REMOTE_SWAP
else
  # Docker-assisted path (measured Zspace: root-owned parent, no passwordless sudo).
  # 1) rsync out/ → /tmp staging (SSH-user writable)
  # 2) docker (root) mounts parent + /tmp staging, chown, directory rename swap
  echo "copy-back: docker image ${LEZI_COPY_BACK_DOCKER_IMAGE}" >&2
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
    "rm -rf '${TMP_STAGING}' && mkdir -p '${TMP_STAGING}'"

  rsync -aH --delete --info=stats2 -e "ssh -p ${NAS_SSH_PORT} -o BatchMode=yes -o ConnectTimeout=15" \
    "${OUT}/" \
    "${NAS_SSH}:${TMP_STAGING}/"

  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" bash -s <<REMOTE_DOCKER_SWAP
set -euo pipefail
img='${LEZI_COPY_BACK_DOCKER_IMAGE}'
parent='${PARENT}'
base='${BASE}'
tmp_st='${TMP_STAGING}'
# Ensure image present (pull if missing).
if ! docker image inspect "\${img}" >/dev/null 2>&1; then
  docker pull "\${img}"
fi
docker run --rm \
  -v "\${parent}:/lezi" \
  -v "\${tmp_st}:/staging:ro" \
  "\${img}" \
  sh -ec '
    set -euo pipefail
    base="'"${BASE}"'"
    data="/lezi/\${base}"
    st="/lezi/\${base}.copy-back-staging-docker"
    prev="/lezi/\${base}.pre-copy-back-docker"
    rm -rf "\${st}"
    mkdir -p "\${st}"
    # Copy from read-only /staging bind into writable sibling under parent.
    tar -C /staging -cf - . | tar -C "\${st}" -xf -
    rm -f "\${st}/lezi.db-wal" "\${st}/lezi.db-shm" "\${st}/lezi.db-journal"
    test -f "\${st}/lezi.db"
    test -f "\${st}/server.secret"
    chown -R 10001:10001 "\${st}"
    owner="\$(stat -c %u "\${st}/lezi.db")"
    if [ "\${owner}" != "10001" ]; then
      echo "copy-back failed: staging lezi.db owner=\${owner} want 10001" >&2
      exit 1
    fi
    if [ -e "\${data}" ]; then
      rm -rf "\${prev}"
      mv "\${data}" "\${prev}"
    fi
    mv "\${st}" "\${data}"
    rm -f "\${data}/lezi.db-wal" "\${data}/lezi.db-shm" "\${data}/lezi.db-journal"
    owner="\$(stat -c %u "\${data}/lezi.db")"
    if [ "\${owner}" != "10001" ]; then
      echo "copy-back failed: live lezi.db owner=\${owner} after swap want 10001" >&2
      exit 1
    fi
    echo "copy-back docker swap ok; previous tree at \${prev}"
  '
# Drop user-writable temp staging (payload already under data bind).
rm -rf "\${tmp_st}"
REMOTE_DOCKER_SWAP
  PREV="${LEZI_DATA_HOST_PATH}.pre-copy-back-docker"
fi

echo "copy-back ok"
echo "out=${OUT}"
echo "nas=${NAS_SSH}:${LEZI_DATA_HOST_PATH}/"
echo "previous_tree_remote=${PREV}"
echo "next: export LEZI_BOOTSTRAP_SECRET=<migration-time new root password> LEZI_FORWARD_BOOTSTRAP_SECRET=1 LEZI_ALLOW_SECRET_RESEED=1 LEZI_ALLOW_TLS_BOOTSTRAP=1 (NEVER inherit pre-cutover container env; explicit secret replacement; one-time TLS create)"
echo "then: cd tools/lezi-sync && ./deploy/push-and-deploy.sh  # forwards secret only with LEZI_FORWARD_BOOTSTRAP_SECRET=1"
echo "then: unset LEZI_BOOTSTRAP_SECRET LEZI_FORWARD_BOOTSTRAP_SECRET LEZI_ALLOW_SECRET_RESEED LEZI_ALLOW_TLS_BOOTSTRAP after cutover; probe health/ready (see copy-back-tls-cutover-runbook.md)"
echo "note: this script does NOT claim live cutover success (ticket 07)"
