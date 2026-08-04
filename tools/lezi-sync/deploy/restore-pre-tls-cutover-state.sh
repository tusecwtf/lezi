#!/usr/bin/env bash
# Decrypt and validate a pre-TLS Docker start contract into a new developer-side
# staging directory. This script never connects to or modifies the NAS.
set -euo pipefail

if [[ "$#" -ne 2 ]]; then
  echo "usage: LEZI_AGE_IDENTITY_FILE=/path/to/key.txt LEZI_EXPECTED_PRE_TLS_IMAGE_ID=sha256:... LEZI_EXPECTED_PRE_TLS_BACKUP_SHA256=<independent pin> $0 BACKUP.age EMPTY_OUTPUT_DIR" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
backup_file="$1"
restore_dir="$2"
identity_file="${LEZI_AGE_IDENTITY_FILE:-}"
expected_image_id="${LEZI_EXPECTED_PRE_TLS_IMAGE_ID:-}"
independent_backup_sha256="${LEZI_EXPECTED_PRE_TLS_BACKUP_SHA256:-}"
CONTAINER_NAME="${LEZI_PRE_TLS_CONTAINER_NAME:-lezi-sync}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "error: python3 is required to validate a pre-TLS Docker start contract" >&2
  exit 1
fi
if ! command -v age >/dev/null 2>&1; then
  echo "error: age is required to decrypt pre-TLS rollback backups" >&2
  exit 1
fi
if [[ ! "${CONTAINER_NAME}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]]; then
  echo "error: LEZI_PRE_TLS_CONTAINER_NAME contains unsupported characters" >&2
  exit 1
fi
if [[ ! "${expected_image_id}" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo "error: LEZI_EXPECTED_PRE_TLS_IMAGE_ID must be the independently recorded sha256 image id from Step 0" >&2
  exit 1
fi
if [[ ! "${independent_backup_sha256}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "error: LEZI_EXPECTED_PRE_TLS_BACKUP_SHA256 must be the independently recorded ciphertext digest from Step 0" >&2
  exit 1
fi

canonical_repo_root="$(realpath -- "${REPO_ROOT}")"
if [[ -z "${identity_file}" || "${identity_file}" != /* ]]; then
  echo "error: LEZI_AGE_IDENTITY_FILE must name an absolute age identity path outside the repository" >&2
  exit 1
fi
if [[ -L "${identity_file}" || ! -f "${identity_file}" || ! -s "${identity_file}" ]]; then
  echo "error: age identity must be a non-empty regular non-symlink file" >&2
  exit 1
fi
canonical_identity_file="$(realpath -- "${identity_file}")"
case "${canonical_identity_file}" in
  "${canonical_repo_root}"|"${canonical_repo_root}/"*)
    echo "error: age identity must be outside the repository" >&2
    exit 1
    ;;
esac
if [[ "$(stat -c '%a' "${canonical_identity_file}")" != "600" ]]; then
  echo "error: age identity file must have mode 600" >&2
  exit 1
fi
identity_file="${canonical_identity_file}"

if [[ -L "${backup_file}" || ! -f "${backup_file}" || ! -s "${backup_file}" ]]; then
  echo "error: encrypted pre-TLS rollback backup must be a non-empty regular non-symlink file" >&2
  exit 1
fi
canonical_backup_file="$(realpath -- "${backup_file}")"
case "${canonical_backup_file}" in
  "${canonical_repo_root}"|"${canonical_repo_root}/"*)
    echo "error: encrypted pre-TLS rollback backup must be outside the repository" >&2
    exit 1
    ;;
esac
backup_file="${canonical_backup_file}"
checksum_file="${backup_file}.sha256"
if [[ -L "${checksum_file}" || ! -f "${checksum_file}" || ! -s "${checksum_file}" ]]; then
  echo "error: encrypted pre-TLS rollback backup requires its portable .sha256 sidecar" >&2
  exit 1
fi
mapfile -t checksum_lines <"${checksum_file}"
if [[ "${#checksum_lines[@]}" -ne 1 \
    || ! "${checksum_lines[0]}" =~ ^([0-9a-f]{64})\ \ ([^/]+)$ \
    || "${BASH_REMATCH[2]}" != "$(basename -- "${backup_file}")" ]]; then
  echo "error: pre-TLS rollback checksum sidecar has an invalid or non-portable format" >&2
  exit 1
fi
expected_backup_sha256="${BASH_REMATCH[1]}"
actual_backup_sha256="$(sha256sum "${backup_file}" | awk '{print $1}')"
if [[ "${actual_backup_sha256}" != "${expected_backup_sha256}" ]]; then
  echo "error: encrypted pre-TLS rollback backup checksum does not match" >&2
  exit 1
fi
if [[ "${actual_backup_sha256}" != "${independent_backup_sha256}" ]]; then
  echo "error: encrypted pre-TLS rollback backup does not match the independent ciphertext pin" >&2
  exit 1
fi

if [[ "${restore_dir}" != /* ]]; then
  echo "error: restore staging directory must be an absolute path outside the repository" >&2
  exit 1
fi
canonical_restore_dir="$(realpath -m -- "${restore_dir}")"
case "${canonical_restore_dir}/" in
  /|"${canonical_repo_root}/"*)
    echo "error: restore staging directory must be outside the repository and filesystem root" >&2
    exit 1
    ;;
esac
if [[ -e "${canonical_restore_dir}" || -L "${canonical_restore_dir}" ]]; then
  echo "error: restore staging target already exists; refusing to overwrite it" >&2
  exit 1
fi

if ! bundle="$(age --decrypt --identity "${identity_file}" "${backup_file}")"; then
  echo "error: unable to decrypt the pre-TLS rollback backup" >&2
  exit 1
fi
if ! validated_inspect="$(
  printf '%s\n' "${bundle}" \
    | python3 "${SCRIPT_DIR}/validate-pre-tls-cutover-state.py" \
        unpack --container-name "${CONTAINER_NAME}" \
        --expected-image-id "${expected_image_id}"
)"; then
  echo "error: decrypted pre-TLS rollback state is not recoverable" >&2
  exit 1
fi
if ! bootstrap_secret="$(
  printf '%s\n' "${bundle}" \
    | python3 "${SCRIPT_DIR}/validate-pre-tls-cutover-state.py" \
        secret --container-name "${CONTAINER_NAME}" \
        --expected-image-id "${expected_image_id}"
)"; then
  echo "error: unable to recover the validated pre-TLS bootstrap secret" >&2
  exit 1
fi
unset bundle

umask 077
if ! mkdir -m 700 -- "${canonical_restore_dir}"; then
  echo "error: unable to atomically create the empty restore staging directory" >&2
  exit 1
fi
restore_complete=0
cleanup() {
  unset bootstrap_secret validated_inspect
  if [[ "${restore_complete}" != "1" ]]; then
    rm -f -- \
      "${canonical_restore_dir}/docker-inspect.json" \
      "${canonical_restore_dir}/bootstrap-secret" \
      "${canonical_restore_dir}/MANIFEST.txt"
    rmdir -- "${canonical_restore_dir}" 2>/dev/null || true
  fi
}
trap cleanup EXIT HUP INT TERM

printf '%s\n' "${validated_inspect}" \
  >"${canonical_restore_dir}/docker-inspect.json"
printf '%s' "${bootstrap_secret}" \
  >"${canonical_restore_dir}/bootstrap-secret"
chmod 600 \
  "${canonical_restore_dir}/docker-inspect.json" \
  "${canonical_restore_dir}/bootstrap-secret"
unset bootstrap_secret validated_inspect

inspect_sha256="$(sha256sum "${canonical_restore_dir}/docker-inspect.json" | awk '{print $1}')"
container_id="$(
  python3 - "${canonical_restore_dir}/docker-inspect.json" <<'PY'
import json
import sys

with open(sys.argv[1], "rb") as stream:
    print(json.load(stream)[0]["Id"])
PY
)"
cat >"${canonical_restore_dir}/MANIFEST.txt" <<EOF
format=LEZI_PRE_TLS_CUTOVER_STATE_V1
encrypted_backup_sha256=${actual_backup_sha256}
inspect_sha256=${inspect_sha256}
container_id=${container_id}
image_id=${expected_image_id}
independent_image_pin_verified=true
independent_backup_pin_verified=true
restored_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
nas_modified=false
EOF
chmod 600 "${canonical_restore_dir}/MANIFEST.txt"

restore_complete=1
trap - EXIT HUP INT TERM
echo "==> pre-TLS rollback state validated into staging: ${canonical_restore_dir}"
echo "    image_id=${expected_image_id}"
echo "    inspect_sha256=${inspect_sha256}"
echo "    live NAS state was not modified"
