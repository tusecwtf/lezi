#!/usr/bin/env bash
# Runs ON the NAS inside an unpacked release directory.
# - docker load image tar
# - validate live/persistent LEZI_BOOTSTRAP_SECRET sources before replacement
# - prefer zdocker bundled docker-compose; fall back to docker run
# - health-check; does not delete the data bind mount
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}"

CONTAINER_NAME="${LEZI_CONTAINER_NAME:-lezi-sync}"
COMPOSE_PROJECT="${LEZI_COMPOSE_PROJECT:-lezi}"
ZDOCKER_COMPOSE="${ZDOCKER_COMPOSE:-/zspace/applications/services/zdocker/bin/docker-compose}"
HEALTH_URL="${LEZI_HEALTH_URL:-https://127.0.0.1:8765/health}"
READY_URL="${LEZI_READY_URL:-https://127.0.0.1:8765/ready}"
EXPECTED_VERSION="${LEZI_SYNC_VERSION:-}"
TLS_HOST="${LEZI_TLS_HOST:-192.168.50.4}"
LAN_APK_DOWNLOAD_ORIGIN="${LEZI_LAN_APK_DOWNLOAD_ORIGIN:-}"
ALLOW_TLS_BOOTSTRAP="${LEZI_ALLOW_TLS_BOOTSTRAP:-0}"
ALLOW_SECRET_RECOVERY="${LEZI_ALLOW_SECRET_RECOVERY:-0}"
ALLOW_SECRET_RESEED="${LEZI_ALLOW_SECRET_RESEED:-0}"
BOOTSTRAP_SECRET_STDIN="${LEZI_BOOTSTRAP_SECRET_STDIN:-0}"
DEPLOY_LOCK_TOKEN="${LEZI_DEPLOY_LOCK_TOKEN:-}"

if [[ "${ALLOW_TLS_BOOTSTRAP}" != "0" && "${ALLOW_TLS_BOOTSTRAP}" != "1" ]]; then
  echo "error: LEZI_ALLOW_TLS_BOOTSTRAP must be 0 or 1" >&2
  exit 1
fi
if [[ "${ALLOW_SECRET_RECOVERY}" != "0" && "${ALLOW_SECRET_RECOVERY}" != "1" ]]; then
  echo "error: LEZI_ALLOW_SECRET_RECOVERY must be 0 or 1" >&2
  exit 1
fi
if [[ "${ALLOW_SECRET_RESEED}" != "0" && "${ALLOW_SECRET_RESEED}" != "1" ]]; then
  echo "error: LEZI_ALLOW_SECRET_RESEED must be 0 or 1" >&2
  exit 1
fi
if [[ "${BOOTSTRAP_SECRET_STDIN}" != "0" && "${BOOTSTRAP_SECRET_STDIN}" != "1" ]]; then
  echo "error: LEZI_BOOTSTRAP_SECRET_STDIN must be 0 or 1" >&2
  exit 1
fi
if [[ -n "${DEPLOY_LOCK_TOKEN}" \
    && ! "${DEPLOY_LOCK_TOKEN}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "error: LEZI_DEPLOY_LOCK_TOKEN must be 64 lowercase hexadecimal characters" >&2
  exit 1
fi
if [[ "${ALLOW_SECRET_RECOVERY}" == "1" \
    && ( "${ALLOW_SECRET_RESEED}" == "1" \
      || "${ALLOW_TLS_BOOTSTRAP}" == "1" ) ]]; then
  echo "error: secret recovery cannot be combined with secret reseed or TLS bootstrap" >&2
  exit 1
fi

if [[ ! -x "${DIR}/validate-nas-package.sh" ]]; then
  echo "error: package is missing executable validate-nas-package.sh" >&2
  exit 1
fi
# This must be the first package-dependent operation. It rejects stale files
# left by a previous scp before secret persistence, image load, or replacement.
"${DIR}/validate-nas-package.sh" "${DIR}" "${EXPECTED_VERSION}" >/dev/null

manifest_string() {
  local key="$1"
  sed -nE \
    "s/^[[:space:]]*\"${key}\"[[:space:]]*:[[:space:]]*\"([^\"]*)\"[[:space:]]*,?[[:space:]]*$/\\1/p" \
    MANIFEST.json
}

EXPECTED_VERSION="$(manifest_string version)"
image="$(manifest_string image)"
expected_image_id="$(manifest_string image_id)"
expected_image_os="$(manifest_string os)"
expected_image_architecture="$(manifest_string architecture)"
tar_file="$(manifest_string tar)"
manifest_tls_host="$(manifest_string tls_host)"
[[ -n "${manifest_tls_host}" ]] && TLS_HOST="${manifest_tls_host}"
manifest_lan_apk_download_origin="$(manifest_string lan_apk_download_origin)"
[[ -n "${manifest_lan_apk_download_origin}" ]] \
  && LAN_APK_DOWNLOAD_ORIGIN="${manifest_lan_apk_download_origin}"
if [[ -z "${LAN_APK_DOWNLOAD_ORIGIN}" ]]; then
  lan_apk_download_host="${TLS_HOST}"
  if [[ "${lan_apk_download_host}" == *:* ]]; then
    lan_apk_download_host="[${lan_apk_download_host}]"
  fi
  LAN_APK_DOWNLOAD_ORIGIN="http://${lan_apk_download_host}:8767"
fi

# Bind source before ":/data" on the volume line. Resolve this before the
# bootstrap secret so the durable secret file can live beside (not inside) the
# persistent data bind.
data_path="$(
  grep -E '^[[:space:]]*-[[:space:]]+[^#]+:/data' docker-compose.yml \
    | head -1 \
    | sed -E 's/^[[:space:]]*-[[:space:]]+//; s,:/data.*,,; s/[[:space:]]*$//'
)"
if [[ -z "${data_path}" || "${data_path}" != /* || "${data_path}" == "/" ]]; then
  echo "error: compose /data bind source must be a safe absolute host path" >&2
  exit 1
fi
SECRET_FILE="${LEZI_SECRET_FILE:-$(dirname -- "${data_path}")/config/lezi-sync.env}"
if [[ "${SECRET_FILE}" != /* \
    || "${SECRET_FILE}" == "/" \
    || ! "${SECRET_FILE}" =~ ^/[A-Za-z0-9._/-]+$ \
    || "${SECRET_FILE}" == *'//'* \
    || "${SECRET_FILE}" == */./* \
    || "${SECRET_FILE}" == */../* \
    || "${SECRET_FILE}" == */. \
    || "${SECRET_FILE}" == */.. ]]; then
  echo "error: LEZI_SECRET_FILE must be a normalized absolute NAS path using only A-Z a-z 0-9 . _ / -" >&2
  exit 1
fi

echo "==> remote deploy in ${DIR}"
echo "    tar:     ${tar_file}"
echo "    image:   ${image}"
echo "    project: ${COMPOSE_PROJECT}"
echo "    apk LAN: ${LAN_APK_DOWNLOAD_ORIGIN}"

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

prepare_secret_directory() {
  local secret_directory
  secret_directory="$(dirname -- "${SECRET_FILE}")"
  if [[ -L "${secret_directory}" ]]; then
    echo "error: persistent bootstrap secret directory must not be a symlink" >&2
    exit 1
  fi
  if [[ ! -e "${secret_directory}" ]]; then
    install -d -m 700 "${secret_directory}"
  fi
  if [[ ! -d "${secret_directory}" ]]; then
    echo "error: persistent bootstrap secret parent is not a directory" >&2
    exit 1
  elif [[ "$(stat -c '%a' "${secret_directory}")" != "700" ]]; then
    echo "error: persistent bootstrap secret directory must have mode 700" >&2
    exit 1
  fi
}

credential_deploy_lock="$(dirname -- "${data_path}")/config/.lezi-sync-credential-deploy.lock"
acquire_credential_deploy_lock() {
  if [[ -z "${DEPLOY_LOCK_TOKEN}" ]]; then
    echo "error: production remote-deploy requires the outer push-and-deploy lease and encrypted backup" >&2
    echo "  run push-and-deploy.sh; direct production execution is forbidden" >&2
    exit 1
  fi
  if [[ ! -x "${DIR}/credential-deploy-lock.sh" ]]; then
    echo "error: package is missing executable credential-deploy-lock.sh" >&2
    exit 1
  fi
  "${DIR}/credential-deploy-lock.sh" \
    validate "${credential_deploy_lock}" "${DEPLOY_LOCK_TOKEN}"
}

persistent_secret=""
read_persistent_secret() {
  local -a secret_lines=()
  if [[ ! -e "${SECRET_FILE}" && ! -L "${SECRET_FILE}" ]]; then
    return 1
  fi
  if [[ -L "${SECRET_FILE}" || ! -f "${SECRET_FILE}" ]]; then
    echo "error: persistent bootstrap secret path must be a regular non-symlink file" >&2
    exit 1
  fi
  if [[ "$(stat -c '%a' "${SECRET_FILE}")" != "600" ]]; then
    echo "error: persistent bootstrap secret file must have mode 600" >&2
    exit 1
  fi
  mapfile -t secret_lines <"${SECRET_FILE}"
  if [[ "${#secret_lines[@]}" -ne 1 \
      || "${secret_lines[0]}" != LEZI_BOOTSTRAP_SECRET=* ]]; then
    echo "error: persistent bootstrap secret file must contain exactly one LEZI_BOOTSTRAP_SECRET entry" >&2
    exit 1
  fi
  persistent_secret="${secret_lines[0]#LEZI_BOOTSTRAP_SECRET=}"
  validate_secret_value "${persistent_secret}" "persistent bootstrap secret file"
}

persist_secret_if_missing() {
  local secret_directory secret_temporary
  secret_directory="$(dirname -- "${SECRET_FILE}")"

  if [[ -e "${SECRET_FILE}" || -L "${SECRET_FILE}" ]]; then
    return 0
  fi
  if [[ ! -e "${secret_directory}" ]]; then
    install -d -m 700 "${secret_directory}"
  fi

  secret_temporary="$(mktemp "${secret_directory}/.lezi-sync.env.tmp.XXXXXX")"
  printf 'LEZI_BOOTSTRAP_SECRET=%s\n' "${secret}" >"${secret_temporary}"
  chmod 600 "${secret_temporary}"
  if ! ln "${secret_temporary}" "${SECRET_FILE}" 2>/dev/null; then
    rm -f -- "${secret_temporary}"
    echo "error: persistent bootstrap secret file appeared during atomic seed" >&2
    exit 1
  fi
  rm -f -- "${secret_temporary}"
  echo "==> seeded persistent bootstrap secret file: ${SECRET_FILE}"
}

replace_persistent_secret() {
  local secret_temporary
  secret_temporary="$(mktemp "$(dirname -- "${SECRET_FILE}")/.lezi-sync.env.tmp.XXXXXX")"
  printf 'LEZI_BOOTSTRAP_SECRET=%s\n' "$1" >"${secret_temporary}"
  chmod 600 "${secret_temporary}"
  mv -f -- "${secret_temporary}" "${SECRET_FILE}"
  echo "==> reseeded persistent bootstrap secret file after explicit maintenance authorization"
}

prepare_secret_directory
acquire_credential_deploy_lock
"${DIR}/validate-nas-package.sh" "${DIR}" "${EXPECTED_VERSION}" >/dev/null
echo "==> package integrity revalidated under credential/deploy lease"
has_persistent_secret=0
if read_persistent_secret; then
  has_persistent_secret=1
fi

container_exists=0
container_running=0
container_configured_secret=""
live_secret=""
if ! container_names_output="$(
  docker ps -a \
    --filter "name=^/${CONTAINER_NAME}$" \
    --format '{{.Names}}'
)"; then
  echo "error: could not enumerate existing containers before deployment" >&2
  exit 1
fi
container_names=()
if [[ -n "${container_names_output}" ]]; then
  mapfile -t container_names < <(printf '%s\n' "${container_names_output}")
fi
if [[ "${#container_names[@]}" -gt 1 \
    || ( "${#container_names[@]}" -eq 1 \
      && "${container_names[0]}" != "${CONTAINER_NAME}" ) ]]; then
  echo "error: container enumeration returned an ambiguous result" >&2
  exit 1
fi
if [[ "${#container_names[@]}" -eq 1 ]]; then
  container_exists=1
  if ! container_running_value="$(
    docker inspect "${CONTAINER_NAME}" --format '{{.State.Running}}'
  )"; then
    echo "error: could not inspect existing container state" >&2
    exit 1
  fi
  case "${container_running_value}" in
    true)
      container_running=1
      ;;
    false)
      ;;
    *)
      echo "error: could not establish whether container ${CONTAINER_NAME} is running" >&2
      exit 1
      ;;
  esac
  if ! container_environment="$(
    docker inspect "${CONTAINER_NAME}" \
      --format '{{range .Config.Env}}{{println .}}{{end}}'
  )"; then
    echo "error: could not inspect existing container environment" >&2
    exit 1
  fi
  mapfile -t container_configured_secret_lines < <(
    printf '%s\n' "${container_environment}" \
      | sed -n 's/^LEZI_BOOTSTRAP_SECRET=//p'
  )
  if [[ "${#container_configured_secret_lines[@]}" -ne 1 ]]; then
    echo "error: existing container must contain exactly one LEZI_BOOTSTRAP_SECRET entry" >&2
    exit 1
  fi
  container_configured_secret="${container_configured_secret_lines[0]}"
  validate_secret_value \
    "${container_configured_secret}" "existing container LEZI_BOOTSTRAP_SECRET"
fi
if [[ "${container_running}" == "1" ]]; then
  live_secret="${container_configured_secret}"
  validate_secret_value "${live_secret}" "live container LEZI_BOOTSTRAP_SECRET"
fi
if [[ "${ALLOW_TLS_BOOTSTRAP}" == "1" && "${container_exists}" == "1" ]]; then
  echo "error: TLS bootstrap requires the live container to be absent on a verified fresh data root" >&2
  exit 1
fi
if [[ "${ALLOW_SECRET_RECOVERY}" == "1" && "${container_running}" == "1" ]]; then
  echo "error: LEZI_ALLOW_SECRET_RECOVERY requires the live container to be absent or stopped" >&2
  exit 1
fi

explicit_secret="${LEZI_BOOTSTRAP_SECRET:-}"
if [[ "${BOOTSTRAP_SECRET_STDIN}" == "1" ]]; then
  if [[ -n "${explicit_secret}" ]]; then
    echo "error: provide the explicit bootstrap secret through env or stdin, not both" >&2
    exit 1
  fi
  if ! IFS= read -r explicit_secret; then
    echo "error: LEZI_BOOTSTRAP_SECRET_STDIN=1 requires one secret line on stdin" >&2
    exit 1
  fi
  if IFS= read -r _unexpected_secret_input; then
    echo "error: bootstrap secret stdin must contain exactly one line" >&2
    exit 1
  fi
fi
if [[ -n "${explicit_secret}" ]]; then
  validate_secret_value "${explicit_secret}" "LEZI_BOOTSTRAP_SECRET"
fi
if [[ "${ALLOW_SECRET_RECOVERY}" == "1" && -n "${explicit_secret}" ]]; then
  echo "error: secret recovery cannot be combined with an explicit bootstrap secret" >&2
  exit 1
fi
if [[ "${ALLOW_SECRET_RESEED}" == "1" && "${container_exists}" == "1" ]]; then
  echo "error: LEZI_ALLOW_SECRET_RESEED requires the live container to be absent" >&2
  exit 1
fi
if [[ "${ALLOW_SECRET_RESEED}" == "1" && -z "${explicit_secret}" ]]; then
  echo "error: LEZI_ALLOW_SECRET_RESEED=1 requires an explicit bootstrap secret" >&2
  exit 1
fi

if [[ "${container_running}" == "1" ]]; then
  secret="${live_secret}"
  if [[ -n "${explicit_secret}" && "${explicit_secret}" != "${live_secret}" ]]; then
    echo "error: explicit LEZI_BOOTSTRAP_SECRET does not match the live container" >&2
    exit 1
  fi
  if [[ "${has_persistent_secret}" == "1" \
      && "${persistent_secret}" != "${live_secret}" ]]; then
    echo "error: persistent bootstrap secret does not match the live container" >&2
    exit 1
  fi
elif [[ "${has_persistent_secret}" == "1" \
    && "${ALLOW_SECRET_RECOVERY}" == "1" ]]; then
  if [[ "${container_exists}" == "1" \
      && "${persistent_secret}" != "${container_configured_secret}" ]]; then
    echo "error: persistent bootstrap secret does not match the stopped container configuration" >&2
    exit 1
  fi
  secret="${persistent_secret}"
  if [[ "${container_exists}" == "1" ]]; then
    echo "==> recovered bootstrap secret from persistent file while the existing container is stopped (value not printed)"
  else
    echo "==> recovered bootstrap secret from persistent file (value not printed)"
  fi
elif [[ "${container_exists}" == "1" ]]; then
  echo "error: container ${CONTAINER_NAME} exists but is not running; no live secret authority is available" >&2
  echo "  verify the persistent secret and TLS pair, then set LEZI_ALLOW_SECRET_RECOVERY=1" >&2
  exit 1
elif [[ -n "${explicit_secret}" ]]; then
  secret="${explicit_secret}"
  if [[ "${has_persistent_secret}" == "1" \
      && "${persistent_secret}" != "${explicit_secret}" ]]; then
    if [[ "${ALLOW_SECRET_RESEED}" != "1" ]]; then
      echo "error: explicit LEZI_BOOTSTRAP_SECRET does not match the persistent file" >&2
      exit 1
    fi
    replace_persistent_secret "${explicit_secret}"
    persistent_secret="${explicit_secret}"
  fi
else
  echo "error: container ${CONTAINER_NAME} is absent; persistent secret recovery is not authorized" >&2
  echo "  verify the incident, then set LEZI_ALLOW_SECRET_RECOVERY=1 to use ${SECRET_FILE}" >&2
  exit 1
fi

if [[ "${has_persistent_secret}" == "0" ]]; then
  persist_secret_if_missing
fi

echo "==> bootstrap secret sources validated (values not printed)"

# A pre-hardening release may have left a second plaintext copy beside the
# package. It is never authoritative: fail instead of silently deleting an
# unexpected artifact inside the package while a deployment is in progress.
if [[ -e .env || -L .env ]]; then
  echo "error: unexpected legacy .env in the NAS package directory" >&2
  echo "  inspect and remove it deliberately, then transfer an exact package" >&2
  exit 1
fi

"${DIR}/validate-nas-package.sh" "${DIR}" "${EXPECTED_VERSION}" >/dev/null
echo "==> package integrity revalidated immediately before image load"
echo "==> docker load"
docker load -i "${tar_file}"
if ! loaded_image_id="$(docker image inspect "${image}" --format '{{.Id}}')"; then
  echo "error: loaded archive did not provide manifest image ${image}" >&2
  exit 1
fi
if [[ "${loaded_image_id}" != "${expected_image_id}" ]]; then
  echo "error: loaded image id does not match MANIFEST.json" >&2
  echo "  expected: ${expected_image_id}" >&2
  echo "  actual:   ${loaded_image_id:-missing}" >&2
  exit 1
fi
loaded_image_os="$(docker image inspect "${image}" --format '{{.Os}}')"
loaded_image_architecture="$(docker image inspect "${image}" --format '{{.Architecture}}')"
if [[ "${loaded_image_os}" != "${expected_image_os}" \
    || "${loaded_image_architecture}" != "${expected_image_architecture}" \
    || "${loaded_image_os}" != "linux" \
    || "${loaded_image_architecture}" != "amd64" ]]; then
  echo "error: loaded image platform does not match the linux/amd64 manifest" >&2
  echo "  actual: ${loaded_image_os:-missing}/${loaded_image_architecture:-missing}" >&2
  exit 1
fi
echo "==> loaded image identity/platform validated: ${expected_image_id} linux/amd64"

# Compose and docker-run inherit the already validated value through their
# process environment. This preserves arbitrary non-newline characters exactly
# and keeps the secret out of dotenv interpolation and command arguments.

if [[ -n "${data_path}" && -d "${data_path}" ]]; then
  echo "==> data path exists: ${data_path}"
else
  echo "warn: data path missing or unreadable: ${data_path:-unknown}" >&2
  echo "      ensure uid 10001 can write it (chown 10001:10001)" >&2
fi

echo "==> initialize or validate persistent TLS identity"
tls_identity_state_before="$(
  LEZI_ALLOW_TLS_BOOTSTRAP=0 LEZI_TLS_INSPECT_ONLY=1 \
    "${DIR}/init-tls.sh" "${data_path}" "${image}" "${TLS_HOST}"
)"
case "${tls_identity_state_before}" in
  present)
    tls_certificate_sha256_before="$(
      "${DIR}/tls-certificate-sha256.sh" "${data_path}" "${image}"
    )"
    tls_spki_before="$(
      "${DIR}/tls-spki.sh" "${data_path}" "${image}"
    )"
    ;;
  absent)
    if [[ "${ALLOW_TLS_BOOTSTRAP}" != "1" ]]; then
      echo "error: ordinary CD requires a complete pre-existing TLS identity" >&2
      exit 1
    fi
    tls_certificate_sha256_before=""
    tls_spki_before=""
    ;;
  *)
    echo "error: TLS identity inspection returned an invalid state" >&2
    exit 1
    ;;
esac
LEZI_TLS_INSPECT_ONLY=0 \
  "${DIR}/init-tls.sh" "${data_path}" "${image}" "${TLS_HOST}"
tls_certificate_sha256_expected="$(
  "${DIR}/tls-certificate-sha256.sh" "${data_path}" "${image}"
)"
tls_spki_expected="$("${DIR}/tls-spki.sh" "${data_path}" "${image}")"
if [[ "${tls_identity_state_before}" == "present" ]]; then
  "${DIR}/tls-spki.sh" "${data_path}" "${image}" "${tls_spki_before}" >/dev/null
  "${DIR}/tls-certificate-sha256.sh" \
    "${data_path}" "${image}" "${tls_certificate_sha256_before}" >/dev/null
  echo "==> pre-replace TLS certificate preserved: ${tls_certificate_sha256_expected}"
  echo "==> pre-replace TLS SPKI preserved: ${tls_spki_expected}"
else
  echo "==> bootstrapped TLS certificate: ${tls_certificate_sha256_expected}"
  echo "==> bootstrapped TLS SPKI: ${tls_spki_expected}"
fi
tls_certificate="${data_path}/tls/server.crt"

# Self-hosted app update: copy release APK + metadata into the data bind mount
# so lezi-sync can serve GET /v1/app-update and /v1/app-update/apk.
#
# Atomic pair publish: stage both files completely, then rename APK into place
# before metadata. That way a running service never observes "new min_supported
# + missing/old/broken APK" under the final paths (server also enforces the
# version floor only on a verified channel).
if [[ ! -f "${DIR}/app-update/app-release.apk" || ! -f "${DIR}/app-update/app-update.json" ]]; then
  echo "error: package missing app-update/app-release.apk or app-update/app-update.json" >&2
  echo "  repackage with package-nas.sh (fail-closed on release APK + metadata)" >&2
  exit 1
fi

# A running old server is the only endpoint that can prove the new APK before
# the replacement activates its raised floor. Snapshot the currently served
# pair first so any live-channel failure restores the prior floor and APK.
app_update_rollback_pending=0
restore_app_update_pair() {
  docker run --rm \
    --user 10001:10001 \
    -v "${data_path}:/data" \
    --entrypoint /bin/sh \
    "${image}" \
    -ec 'test -f /data/app-release.apk.lezi-rollback && test -f /data/app-update.json.lezi-rollback && cp /data/app-release.apk.lezi-rollback /data/app-release.apk.lezi-staging && cp /data/app-update.json.lezi-rollback /data/app-update.json.lezi-staging && mv -f /data/app-release.apk.lezi-staging /data/app-release.apk && mv -f /data/app-update.json.lezi-staging /data/app-update.json && rm -f /data/app-release.apk.lezi-rollback /data/app-update.json.lezi-rollback'
  app_update_rollback_pending=0
}
if [[ "${container_running}" == "1" ]]; then
  docker run --rm \
    --user 10001:10001 \
    -v "${data_path}:/data" \
    --entrypoint /bin/sh \
    "${image}" \
    -ec 'test -f /data/app-release.apk && test -f /data/app-update.json && rm -f /data/app-release.apk.lezi-rollback /data/app-update.json.lezi-rollback && cp /data/app-release.apk /data/app-release.apk.lezi-rollback && cp /data/app-update.json /data/app-update.json.lezi-rollback'
  app_update_rollback_pending=1
fi
echo "==> install app-update artifacts into ${data_path} (atomic pair: APK then metadata)"
# Data bind is often mode 700 uid 10001 (SSH user cannot write). Prefer direct
# install; fall back to docker as uid 10001 with a bind of the package app-update/.
app_update_apk_staging="${data_path}/app-release.apk.lezi-staging"
app_update_meta_staging="${data_path}/app-update.json.lezi-staging"
if mkdir -p "${data_path}" 2>/dev/null \
  && install -m 644 "${DIR}/app-update/app-release.apk" "${app_update_apk_staging}" 2>/dev/null \
  && install -m 644 "${DIR}/app-update/app-update.json" "${app_update_meta_staging}" 2>/dev/null \
  && mv -f "${app_update_apk_staging}" "${data_path}/app-release.apk" \
  && mv -f "${app_update_meta_staging}" "${data_path}/app-update.json"; then
  if command -v chown >/dev/null 2>&1; then
    chown 10001:10001 "${data_path}/app-update.json" "${data_path}/app-release.apk" 2>/dev/null || true
  fi
else
  # Clean any partial direct staging before the docker fallback.
  rm -f -- "${app_update_apk_staging}" "${app_update_meta_staging}" 2>/dev/null || true
  echo "    direct install not writable; docker-copy as 10001:10001 (atomic pair)" >&2
  docker run --rm \
    --user 10001:10001 \
    -v "${data_path}:/data" \
    -v "${DIR}/app-update:/src:ro" \
    --entrypoint /bin/sh \
    "${image}" \
    -ec 'cp /src/app-release.apk /data/app-release.apk.lezi-staging && cp /src/app-update.json /data/app-update.json.lezi-staging && mv -f /data/app-release.apk.lezi-staging /data/app-release.apk && mv -f /data/app-update.json.lezi-staging /data/app-update.json && chmod 644 /data/app-update.json /data/app-release.apk'
fi
# Fail closed: final artifacts must exist under the bind (via docker stat as 10001),
# and no staging leftovers may remain as the live names.
docker run --rm \
  --user 10001:10001 \
  -v "${data_path}:/data:ro" \
  --entrypoint /bin/sh \
  "${image}" \
  -ec 'test -f /data/app-update.json && test -f /data/app-release.apk && test ! -e /data/app-update.json.lezi-staging && test ! -e /data/app-release.apk.lezi-staging'

# The old container remains live until this point. Prove its anonymous LAN
# install channel can actually serve the just-published APK before activating
# the new server's forced-update floor and protocol capability.
if [[ "${container_running}" == "1" ]]; then
  expected_app_update_sha256="$(sha256sum "${DIR}/app-update/app-release.apk" | awk '{print $1}')"
  if ! served_app_update_sha256="$(curl -fsS --max-time 30 http://127.0.0.1:8767/download/lezi.apk | sha256sum | awk '{print $1}')"; then
    restore_app_update_pair
    echo "error: live LAN install channel could not serve the packaged APK; prior update pair restored" >&2
    exit 1
  fi
  if [[ "${served_app_update_sha256}" != "${expected_app_update_sha256}" ]]; then
    restore_app_update_pair
    echo "error: live LAN install channel did not serve the packaged APK; prior update pair restored" >&2
    exit 1
  fi
  docker run --rm \
    --user 10001:10001 \
    -v "${data_path}:/data" \
    --entrypoint /bin/sh \
    "${image}" \
    -ec 'rm -f /data/app-release.apk.lezi-rollback /data/app-update.json.lezi-rollback'
  app_update_rollback_pending=0
  echo "==> live LAN install channel verified before protocol cutover: ${served_app_update_sha256}"
fi

echo "==> stop/remove existing container ${CONTAINER_NAME} (data bind kept)"
if docker inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
  if ! docker stop "${CONTAINER_NAME}" >/dev/null; then
    echo "error: failed to stop existing container ${CONTAINER_NAME}; replacement aborted" >&2
    exit 1
  fi
  if ! docker rm "${CONTAINER_NAME}" >/dev/null; then
    echo "error: failed to remove stopped container ${CONTAINER_NAME}; replacement aborted" >&2
    exit 1
  fi
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
  LEZI_BOOTSTRAP_SECRET="${secret}" \
    "${ZDOCKER_COMPOSE}" -f docker-compose.yml -p "${COMPOSE_PROJECT}" up -d
else
  # Fallback mirrors the nas compose (keep in sync with template).
  LEZI_BOOTSTRAP_SECRET="${secret}" docker run -d \
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
    -e LEZI_INTERNAL_PORT=8766 \
    -e "LEZI_LAN_APK_DOWNLOAD_ORIGIN=${LAN_APK_DOWNLOAD_ORIGIN}" \
    -e LEZI_TLS_CERTFILE=/data/tls/server.crt \
    -e LEZI_TLS_KEYFILE=/data/tls/server.key \
    -e LEZI_MAX_MEDIA_BYTES=10485760 \
    -e LEZI_CREATE_RATE_LIMIT=20 \
    -e LEZI_MEMBER_REQUEST_RATE_LIMIT=10 \
    -e LEZI_MEMBER_REQUEST_TTL_HOURS=24 \
    -e LEZI_MAX_PENDING_MEMBER_REQUESTS=32 \
    -e LEZI_RATE_LIMIT_WINDOW_SECONDS=60 \
    -e LEZI_ALLOW_PERMISSION_HARDENING_SKIP=0 \
    -e LEZI_BOOTSTRAP_SECRET \
    -v "${data_path}:/data" \
    -p 0.0.0.0:8765:8765 \
    -p 0.0.0.0:8767:8767 \
    "${image}"
fi

echo "==> wait for health"
ok=0
# Image is debian:bookworm-slim + lezi-sync only (no curl). Prefer host curl with
# data-bind cert; if the bind is mode 700 (uid 10001 only), use host curl -k on
# published HTTPS :8765 for JSON bodies, and `lezi-sync healthcheck` inside the
# container for internal :8766 /ready (container-local only; not published).
for i in $(seq 1 30); do
  if [[ -r "${tls_certificate}" ]] \
    && curl --cacert "${tls_certificate}" -fsS "${HEALTH_URL}" >/tmp/lezi-health.out 2>/dev/null \
    && curl --cacert "${tls_certificate}" -fsS "${READY_URL}" >/tmp/lezi-ready.out 2>/dev/null; then
    ok=1
    break
  fi
  # Host LAN HTTPS without readable cert (mode-700 data bind).
  if curl -k -fsS "https://127.0.0.1:8765/health" >/tmp/lezi-health.out 2>/dev/null \
    && curl -k -fsS "https://127.0.0.1:8765/ready" >/tmp/lezi-ready.out 2>/dev/null; then
    ok=1
    break
  fi
  # Container-internal readiness only (no JSON body; image has no curl).
  if docker exec "${CONTAINER_NAME}" lezi-sync healthcheck >/dev/null 2>&1; then
    # Still need version JSON for EXPECTED_VERSION check — retry host HTTPS briefly.
    if curl -k -fsS "https://127.0.0.1:8765/health" >/tmp/lezi-health.out 2>/dev/null \
      && curl -k -fsS "https://127.0.0.1:8765/ready" >/tmp/lezi-ready.out 2>/dev/null; then
      ok=1
      break
    fi
  fi
  sleep 1
done

if [[ "${ok}" -ne 1 ]]; then
  echo "error: health/ready failed" >&2
  docker ps -a --filter "name=^/${CONTAINER_NAME}$" || true
  docker logs --tail 80 "${CONTAINER_NAME}" 2>&1 || true
  exit 1
fi

running_image_id="$(docker inspect "${CONTAINER_NAME}" --format '{{.Image}}')"
if [[ "${running_image_id}" != "${expected_image_id}" ]]; then
  echo "error: running container image id does not match MANIFEST.json" >&2
  echo "  expected: ${expected_image_id}" >&2
  echo "  actual:   ${running_image_id:-missing}" >&2
  exit 1
fi
echo "==> running container image identity validated: ${running_image_id}"

echo "==> health: $(cat /tmp/lezi-health.out)"
echo "==> ready:  $(cat /tmp/lezi-ready.out)"

if ! grep -Fq "\"version\":\"${EXPECTED_VERSION}\"" /tmp/lezi-health.out \
  && ! grep -Fq "\"version\": \"${EXPECTED_VERSION}\"" /tmp/lezi-health.out; then
  echo "error: /health version did not match expected ${EXPECTED_VERSION}" >&2
  cat /tmp/lezi-health.out >&2 || true
  exit 1
fi

"${DIR}/tls-spki.sh" "${data_path}" "${image}" "${tls_spki_expected}" >/dev/null
"${DIR}/tls-certificate-sha256.sh" \
  "${data_path}" "${image}" "${tls_certificate_sha256_expected}" >/dev/null
echo "==> post-replace TLS certificate preserved: ${tls_certificate_sha256_expected}"
echo "==> post-replace TLS SPKI preserved: ${tls_spki_expected}"

docker ps --filter "name=^/${CONTAINER_NAME}$" --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
echo "==> deploy ok"
