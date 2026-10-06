#!/usr/bin/env bash
# Decrypt and validate a NAS credential backup into a new, local staging
# directory. This never installs or overwrites live NAS credentials.
set -euo pipefail

if [[ "$#" -ne 2 ]]; then
  echo "usage: LEZI_AGE_IDENTITY_FILE=/path/to/key.txt $0 BACKUP.age EMPTY_OUTPUT_DIR" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
backup_file="$1"
restore_dir="$2"
identity_file="${LEZI_AGE_IDENTITY_FILE:-}"
independent_certificate_sha256="${LEZI_EXPECTED_CERTIFICATE_SHA256:-}"
independent_spki_sha256="${LEZI_EXPECTED_SPKI_SHA256:-}"

if ! command -v age >/dev/null 2>&1; then
  echo "error: age is required to decrypt NAS credential backups" >&2
  exit 1
fi
if [[ -z "${identity_file}" || "${identity_file}" != /* ]]; then
  echo "error: LEZI_AGE_IDENTITY_FILE must name an absolute age identity path outside the repository" >&2
  exit 1
fi
if [[ -L "${identity_file}" || ! -f "${identity_file}" || ! -s "${identity_file}" ]]; then
  echo "error: age identity must be a non-empty regular non-symlink file" >&2
  exit 1
fi
canonical_identity_file="$(realpath -- "${identity_file}")"
canonical_repo_root="$(realpath -- "${REPO_ROOT}")"
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
if [[ ! "${independent_certificate_sha256}" =~ ^[0-9a-f]{64}$ \
    || ! "${independent_spki_sha256}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "error: restore requires independent 64-lowercase-hex LEZI_EXPECTED_CERTIFICATE_SHA256 and LEZI_EXPECTED_SPKI_SHA256 pins" >&2
  exit 1
fi
if [[ ! -s "${backup_file}" ]]; then
  echo "error: encrypted credential backup is missing or empty: ${backup_file}" >&2
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
  echo "error: unable to decrypt NAS credential backup" >&2
  exit 1
fi
mapfile -t bundle_lines <<<"${bundle}"
if [[ "${#bundle_lines[@]}" -ne 6 \
    || "${bundle_lines[0]}" != 'LEZI_CREDENTIAL_BUNDLE_V1' \
    || "${bundle_lines[1]}" != bootstrap_secret_b64=* \
    || "${bundle_lines[2]}" != server_crt_b64=* \
    || "${bundle_lines[3]}" != server_key_b64=* \
    || ! "${bundle_lines[4]}" =~ ^certificate_sha256=[0-9a-f]{64}$ \
    || ! "${bundle_lines[5]}" =~ ^spki_sha256=[0-9a-f]{64}$ ]]; then
  echo "error: decrypted credential backup has an invalid or unsupported format" >&2
  exit 1
fi

secret_b64="${bundle_lines[1]#bootstrap_secret_b64=}"
certificate_b64="${bundle_lines[2]#server_crt_b64=}"
private_key_b64="${bundle_lines[3]#server_key_b64=}"
expected_certificate_sha256="${bundle_lines[4]#certificate_sha256=}"
expected_spki_sha256="${bundle_lines[5]#spki_sha256=}"
base64_pattern='^[A-Za-z0-9+/]+={0,2}$'
if [[ ! "${secret_b64}" =~ ${base64_pattern} \
    || ! "${certificate_b64}" =~ ${base64_pattern} \
    || ! "${private_key_b64}" =~ ${base64_pattern} ]]; then
  echo "error: decrypted credential backup contains invalid base64 fields" >&2
  exit 1
fi
if ! printf '%s\n' "${bundle_lines[@]}" \
    | "${SCRIPT_DIR}/validate-credential-bundle.sh" >/dev/null; then
  echo "error: decrypted credential backup is not a recoverable bundle" >&2
  exit 1
fi
if [[ "${expected_certificate_sha256}" != "${independent_certificate_sha256}" \
    || "${expected_spki_sha256}" != "${independent_spki_sha256}" ]]; then
  echo "error: decrypted TLS identity does not match the independently recorded production pins" >&2
  exit 1
fi

umask 077
if ! mkdir -m 700 -- "${canonical_restore_dir}"; then
  echo "error: unable to atomically create the empty restore staging directory" >&2
  exit 1
fi
if ! mkdir -m 700 -- "${canonical_restore_dir}/tls"; then
  rmdir -- "${canonical_restore_dir}" 2>/dev/null || true
  echo "error: unable to create the TLS restore staging directory" >&2
  exit 1
fi
restore_complete=0
cleanup() {
  if [[ "${restore_complete}" != "1" ]]; then
    rm -f -- \
      "${canonical_restore_dir}/lezi-sync.env" \
      "${canonical_restore_dir}/MANIFEST.txt" \
      "${canonical_restore_dir}/.bootstrap-secret" \
      "${canonical_restore_dir}/tls/server.crt" \
      "${canonical_restore_dir}/tls/server.key"
    rmdir -- "${canonical_restore_dir}/tls" 2>/dev/null || true
    rmdir -- "${canonical_restore_dir}" 2>/dev/null || true
  fi
}
trap cleanup EXIT HUP INT TERM

if ! printf '%s' "${secret_b64}" | base64 -d \
    >"${canonical_restore_dir}/.bootstrap-secret" \
    || ! printf '%s' "${certificate_b64}" | base64 -d \
      >"${canonical_restore_dir}/tls/server.crt" \
    || ! printf '%s' "${private_key_b64}" | base64 -d \
      >"${canonical_restore_dir}/tls/server.key"; then
  echo "error: unable to decode credential backup fields" >&2
  exit 1
fi
if [[ "$(wc -l <"${canonical_restore_dir}/.bootstrap-secret")" -ne 0 ]] \
    || LC_ALL=C grep -q $'\r' "${canonical_restore_dir}/.bootstrap-secret"; then
  echo "error: restored bootstrap secret contains newline characters" >&2
  exit 1
fi
bootstrap_secret="$(cat "${canonical_restore_dir}/.bootstrap-secret")"
rm -f -- "${canonical_restore_dir}/.bootstrap-secret"
if [[ -z "${bootstrap_secret}" || "${#bootstrap_secret}" -lt 16 \
    || "${bootstrap_secret}" == *$'\n'* \
    || "${bootstrap_secret}" == *$'\r'* ]]; then
  echo "error: restored bootstrap secret is invalid" >&2
  exit 1
fi
printf 'LEZI_BOOTSTRAP_SECRET=%s\n' "${bootstrap_secret}" \
  >"${canonical_restore_dir}/lezi-sync.env"
chmod 600 "${canonical_restore_dir}/lezi-sync.env" \
  "${canonical_restore_dir}/tls/server.key"
chmod 644 "${canonical_restore_dir}/tls/server.crt"

certificate="${canonical_restore_dir}/tls/server.crt"
private_key="${canonical_restore_dir}/tls/server.key"
if ! openssl x509 -in "${certificate}" -checkend 86400 -noout >/dev/null 2>&1 \
    || ! openssl pkey -in "${private_key}" -check -noout >/dev/null 2>&1; then
  echo "error: restored TLS certificate or private key is invalid/near expiry" >&2
  exit 1
fi
certificate_public_key="$(openssl x509 -in "${certificate}" -pubkey -noout)"
private_public_key="$(openssl pkey -in "${private_key}" -pubout)"
if [[ "${certificate_public_key}" != "${private_public_key}" ]]; then
  echo "error: restored TLS certificate and private key do not match" >&2
  exit 1
fi
if [[ -n "${LEZI_EXPECTED_TLS_HOST:-}" ]]; then
  if [[ "${LEZI_EXPECTED_TLS_HOST}" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    openssl x509 -in "${certificate}" -checkip "${LEZI_EXPECTED_TLS_HOST}" \
      -noout >/dev/null
  else
    openssl x509 -in "${certificate}" -checkhost "${LEZI_EXPECTED_TLS_HOST}" \
      -noout >/dev/null
  fi
fi

actual_certificate_sha256="$(sha256sum "${certificate}" | awk '{print $1}')"
actual_spki_sha256="$(
  printf '%s\n' "${certificate_public_key}" \
    | openssl pkey -pubin -outform DER \
    | sha256sum \
    | awk '{print $1}'
)"
if [[ "${actual_certificate_sha256}" != "${expected_certificate_sha256}" \
    || "${actual_spki_sha256}" != "${expected_spki_sha256}" ]]; then
  echo "error: restored TLS certificate fingerprint does not match the encrypted bundle" >&2
  exit 1
fi
if [[ "${actual_certificate_sha256}" != "${independent_certificate_sha256}" \
    || "${actual_spki_sha256}" != "${independent_spki_sha256}" ]]; then
  echo "error: restored TLS identity does not match the independently recorded production pins" >&2
  exit 1
fi

encrypted_sha256="$(sha256sum "${backup_file}" | awk '{print $1}')"
cat >"${canonical_restore_dir}/MANIFEST.txt" <<EOF
format=LEZI_CREDENTIAL_BUNDLE_V1
encrypted_backup_sha256=${encrypted_sha256}
certificate_sha256=${actual_certificate_sha256}
spki_sha256=${actual_spki_sha256}
independent_production_pins_verified=true
restored_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
chmod 600 "${canonical_restore_dir}/MANIFEST.txt"

restore_complete=1
trap - EXIT HUP INT TERM
echo "==> credential backup validated into staging: ${canonical_restore_dir}"
echo "    certificate_sha256=${actual_certificate_sha256}"
echo "    spki_sha256=${actual_spki_sha256}"
echo "    live NAS credentials were not modified"
