#!/usr/bin/env bash
# Copy-out (ticket 05) — authoritative ops narrative for NAS → local backup.
#
# Read-only on the NAS: does NOT stop the live container, does NOT copy back
# (ticket 06). `lezi-sync offline-migrate copy-out-help` points here.
#
# After this script (password mandatory for dry-run/migrate — flag or env):
#   export LEZI_MIGRATE_NEW_ROOT_PASSWORD='…ops-chosen ≥16 chars…'
#   lezi-sync offline-migrate dry-run --in "$BACKUP_DIR"
#   lezi-sync offline-migrate migrate --in "$BACKUP_DIR" --out "$OUT_DIR"
#   lezi-sync offline-migrate validate --out "$OUT_DIR"
# Or pass --new-root-password <secret> on dry-run / migrate instead of the env.
#
# Env (defaults match AGENTS.md / measured family control plane):
#   NAS_SSH              default nas-account@192.168.77.4
#   NAS_SSH_PORT         default 10000
#   LEZI_DATA_HOST_PATH  default /tmp/zfsv3/sata1/nas-account/data/Docker/lezi/data
#   LEZI_BACKUP_ROOT     default $HOME/lezi-nas-backups
#   LEZI_BACKUP_DIR      optional explicit destination (skips timestamp mkdir)
#   LEZI_COPY_OUT_RO=1   chmod -R a-w the backup after copy (default 1); fail if chmod fails
#   LEZI_COPY_OUT_VIA_DOCKER=auto|1|0
#                        auto (default): try host-path rsync first; on permission
#                        failure fall back to `docker exec lezi-sync tar` of /data.
#                        1 = docker only; 0 = host-path only (fail on permission).
#   LEZI_COPY_OUT_CONTAINER default lezi-sync
#   LEZI_MIGRATE_NEW_ROOT_PASSWORD  required by offline-migrate dry-run/migrate (not by this script)
#
# Hot-copy risk: live store may use WAL. This rsync/scp/tar is non-atomic while the
# container runs. Prefer a brief quiesce, `sqlite3 .backup` over SSH, or
# checkpoint then rsync for production cutover prep. After copy we require
# lezi.db present and PRAGMA user_version=3 when sqlite3 is available.
#
# Measured family NAS: data bind is often mode 700 uid 10001 — SSH user cannot
# rsync the host path. Docker-tar fallback is the supported path for that layout.
set -euo pipefail

NAS_SSH="${NAS_SSH:-nas-account@192.168.77.4}"
NAS_SSH_PORT="${NAS_SSH_PORT:-10000}"
LEZI_DATA_HOST_PATH="${LEZI_DATA_HOST_PATH:-/tmp/zfsv3/sata1/nas-account/data/Docker/lezi/data}"
LEZI_BACKUP_ROOT="${LEZI_BACKUP_ROOT:-${HOME}/lezi-nas-backups}"
LEZI_COPY_OUT_RO="${LEZI_COPY_OUT_RO:-1}"
LEZI_COPY_OUT_VIA_DOCKER="${LEZI_COPY_OUT_VIA_DOCKER:-auto}"
LEZI_COPY_OUT_CONTAINER="${LEZI_COPY_OUT_CONTAINER:-lezi-sync}"

if [[ -n "${LEZI_BACKUP_DIR:-}" ]]; then
  BACKUP_DIR="$LEZI_BACKUP_DIR"
else
  STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
  BACKUP_DIR="${LEZI_BACKUP_ROOT}/lezi-data-${STAMP}"
fi

mkdir -p "$BACKUP_DIR"

echo "copy-out: ${NAS_SSH}:${LEZI_DATA_HOST_PATH}/ -> ${BACKUP_DIR}/" >&2
echo "note: live container is left running; this ticket does not copy back" >&2
echo "note: hot-copy risk if NAS is writing; prefer quiesce/sqlite backup when possible" >&2

copy_out_via_docker() {
  echo "copy-out: using docker exec ${LEZI_COPY_OUT_CONTAINER} tar of /data (host path not readable)" >&2
  ssh -o BatchMode=yes -p "${NAS_SSH_PORT}" "${NAS_SSH}" \
    "docker exec ${LEZI_COPY_OUT_CONTAINER} tar -C /data -cf - ." \
    | tar -C "${BACKUP_DIR}" -xf -
}

copy_out_via_host_path() {
  if command -v rsync >/dev/null 2>&1; then
    rsync -aH --info=stats2 -e "ssh -p ${NAS_SSH_PORT}" \
      "${NAS_SSH}:${LEZI_DATA_HOST_PATH}/" \
      "${BACKUP_DIR}/"
  else
    echo "rsync not found; falling back to scp -r" >&2
    scp -P "${NAS_SSH_PORT}" -r \
      "${NAS_SSH}:${LEZI_DATA_HOST_PATH}/." \
      "${BACKUP_DIR}/"
  fi
}

case "${LEZI_COPY_OUT_VIA_DOCKER}" in
  1|true|yes)
    copy_out_via_docker
    ;;
  0|false|no)
    copy_out_via_host_path
    ;;
  auto|*)
    set +e
    host_err="$(copy_out_via_host_path 2>&1)"
    host_rc=$?
    set -e
    if [[ "${host_rc}" -ne 0 ]]; then
      echo "${host_err}" >&2
      if echo "${host_err}" | grep -qiE 'Permission denied|failed to set|change_dir'; then
        copy_out_via_docker
      else
        echo "copy-out failed: host-path transport exit ${host_rc}" >&2
        exit "${host_rc}"
      fi
    fi
    ;;
esac

if [[ ! -f "${BACKUP_DIR}/lezi.db" ]]; then
  echo "copy-out failed: ${BACKUP_DIR}/lezi.db missing" >&2
  exit 1
fi

if command -v sqlite3 >/dev/null 2>&1; then
  UV="$(sqlite3 "${BACKUP_DIR}/lezi.db" 'PRAGMA user_version;')"
  if [[ "${UV}" != "3" ]]; then
    echo "copy-out failed: expected PRAGMA user_version=3, got ${UV}" >&2
    exit 1
  fi
else
  echo "warning: sqlite3 not found; skipped user_version=3 check" >&2
fi

if [[ "${LEZI_COPY_OUT_RO}" == "1" ]]; then
  chmod -R a-w "${BACKUP_DIR}"
  # Fail closed: sample path must not be writable after RO mark.
  if [[ -w "${BACKUP_DIR}/lezi.db" ]]; then
    echo "copy-out failed: LEZI_COPY_OUT_RO=1 but ${BACKUP_DIR}/lezi.db is still writable" >&2
    exit 1
  fi
fi

echo "copy-out ok"
echo "backup=${BACKUP_DIR}"
echo "next: export LEZI_MIGRATE_NEW_ROOT_PASSWORD='…ops-chosen ≥16 chars…'"
echo "then: lezi-sync offline-migrate dry-run --in \"${BACKUP_DIR}\"   # or --new-root-password <secret>"
echo "then: lezi-sync offline-migrate migrate --in \"${BACKUP_DIR}\" --out <independent-out-dir>   # or --new-root-password <secret>"
echo "then: lezi-sync offline-migrate validate --out <independent-out-dir>"
