#!/usr/bin/env bash
# From the developer machine, capture the exact pre-TLS Docker start contract
# (including its old bootstrap secret) directly into age ciphertext.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
NAS_SSH="${NAS_SSH:-nas-account@192.168.77.4}"
NAS_SSH_PORT="${NAS_SSH_PORT:-10000}"
CONTAINER_NAME="${LEZI_PRE_TLS_CONTAINER_NAME:-lezi-sync}"
EXPECTED_IMAGE_ID="${LEZI_EXPECTED_PRE_TLS_IMAGE_ID:-}"
config_root="${XDG_CONFIG_HOME:-${HOME:?HOME is required when XDG_CONFIG_HOME is unset}/.config}"
AGE_RECIPIENTS_FILE="${LEZI_AGE_RECIPIENTS_FILE:-${config_root}/lezi/age-recipients.txt}"
BACKUP_DIR="${LEZI_PRE_TLS_STATE_BACKUP_DIR:-${config_root}/lezi/pre-tls-cutover-backups}"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -p "${NAS_SSH_PORT}")

if [[ "${LEZI_ALLOW_PRE_TLS_CUTOVER_STATE_BACKUP:-0}" != "1" ]]; then
  echo "error: set LEZI_ALLOW_PRE_TLS_CUTOVER_STATE_BACKUP=1 only for the confirmed pre-TLS cutover rollback gate" >&2
  exit 1
fi
if [[ ! "${NAS_SSH_PORT}" =~ ^[0-9]+$ \
    || "${NAS_SSH_PORT}" -lt 1 \
    || "${NAS_SSH_PORT}" -gt 65535 ]]; then
  echo "error: NAS_SSH_PORT must be a number from 1 through 65535" >&2
  exit 1
fi
if [[ ! "${CONTAINER_NAME}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]]; then
  echo "error: LEZI_PRE_TLS_CONTAINER_NAME contains unsupported characters" >&2
  exit 1
fi
if [[ ! "${EXPECTED_IMAGE_ID}" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo "error: LEZI_EXPECTED_PRE_TLS_IMAGE_ID must be the independently recorded sha256 image id from Step 0" >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "error: python3 is required to validate the pre-TLS Docker start contract" >&2
  exit 1
fi
if ! command -v age >/dev/null 2>&1; then
  echo "error: age is required for encrypted pre-TLS rollback backups" >&2
  exit 1
fi
if [[ -z "${AGE_RECIPIENTS_FILE}" || ! -s "${AGE_RECIPIENTS_FILE}" ]]; then
  echo "error: LEZI_AGE_RECIPIENTS_FILE must name a non-empty age recipients file" >&2
  exit 1
fi
if [[ "${BACKUP_DIR}" != /* ]]; then
  echo "error: LEZI_PRE_TLS_STATE_BACKUP_DIR must be an absolute path outside the repository" >&2
  exit 1
fi
canonical_backup_dir="$(realpath -m -- "${BACKUP_DIR}")"
canonical_repo_root="$(realpath -- "${REPO_ROOT}")"
case "${canonical_backup_dir}/" in
  /|"${canonical_repo_root}/"*)
    echo "error: pre-TLS state backup directory must be outside the repository and filesystem root" >&2
    exit 1
    ;;
esac
BACKUP_DIR="${canonical_backup_dir}"
if [[ -L "${BACKUP_DIR}" ]]; then
  echo "error: pre-TLS state backup directory must not be a symlink" >&2
  exit 1
fi
if [[ ! -e "${BACKUP_DIR}" ]]; then
  install -d -m 700 "${BACKUP_DIR}"
elif [[ ! -d "${BACKUP_DIR}" ]]; then
  echo "error: pre-TLS state backup path is not a directory" >&2
  exit 1
elif [[ "$(stat -c '%a' "${BACKUP_DIR}")" != "700" ]]; then
  echo "error: pre-TLS state backup directory must have mode 700" >&2
  exit 1
fi

umask 077
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_file="${BACKUP_DIR}/lezi-sync-pre-tls-cutover-state-${timestamp}-${BASHPID}.age"
if [[ -e "${backup_file}" ]]; then
  echo "error: pre-TLS state backup target already exists" >&2
  exit 1
fi
temporary_file="$(mktemp "${BACKUP_DIR}/.lezi-pre-tls-state.XXXXXX.age.tmp")"
cleanup() {
  rm -f -- "${temporary_file}"
}
trap cleanup EXIT HUP INT TERM

echo "==> stream exact pre-TLS container start state directly into age"
echo "    plaintext start configuration is not stored locally"
set +e
ssh "${SSH_OPTS[@]}" "${NAS_SSH}" "docker inspect ${CONTAINER_NAME}" \
  | python3 "${SCRIPT_DIR}/validate-pre-tls-cutover-state.py" \
      pack --container-name "${CONTAINER_NAME}" \
      --expected-image-id "${EXPECTED_IMAGE_ID}" \
  | age --encrypt --recipients-file "${AGE_RECIPIENTS_FILE}" \
      --output "${temporary_file}"
pipeline_status=("${PIPESTATUS[@]}")
set -e
if [[ "${pipeline_status[2]}" -ne 0 ]]; then
  echo "error: age encryption failed" >&2
  exit 1
fi
if [[ "${pipeline_status[0]}" -ne 0 ]]; then
  echo "error: unable to inspect the live pre-TLS container" >&2
  exit 1
fi
if [[ "${pipeline_status[1]}" -ne 0 ]]; then
  echo "error: live container state is not a recoverable pre-TLS start contract" >&2
  exit 1
fi
if [[ ! -s "${temporary_file}" ]]; then
  echo "error: age produced an empty pre-TLS rollback backup" >&2
  exit 1
fi
chmod 600 "${temporary_file}"
mv -- "${temporary_file}" "${backup_file}"
trap - EXIT HUP INT TERM

backup_basename="$(basename -- "${backup_file}")"
backup_sha256="$(sha256sum "${backup_file}" | awk '{print $1}')"
(
  cd "${BACKUP_DIR}"
  printf '%s  %s\n' "${backup_sha256}" "${backup_basename}" \
    >"${backup_basename}.sha256"
)
chmod 600 "${backup_file}.sha256"

echo "==> encrypted pre-TLS rollback state ready: ${backup_file}"
echo "    checksum: ${backup_file}.sha256"
echo "    INDEPENDENT BACKUP SHA-256 PIN: ${backup_sha256}"
echo "    record that pin in a separate trusted location; the adjacent sidecar alone is not sender authentication"
echo "    keep both files and the independent pin until cutover and rollback acceptance are complete"
