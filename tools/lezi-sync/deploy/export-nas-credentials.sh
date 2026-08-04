#!/usr/bin/env bash
# Runs on the NAS and writes a plaintext, line-oriented credential bundle to
# stdout. Its flag/terminal/regular-file checks are misuse guardrails; only the
# official backup wrapper validates the stream and sends it directly to age.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}"

CONTAINER_NAME="${LEZI_CONTAINER_NAME:-lezi-sync}"
EXPORT_PIPE="${LEZI_CREDENTIAL_EXPORT_PIPE:-0}"
DEPLOY_LOCK_TOKEN="${LEZI_DEPLOY_LOCK_TOKEN:-}"

if [[ "${EXPORT_PIPE}" != "1" ]]; then
  echo "error: set LEZI_CREDENTIAL_EXPORT_PIPE=1 and pipe stdout directly into age" >&2
  exit 1
fi
if [[ -n "${DEPLOY_LOCK_TOKEN}" \
    && ! "${DEPLOY_LOCK_TOKEN}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "error: LEZI_DEPLOY_LOCK_TOKEN must be 64 lowercase hexadecimal characters" >&2
  exit 1
fi
if [[ -t 1 ]]; then
  echo "error: refusing to print a plaintext credential bundle to a terminal" >&2
  exit 1
fi
exporter_pid="${BASHPID}"
stdout_type="$(stat -Lc '%F' "/proc/${exporter_pid}/fd/1" 2>/dev/null || true)"
if [[ "${stdout_type}" == regular*file ]]; then
  echo "error: refusing to redirect a plaintext credential bundle to a regular file" >&2
  exit 1
fi

validate_secret_value() {
  local value="$1" source_name="$2"
  if [[ -z "${value}" || "${#value}" -lt 16 ]]; then
    echo "error: ${source_name} must contain a bootstrap secret of at least 16 characters" >&2
    exit 1
  fi
  if [[ "${value}" == *$'\n'* || "${value}" == *$'\r'* ]]; then
    echo "error: ${source_name} must not contain newline characters" >&2
    exit 1
  fi
}

if [[ -n "${LEZI_DATA_HOST_PATH:-}" ]]; then
  data_path="${LEZI_DATA_HOST_PATH}"
else
  data_path="$(
    grep -E '^[[:space:]]*-[[:space:]]+[^#]+:/data' docker-compose.yml \
      | head -1 \
      | sed -E 's/^[[:space:]]*-[[:space:]]+//; s,:/data.*,,; s/[[:space:]]*$//'
  )"
fi
if [[ -z "${data_path}" || "${data_path}" != /* || "${data_path}" == "/" \
    || ! "${data_path}" =~ ^/[A-Za-z0-9._/-]+$ \
    || "${data_path}" == *'//'* \
    || "${data_path}" == */./* \
    || "${data_path}" == */../* \
    || "${data_path}" == */. \
    || "${data_path}" == */.. ]]; then
  echo "error: compose /data bind source must be a safe absolute host path" >&2
  exit 1
fi
case "${data_path}" in
  /etc|/usr|/var|/home|/root|/tmp|/opt|/srv)
    echo "error: compose /data bind source must not be a broad/system directory" >&2
    exit 1
    ;;
esac
if [[ -n "${LEZI_SECRET_FILE:-}" ]]; then
  secret_file="${LEZI_SECRET_FILE}"
else
  secret_file="$(dirname -- "${data_path}")/config/lezi-sync.env"
fi
if [[ "${secret_file}" != /* \
    || "${secret_file}" == "/" \
    || ! "${secret_file}" =~ ^/[A-Za-z0-9._/-]+$ \
    || "${secret_file}" == *'//'* \
    || "${secret_file}" == */./* \
    || "${secret_file}" == */../* \
    || "${secret_file}" == */. \
    || "${secret_file}" == */.. ]]; then
  echo "error: LEZI_SECRET_FILE must be a normalized absolute NAS path using only A-Z a-z 0-9 . _ / -" >&2
  exit 1
fi

# Serialize the credential snapshot with remote-deploy. The directory lock is
# deliberately shared through the persistent secret parent, so package paths
# and release versions cannot create independent lock domains. A stale lock is
# never auto-broken: an operator must first prove that no exporter/deploy is
# running, then remove it deliberately.
secret_directory="$(dirname -- "${secret_file}")"
if [[ -L "${secret_directory}" ]]; then
  echo "error: persistent bootstrap secret directory must not be a symlink" >&2
  exit 1
fi
if [[ ! -e "${secret_directory}" ]]; then
  install -d -m 700 "${secret_directory}"
elif [[ ! -d "${secret_directory}" ]]; then
  echo "error: persistent bootstrap secret parent is not a directory" >&2
  exit 1
elif [[ "$(stat -c '%a' "${secret_directory}")" != "700" ]]; then
  echo "error: persistent bootstrap secret directory must have mode 700" >&2
  exit 1
fi

credential_deploy_lock="$(dirname -- "${data_path}")/config/.lezi-sync-credential-deploy.lock"
credential_deploy_lock_held=0
release_credential_deploy_lock() {
  if [[ "${credential_deploy_lock_held}" == "1" ]]; then
    if ! rmdir -- "${credential_deploy_lock}"; then
      echo "error: could not release credential export/deploy lock: ${credential_deploy_lock}" >&2
    fi
    credential_deploy_lock_held=0
  fi
}
trap release_credential_deploy_lock EXIT
if [[ -n "${DEPLOY_LOCK_TOKEN}" ]]; then
  if [[ ! -x "${DIR}/credential-deploy-lock.sh" ]]; then
    echo "error: package is missing executable credential-deploy-lock.sh" >&2
    exit 1
  fi
  "${DIR}/credential-deploy-lock.sh" \
    validate "${credential_deploy_lock}" "${DEPLOY_LOCK_TOKEN}"
else
  if ! mkdir -m 700 -- "${credential_deploy_lock}" 2>/dev/null; then
    echo "error: credential export or deployment is already in progress" >&2
    echo "  if no process is active, inspect and remove the stale lock deliberately: ${credential_deploy_lock}" >&2
    exit 4
  fi
  credential_deploy_lock_held=1
fi

if ! docker inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
  echo "error: live container ${CONTAINER_NAME} is unavailable for credential export" >&2
  exit 3
fi
container_running="$(
  docker inspect "${CONTAINER_NAME}" --format '{{.State.Running}}' 2>/dev/null || true
)"
if [[ "${container_running}" != "true" ]]; then
  if [[ "${container_running}" == "false" ]]; then
    echo "error: container ${CONTAINER_NAME} is not running; live credential export is unavailable" >&2
  else
    echo "error: could not establish that container ${CONTAINER_NAME} is running" >&2
  fi
  exit 3
fi
mapfile -t live_secret_lines < <(
  docker inspect "${CONTAINER_NAME}" \
    --format '{{range .Config.Env}}{{println .}}{{end}}' \
    | sed -n 's/^LEZI_BOOTSTRAP_SECRET=//p'
)
if [[ "${#live_secret_lines[@]}" -ne 1 ]]; then
  echo "error: live container must contain exactly one LEZI_BOOTSTRAP_SECRET entry" >&2
  exit 1
fi
live_secret="${live_secret_lines[0]}"
validate_secret_value "${live_secret}" "live container LEZI_BOOTSTRAP_SECRET"

if [[ -e "${secret_file}" || -L "${secret_file}" ]]; then
  if [[ -L "${secret_file}" || ! -f "${secret_file}" ]]; then
    echo "error: persistent bootstrap secret path must be a regular non-symlink file" >&2
    exit 1
  fi
  if [[ "$(stat -c '%a' "$(dirname -- "${secret_file}")")" != "700" \
      || "$(stat -c '%a' "${secret_file}")" != "600" ]]; then
    echo "error: persistent bootstrap secret path permissions must be 700/600" >&2
    exit 1
  fi
  mapfile -t secret_lines <"${secret_file}"
  if [[ "${#secret_lines[@]}" -ne 1 \
      || "${secret_lines[0]}" != LEZI_BOOTSTRAP_SECRET=* ]]; then
    echo "error: persistent bootstrap secret file must contain exactly one LEZI_BOOTSTRAP_SECRET entry" >&2
    exit 1
  fi
  persistent_secret="${secret_lines[0]#LEZI_BOOTSTRAP_SECRET=}"
  validate_secret_value "${persistent_secret}" "persistent bootstrap secret file"
  if [[ "${persistent_secret}" != "${live_secret}" ]]; then
    echo "error: persistent bootstrap secret does not match the live container" >&2
    exit 1
  fi
else
  echo "warn: persistent bootstrap secret file is not seeded yet; exporting the validated live value" >&2
fi

tls_payload="$(
  docker exec "${CONTAINER_NAME}" /bin/sh -ec '
    certificate=/data/tls/server.crt
    private_key=/data/tls/server.key
    test -r "${certificate}" && test -r "${private_key}"
    openssl x509 -in "${certificate}" -checkend 86400 -noout >/dev/null
    openssl pkey -in "${private_key}" -check -noout >/dev/null 2>&1
    certificate_public_key="$(openssl x509 -in "${certificate}" -pubkey -noout)"
    private_public_key="$(openssl pkey -in "${private_key}" -pubout)"
    test "${certificate_public_key}" = "${private_public_key}"
    certificate_digest="$(openssl dgst -sha256 -r "${certificate}")"
    certificate_sha256="${certificate_digest%% *}"
    spki_digest="$(
      printf "%s\n" "${certificate_public_key}" \
        | openssl pkey -pubin -outform DER \
        | openssl dgst -sha256 -r
    )"
    spki_sha256="${spki_digest%% *}"
    printf "server_crt_b64=%s\n" "$(openssl base64 -A -in "${certificate}")"
    printf "server_key_b64=%s\n" "$(openssl base64 -A -in "${private_key}")"
    printf "certificate_sha256=%s\n" "${certificate_sha256}"
    printf "spki_sha256=%s\n" "${spki_sha256}"
  '
)"
mapfile -t tls_lines <<<"${tls_payload}"
if [[ "${#tls_lines[@]}" -ne 4 \
    || "${tls_lines[0]}" != server_crt_b64=* \
    || "${tls_lines[1]}" != server_key_b64=* \
    || ! "${tls_lines[2]}" =~ ^certificate_sha256=[0-9a-f]{64}$ \
    || ! "${tls_lines[3]}" =~ ^spki_sha256=[0-9a-f]{64}$ ]]; then
  echo "error: live TLS credential export returned an invalid payload" >&2
  exit 1
fi

bootstrap_secret_b64="$(printf '%s' "${live_secret}" | base64 | tr -d '\n')"
printf 'LEZI_CREDENTIAL_BUNDLE_V1\n'
printf 'bootstrap_secret_b64=%s\n' "${bootstrap_secret_b64}"
printf '%s\n' "${tls_payload}"
echo "==> exported validated live bootstrap secret and TLS identity (values not printed)" >&2
echo "    ${tls_lines[2]}" >&2
echo "    ${tls_lines[3]}" >&2
