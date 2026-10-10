#!/usr/bin/env bash
# From the developer machine: stream the live NAS bootstrap secret + TLS pair
# directly into age. Plaintext credential material is never written locally.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SYNC_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${SYNC_ROOT}/../.." && pwd)"
version="${LEZI_SYNC_VERSION:-}"
if [[ -z "${version}" ]]; then
  version="$(sed -n 's/^version = "\([^"]*\)"/\1/p' "${SYNC_ROOT}/Cargo.toml" | head -1)"
fi

NAS_SSH="${NAS_SSH:?Set NAS_SSH to the explicitly approved user@NAS host}"
NAS_SSH_PORT="${NAS_SSH_PORT:?Set NAS_SSH_PORT to the explicitly approved NAS port}"
case "${NAS_SSH#*@}" in
  vps-host|192.0.2.36|203.0.113.10|192.0.2.97|192.0.2.105)
    echo "error: NAS scripts cannot target ${NAS_SSH}; the VPS CD line was removed 2026-09-06 and must not be recreated" >&2
    exit 1
    ;;
esac
NAS_REMOTE_DIR="${NAS_REMOTE_DIR:-/tmp/lezi-sync-releases/lezi-sync-${version}-nas}"
config_root="${XDG_CONFIG_HOME:-${HOME:?HOME is required when XDG_CONFIG_HOME is unset}/.config}"
AGE_RECIPIENTS_FILE="${LEZI_AGE_RECIPIENTS_FILE:-${config_root}/lezi/age-recipients.txt}"
BACKUP_DIR="${LEZI_CREDENTIAL_BACKUP_DIR:-${config_root}/lezi/backups}"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -p "${NAS_SSH_PORT}")
DEPLOY_LOCK_TOKEN="${LEZI_DEPLOY_LOCK_TOKEN:-}"

if [[ "${NAS_REMOTE_DIR}" != /* \
    || ! "${NAS_REMOTE_DIR}" =~ ^/[A-Za-z0-9._/-]+$ \
    || "${NAS_REMOTE_DIR}" == *'//'* \
    || "${NAS_REMOTE_DIR}" == */./* \
    || "${NAS_REMOTE_DIR}" == */../* \
    || "${NAS_REMOTE_DIR}" == */. \
    || "${NAS_REMOTE_DIR}" == */.. ]]; then
  echo "error: NAS_REMOTE_DIR must be a normalized absolute NAS path using only A-Z a-z 0-9 . _ / -" >&2
  exit 1
fi
remote_package_basename="$(basename -- "${NAS_REMOTE_DIR}")"
expected_package_basename="lezi-sync-${version}-nas"
incoming_suffix="${remote_package_basename#"${expected_package_basename}.incoming-"}"
if [[ "${remote_package_basename}" != "${expected_package_basename}" \
    && ( "${incoming_suffix}" == "${remote_package_basename}" \
      || ! "${incoming_suffix}" =~ ^[0-9a-f]{64}$ ) ]]; then
  echo "error: NAS_REMOTE_DIR must identify the exact versioned package or its push-created staging directory" >&2
  exit 1
fi
case "${NAS_REMOTE_DIR}" in
  /|/tmp|/var|/var/tmp|/home|/root|/tmp/lezi-sync-releases)
    echo "error: refusing a broad NAS_REMOTE_DIR target" >&2
    exit 1
    ;;
esac
if [[ -n "${DEPLOY_LOCK_TOKEN}" \
    && ! "${DEPLOY_LOCK_TOKEN}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "error: LEZI_DEPLOY_LOCK_TOKEN must be 64 lowercase hexadecimal characters" >&2
  exit 1
fi

if ! command -v age >/dev/null 2>&1; then
  echo "error: age is required for encrypted NAS credential backups" >&2
  exit 1
fi
if [[ -z "${AGE_RECIPIENTS_FILE}" || ! -s "${AGE_RECIPIENTS_FILE}" ]]; then
  echo "error: LEZI_AGE_RECIPIENTS_FILE must name a non-empty age recipients file" >&2
  exit 1
fi
if [[ "${BACKUP_DIR}" != /* ]]; then
  echo "error: LEZI_CREDENTIAL_BACKUP_DIR must be an absolute path outside the repository" >&2
  exit 1
fi
canonical_backup_dir="$(realpath -m -- "${BACKUP_DIR}")"
canonical_repo_root="$(realpath -- "${REPO_ROOT}")"
case "${canonical_backup_dir}/" in
  /|"${canonical_repo_root}/"*)
    echo "error: credential backup directory must be outside the repository and filesystem root" >&2
    exit 1
    ;;
esac
BACKUP_DIR="${canonical_backup_dir}"
if [[ -L "${BACKUP_DIR}" ]]; then
  echo "error: credential backup directory must not be a symlink" >&2
  exit 1
fi
if [[ ! -e "${BACKUP_DIR}" ]]; then
  install -d -m 700 "${BACKUP_DIR}"
elif [[ ! -d "${BACKUP_DIR}" ]]; then
  echo "error: credential backup path is not a directory" >&2
  exit 1
elif [[ "$(stat -c '%a' "${BACKUP_DIR}")" != "700" ]]; then
  echo "error: credential backup directory must have mode 700" >&2
  exit 1
fi

printf -v remote_dir_q '%q' "${NAS_REMOTE_DIR}"
remote_env_prefix="LEZI_CREDENTIAL_EXPORT_PIPE=1 "
if [[ -n "${DEPLOY_LOCK_TOKEN}" ]]; then
  remote_env_prefix+="LEZI_DEPLOY_LOCK_TOKEN=${DEPLOY_LOCK_TOKEN} "
fi
if [[ -n "${LEZI_SECRET_FILE:-}" ]]; then
  if [[ "${LEZI_SECRET_FILE}" != /* \
      || ! "${LEZI_SECRET_FILE}" =~ ^/[A-Za-z0-9._/-]+$ \
      || "${LEZI_SECRET_FILE}" == *'//'* \
      || "${LEZI_SECRET_FILE}" == */./* \
      || "${LEZI_SECRET_FILE}" == */../* \
      || "${LEZI_SECRET_FILE}" == */. \
      || "${LEZI_SECRET_FILE}" == */.. \
      || "${LEZI_SECRET_FILE}" == *$'\n'* \
      || "${LEZI_SECRET_FILE}" == *$'\r'* ]]; then
    echo "error: LEZI_SECRET_FILE must be a normalized absolute NAS path using only A-Z a-z 0-9 . _ / -" >&2
    exit 1
  fi
  printf -v secret_file_q '%q' "${LEZI_SECRET_FILE}"
  remote_env_prefix+="LEZI_SECRET_FILE=${secret_file_q} "
fi

umask 077
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_file="${BACKUP_DIR}/lezi-sync-${version}-credentials-${timestamp}-${BASHPID}.age"
if [[ -e "${backup_file}" ]]; then
  echo "error: credential backup already exists for timestamp ${timestamp}" >&2
  exit 1
fi
temporary_file="$(mktemp "${BACKUP_DIR}/.lezi-credentials.XXXXXX.age.tmp")"
cleanup() {
  rm -f -- "${temporary_file}"
}
trap cleanup EXIT HUP INT TERM

echo "==> stream NAS credentials directly into age (plaintext is not stored locally)"
set +e
ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
    "cd ${remote_dir_q} && chmod +x export-nas-credentials.sh && ${remote_env_prefix}./export-nas-credentials.sh" \
  | "${SCRIPT_DIR}/validate-credential-bundle.sh" \
  | age --encrypt --recipients-file "${AGE_RECIPIENTS_FILE}" \
      --output "${temporary_file}"
pipeline_status=("${PIPESTATUS[@]}")
set -e
if [[ "${pipeline_status[2]}" -ne 0 ]]; then
  echo "error: age encryption failed" >&2
  exit 1
fi
if [[ "${pipeline_status[0]}" -ne 0 ]]; then
  if [[ "${pipeline_status[0]}" -eq 3 ]]; then
    echo "error: live container unavailable for pre-deploy credential backup" >&2
    exit 3
  fi
  echo "error: encrypted NAS credential backup failed" >&2
  exit 1
fi
if [[ "${pipeline_status[1]}" -ne 0 ]]; then
  echo "error: NAS credential export was not a recoverable bundle" >&2
  exit 1
fi
if [[ ! -s "${temporary_file}" ]]; then
  echo "error: age produced an empty credential backup" >&2
  exit 1
fi
chmod 600 "${temporary_file}"
mv -- "${temporary_file}" "${backup_file}"
trap - EXIT HUP INT TERM
backup_basename="$(basename -- "${backup_file}")"
(
  cd "${BACKUP_DIR}"
  sha256sum "${backup_basename}" >"${backup_basename}.sha256"
)
chmod 600 "${backup_file}.sha256"

echo "==> encrypted credential backup ready: ${backup_file}"
echo "    checksum: ${backup_file}.sha256"
