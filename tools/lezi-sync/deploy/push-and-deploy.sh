#!/usr/bin/env bash
# From the dev machine: package (optional) → scp → SSH remote-deploy on NAS.
# Decisions:
#   - SSH automatic deploy
#   - prefer zdocker bundled docker-compose on NAS
#   - back up credentials, then validate live/persistent bootstrap sources
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SYNC_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${SYNC_ROOT}/../.." && pwd)"

version="${LEZI_SYNC_VERSION:-}"
if [[ -z "${version}" ]]; then
  version="$(sed -n 's/^version = "\([^"]*\)"/\1/p' "${SYNC_ROOT}/Cargo.toml" | head -1)"
fi
if [[ ! "${version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$ ]]; then
  echo "error: LEZI_SYNC_VERSION is outside the release-version contract" >&2
  exit 1
fi

NAS_SSH="${NAS_SSH:-13096920600@192.168.50.4}"
NAS_SSH_PORT="${NAS_SSH_PORT:-10000}"
# Zspace SSH users often have HOME=/home/ (not writable). Default to /tmp.
NAS_REMOTE_DIR="${NAS_REMOTE_DIR:-/tmp/lezi-sync-releases/lezi-sync-${version}-nas}"
PACKAGE_DIR="${LEZI_NAS_PACKAGE_DIR:-${REPO_ROOT}/dist/lezi-sync-${version}-nas}"
SKIP_PACKAGE="${LEZI_SKIP_PACKAGE:-0}"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -p "${NAS_SSH_PORT}")
allow_tls_bootstrap="${LEZI_ALLOW_TLS_BOOTSTRAP:-0}"
allow_secret_recovery="${LEZI_ALLOW_SECRET_RECOVERY:-0}"
allow_secret_reseed="${LEZI_ALLOW_SECRET_RESEED:-0}"
forward_bootstrap_secret="${LEZI_FORWARD_BOOTSTRAP_SECRET:-0}"

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
if [[ "$(basename -- "${NAS_REMOTE_DIR}")" != "lezi-sync-${version}-nas" ]]; then
  echo "error: NAS_REMOTE_DIR basename must be lezi-sync-${version}-nas" >&2
  exit 1
fi
case "${NAS_REMOTE_DIR}" in
  /|/tmp|/var|/var/tmp|/home|/root|/tmp/lezi-sync-releases)
    echo "error: refusing a broad NAS_REMOTE_DIR target" >&2
    exit 1
    ;;
esac

if [[ "${allow_tls_bootstrap}" != "0" && "${allow_tls_bootstrap}" != "1" ]]; then
  echo "error: LEZI_ALLOW_TLS_BOOTSTRAP must be 0 or 1" >&2
  exit 1
fi
if [[ "${allow_secret_recovery}" != "0" && "${allow_secret_recovery}" != "1" ]]; then
  echo "error: LEZI_ALLOW_SECRET_RECOVERY must be 0 or 1" >&2
  exit 1
fi
if [[ "${allow_secret_reseed}" != "0" && "${allow_secret_reseed}" != "1" ]]; then
  echo "error: LEZI_ALLOW_SECRET_RESEED must be 0 or 1" >&2
  exit 1
fi
if [[ "${forward_bootstrap_secret}" != "0" && "${forward_bootstrap_secret}" != "1" ]]; then
  echo "error: LEZI_FORWARD_BOOTSTRAP_SECRET must be 0 or 1" >&2
  exit 1
fi
if [[ "${allow_secret_recovery}" == "1" \
    && ( "${forward_bootstrap_secret}" == "1" \
      || "${allow_secret_reseed}" == "1" \
      || "${allow_tls_bootstrap}" == "1" ) ]]; then
  echo "error: secret recovery cannot be combined with secret forwarding/reseed or TLS bootstrap" >&2
  exit 1
fi
if [[ "${allow_secret_reseed}" == "1" \
    && "${forward_bootstrap_secret}" != "1" ]]; then
  echo "error: LEZI_ALLOW_SECRET_RESEED=1 requires LEZI_FORWARD_BOOTSTRAP_SECRET=1" >&2
  exit 1
fi
if [[ "${allow_tls_bootstrap}" == "1" \
    && "${forward_bootstrap_secret}" != "1" ]]; then
  echo "error: LEZI_ALLOW_TLS_BOOTSTRAP=1 requires an explicit LEZI_FORWARD_BOOTSTRAP_SECRET=1 fresh-deployment secret" >&2
  exit 1
fi
printf -v version_q '%q' "${version}"
remote_env_prefix="LEZI_SYNC_VERSION=${version_q} "
if [[ "${allow_tls_bootstrap}" == "1" ]]; then
  remote_env_prefix+="LEZI_ALLOW_TLS_BOOTSTRAP=1 "
fi
if [[ "${allow_secret_recovery}" == "1" ]]; then
  remote_env_prefix+="LEZI_ALLOW_SECRET_RECOVERY=1 "
fi
if [[ "${allow_secret_reseed}" == "1" ]]; then
  remote_env_prefix+="LEZI_ALLOW_SECRET_RESEED=1 "
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

echo "==> push-and-deploy lezi-sync ${version}"
echo "    nas:     ${NAS_SSH} port ${NAS_SSH_PORT}"
echo "    package: ${PACKAGE_DIR}"
echo "    remote:  ${NAS_REMOTE_DIR}"

guarded_package_files=(
  .env.example
  credential-deploy-lock.sh
  docker-compose.nas.yml.tpl
  export-nas-credentials.sh
  init-tls.sh
  promote-nas-package.sh
  remote-deploy.sh
  tls-certificate-sha256.sh
  tls-spki.sh
  validate-nas-package.sh
)
package_guards_match_current() {
  local file
  for file in "${guarded_package_files[@]}"; do
    if [[ ! -f "${PACKAGE_DIR}/${file}" \
        || ! -f "${SCRIPT_DIR}/${file}" ]] \
        || ! cmp -s "${SCRIPT_DIR}/${file}" "${PACKAGE_DIR}/${file}"; then
      return 1
    fi
  done
}

if [[ "${SKIP_PACKAGE}" != "1" ]]; then
  echo "==> build a fresh package from the currently inspectable local image"
  LEZI_SYNC_VERSION="${version}" \
  LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=0 \
    "${SCRIPT_DIR}/package-nas.sh"
fi

if [[ ! -d "${PACKAGE_DIR}" ]]; then
  echo "error: package dir missing: ${PACKAGE_DIR}" >&2
  echo "  run: ${SCRIPT_DIR}/package-nas.sh" >&2
  exit 1
fi

validate_guarded_package() {
  if [[ ! -f "${PACKAGE_DIR}/SHA256SUMS" ]] \
      || ! package_guards_match_current; then
    echo "error: package is missing the current credential/deploy guards or SHA256SUMS" >&2
    return 1
  fi
  if ! "${SCRIPT_DIR}/validate-nas-package.sh" \
      "${PACKAGE_DIR}" "${version}" >/dev/null; then
    echo "error: exact NAS package validation failed" >&2
    return 1
  fi
}
if ! validate_guarded_package; then
  if [[ "${SKIP_PACKAGE}" == "1" ]]; then
    echo "error: LEZI_SKIP_PACKAGE skips rebuilding only; it never skips deploy-guard validation" >&2
  else
    echo "error: rebuild the NAS package before deployment" >&2
  fi
  exit 1
fi
echo "==> current deploy helpers and package checksums validated"

local_manifest_string() {
  local key="$1"
  local -a values=()
  mapfile -t values < <(
    sed -nE \
      "s/^[[:space:]]*\"${key}\"[[:space:]]*:[[:space:]]*\"([^\"]*)\"[[:space:]]*,?[[:space:]]*$/\\1/p" \
      "${PACKAGE_DIR}/MANIFEST.json"
  )
  if [[ "${#values[@]}" -ne 1 || -z "${values[0]}" ]]; then
    echo "error: package manifest must contain exactly one ${key}" >&2
    exit 1
  fi
  printf '%s' "${values[0]}"
}

manifest_image="$(local_manifest_string image)"
manifest_image_id="$(local_manifest_string image_id)"
manifest_image_os="$(local_manifest_string os)"
manifest_image_architecture="$(local_manifest_string architecture)"
manifest_data_host_path="$(local_manifest_string data_host_path)"
manifest_tls_host="$(local_manifest_string tls_host)"
manifest_lan_apk_download_origin="$(local_manifest_string lan_apk_download_origin)"
expected_data_host_path="${LEZI_DATA_HOST_PATH:-/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data}"
expected_tls_host="${LEZI_TLS_HOST:-192.168.50.4}"
expected_lan_apk_download_origin="${LEZI_LAN_APK_DOWNLOAD_ORIGIN:-http://${expected_tls_host}:8767}"
if [[ "${manifest_data_host_path}" != "${expected_data_host_path}" \
    || "${manifest_tls_host}" != "${expected_tls_host}" \
    || "${manifest_lan_apk_download_origin}" != "${expected_lan_apk_download_origin}" ]]; then
  echo "error: packaged data/TLS/invite origin does not match the requested deployment inputs" >&2
  echo "  rebuild without LEZI_SKIP_PACKAGE, or pass the exact deliberate package inputs" >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1 \
    || ! docker image inspect "${manifest_image}" >/dev/null 2>&1; then
  echo "error: the package's image must be locally inspectable before push, including LEZI_SKIP_PACKAGE reuse" >&2
  exit 1
fi
local_image_id="$("${SCRIPT_DIR}/image-config-digest.sh" "${manifest_image}")"
local_image_os="$(docker image inspect "${manifest_image}" --format '{{.Os}}')"
local_image_architecture="$(docker image inspect "${manifest_image}" --format '{{.Architecture}}')"
if [[ "${local_image_id}" != "${manifest_image_id}" \
    || "${local_image_os}" != "${manifest_image_os}" \
    || "${local_image_architecture}" != "${manifest_image_architecture}" \
    || "${local_image_os}" != "linux" \
    || "${local_image_architecture}" != "amd64" ]]; then
  echo "error: packaged image identity/platform does not match the local inspectable linux/amd64 image" >&2
  exit 1
fi
echo "==> package image and deployment inputs re-attested against local authority"

# `LEZI_SKIP_PACKAGE=1` skips only rebuilding. It does not trust a package's
# self-declared signer: re-verify the staged APK against the repository's
# tracked public release-certificate pin before any NAS write.
release_signer_pin_file="${REPO_ROOT}/config/release-apk-signer-sha256.txt"
if [[ ! -f "${release_signer_pin_file}" || -L "${release_signer_pin_file}" ]]; then
  echo "error: tracked release APK signer pin is missing or unsafe" >&2
  exit 1
fi
mapfile -t release_signer_pin_lines <"${release_signer_pin_file}"
if [[ "${#release_signer_pin_lines[@]}" -ne 1 \
    || ! "${release_signer_pin_lines[0]}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "error: tracked release APK signer pin must be one lowercase SHA-256 digest" >&2
  exit 1
fi
expected_release_signer_sha256="${release_signer_pin_lines[0]}"
manifest_release_signer_sha256="$(
  sed -nE \
    's/^[[:space:]]*"apk_signer_certificate_sha256"[[:space:]]*:[[:space:]]*"([0-9a-f]{64})"[[:space:]]*,?[[:space:]]*$/\1/p' \
    "${PACKAGE_DIR}/MANIFEST.json"
)"
if [[ "${manifest_release_signer_sha256}" != "${expected_release_signer_sha256}" ]]; then
  echo "error: package manifest APK signer does not match the tracked Lezi release signer" >&2
  exit 1
fi
package_apk_signer="${LEZI_APK_SIGNER:-}"
if [[ -z "${package_apk_signer}" ]]; then
  signer_sdk_dir="$(sed -n 's/^sdk.dir=//p' "${REPO_ROOT}/local.properties" 2>/dev/null | head -1)"
  if [[ -z "${signer_sdk_dir}" || ! -d "${signer_sdk_dir}/build-tools" ]]; then
    signer_sdk_dir="${ANDROID_SDK_ROOT:-}"
  fi
  if [[ -n "${signer_sdk_dir}" && -d "${signer_sdk_dir}/build-tools" ]]; then
    package_apk_signer="$(
      find "${signer_sdk_dir}/build-tools" -mindepth 2 -maxdepth 2 \
        -type f -name apksigner -perm -u+x -print 2>/dev/null \
        | sort -V \
        | tail -1 \
        || true
    )"
  else
    package_apk_signer="$(command -v apksigner || true)"
  fi
fi
if [[ -z "${package_apk_signer}" || ! -x "${package_apk_signer}" ]]; then
  echo "error: apksigner is required to re-attest the packaged release APK" >&2
  exit 1
fi
if ! packaged_signer_output="$(
  "${package_apk_signer}" verify --verbose --print-certs \
    "${PACKAGE_DIR}/app-update/app-release.apk" 2>&1
)"; then
  echo "error: packaged release APK signature verification failed" >&2
  exit 1
fi
mapfile -t packaged_signer_sha256_values < <(
  printf '%s\n' "${packaged_signer_output}" \
    | sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: ([0-9A-Fa-f]{64})$/\L\1/p'
)
if [[ "${#packaged_signer_sha256_values[@]}" -ne 1 \
    || "${packaged_signer_sha256_values[0]}" != "${expected_release_signer_sha256}" ]]; then
  echo "error: packaged APK certificate does not match the tracked Lezi release signer" >&2
  exit 1
fi
echo "==> packaged APK release signer re-attested"
echo "==> re-attest packaged APK identity/version/local-data contract and metadata"
LEZI_SYNC_VERSION="${version}" \
LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1 \
LEZI_RELEASE_APK="${PACKAGE_DIR}/app-update/app-release.apk" \
LEZI_APP_UPDATE_JSON="${PACKAGE_DIR}/app-update/app-update.json" \
LEZI_NAS_PACKAGE_DIR="${REPO_ROOT}/dist/lezi-sync-${version}-nas" \
  "${SCRIPT_DIR}/package-nas.sh" >/dev/null

# Hold one NAS-side lease across scp, the pre-replace snapshot, replacement,
# and any deferred post-recovery snapshot. This closes the cross-SSH gap where
# two otherwise safe push workflows could interleave their backup/deploy phases.
if [[ ! -x "${SCRIPT_DIR}/credential-deploy-lock.sh" ]]; then
  echo "error: local credential-deploy-lock.sh is missing or not executable" >&2
  exit 1
fi
package_data_path="$(
  grep -E '^[[:space:]]*-[[:space:]]+[^#]+:/data' \
    "${PACKAGE_DIR}/docker-compose.yml" \
    | head -1 \
    | sed -E 's/^[[:space:]]*-[[:space:]]+//; s,:/data.*,,; s/[[:space:]]*$//'
)"
if [[ -z "${package_data_path}" || "${package_data_path}" != /* \
    || "${package_data_path}" == "/" ]]; then
  echo "error: validated package does not expose a safe absolute /data bind source" >&2
  exit 1
fi
# The lease is tied to this NAS instance's data bind, never to an overridable
# secret path. Two deploys targeting the same container cannot select different
# lock domains by changing LEZI_SECRET_FILE.
deploy_lock_dir="$(dirname -- "${package_data_path}")/config/.lezi-sync-credential-deploy.lock"
if [[ "${deploy_lock_dir}" != /* \
    || ! "${deploy_lock_dir}" =~ ^/[A-Za-z0-9._/-]+$ \
    || "${deploy_lock_dir}" == *'//'* \
    || "${deploy_lock_dir}" == */./* \
    || "${deploy_lock_dir}" == */../* \
    || "${deploy_lock_dir}" == */. \
    || "${deploy_lock_dir}" == */.. ]]; then
  echo "error: derived credential/deploy lock path is outside the normalized NAS path contract" >&2
  exit 1
fi
deploy_lock_token="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
if [[ ! "${deploy_lock_token}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "error: could not generate the credential/deploy lease token" >&2
  exit 1
fi
printf -v deploy_lock_dir_q '%q' "${deploy_lock_dir}"
printf -v deploy_lock_token_q '%q' "${deploy_lock_token}"

deploy_lock_acquired=0
release_deploy_lock() {
  local original_status=$?
  trap - EXIT
  if [[ "${deploy_lock_acquired}" == "1" ]]; then
    if ! ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
        "bash -s -- release ${deploy_lock_dir_q} ${deploy_lock_token_q}" \
        <"${SCRIPT_DIR}/credential-deploy-lock.sh"; then
      echo "error: failed to release the NAS credential/deploy lease; inspect it before retrying" >&2
      if [[ "${original_status}" -eq 0 ]]; then
        original_status=1
      fi
    fi
  fi
  exit "${original_status}"
}
trap release_deploy_lock EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

echo "==> acquire NAS credential/deploy lease before package transfer"
ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
  "bash -s -- acquire ${deploy_lock_dir_q} ${deploy_lock_token_q}" \
  <"${SCRIPT_DIR}/credential-deploy-lock.sh"
deploy_lock_acquired=1
remote_env_prefix+="LEZI_DEPLOY_LOCK_TOKEN=${deploy_lock_token} "

remote_package_dir="${NAS_REMOTE_DIR}.incoming-${deploy_lock_token}"
printf -v remote_package_dir_q '%q' "${remote_package_dir}"
printf -v stable_remote_dir_q '%q' "${NAS_REMOTE_DIR}"
remote_package_parent="$(dirname -- "${NAS_REMOTE_DIR}")"
printf -v remote_package_parent_q '%q' "${remote_package_parent}"
echo "==> create a fresh mode-700 NAS package staging directory"
ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
  "umask 077; if test ! -e ${remote_package_parent_q}; then mkdir -m 700 -- ${remote_package_parent_q}; fi; test -d ${remote_package_parent_q} && test ! -L ${remote_package_parent_q} && test \"\$(realpath -e ${remote_package_parent_q})\" = ${remote_package_parent_q} && test \"\$(stat -c %u ${remote_package_parent_q})\" = \"\$(id -u)\" && test \"\$(stat -c %a ${remote_package_parent_q})\" = 700 && mkdir -m 700 -- ${remote_package_dir_q}"

echo "==> scp package → NAS"
# Trailing slash: copy contents into remote dir
scp -P "${NAS_SSH_PORT}" -o BatchMode=yes -o ConnectTimeout=15 -r \
  "${PACKAGE_DIR}/." "${NAS_SSH}:${remote_package_dir}/"
ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
  "cd ${remote_package_dir_q} && chmod +x validate-nas-package.sh && ./validate-nas-package.sh . ${version_q} >/dev/null"
echo "==> remote package exact inventory, manifest, and checksums validated"
ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
  "${remote_package_dir_q}/promote-nas-package.sh preflight ${remote_package_dir_q} ${stable_remote_dir_q} ${version_q} >/dev/null"
echo "==> stable NAS package target is absent or an exact prior package"

run_credential_backup() {
  NAS_SSH="${NAS_SSH}" \
  NAS_SSH_PORT="${NAS_SSH_PORT}" \
  NAS_REMOTE_DIR="${remote_package_dir}" \
  LEZI_SYNC_VERSION="${version}" \
  LEZI_DEPLOY_LOCK_TOKEN="${deploy_lock_token}" \
    "${SCRIPT_DIR}/backup-nas-credentials.sh"
}

echo "==> encrypted credential backup before container replacement"
backup_after_deploy=0
if run_credential_backup; then
  echo "==> pre-replace encrypted credential backup complete"
else
  backup_status=$?
  if [[ "${backup_status}" -eq 3 \
      && ( "${forward_bootstrap_secret}" == "1" \
        || "${allow_secret_recovery}" == "1" ) ]]; then
    backup_after_deploy=1
    echo "==> no live container to back up; authorized flow requires backup immediately after deploy"
  else
    echo "error: pre-replace encrypted credential backup failed; container was not replaced" >&2
    exit "${backup_status}"
  fi
fi

echo "==> ssh remote-deploy.sh"
# Ordinary CD: do NOT forward a local LEZI_BOOTSTRAP_SECRET over SSH. remote-deploy
# treats the live container as authority and requires the persistent file to match.
# A leftover local secret
# (cutover dry-run, shell profile, prior window) must not silently win over live
# inherit — that rewrites families.owner_root_fingerprint and revokes owner devices.
#
# Explicit no-live paths never inherit. A verified fresh deployment sets the
# secret + forward + TLS-bootstrap authorization. Offline-migrate cutover sets
# those three plus secret-reseed, and the secret MUST be the migration-time new
# root password. Unset the secret and every authorization immediately after the
# one-time flow so ordinary CD returns to live + persistent validation.
if [[ "${forward_bootstrap_secret}" == "1" ]]; then
  if [[ -z "${LEZI_BOOTSTRAP_SECRET:-}" ]]; then
    echo "error: LEZI_FORWARD_BOOTSTRAP_SECRET=1 requires LEZI_BOOTSTRAP_SECRET (≥16 chars)" >&2
    exit 1
  fi
  if [[ "${#LEZI_BOOTSTRAP_SECRET}" -lt 16 ]]; then
    echo "error: LEZI_BOOTSTRAP_SECRET must be at least 16 characters when set" >&2
    exit 1
  fi
  echo "==> WARNING: forwarding LEZI_BOOTSTRAP_SECRET to remote-deploy (opt-in LEZI_FORWARD_BOOTSTRAP_SECRET=1)"
  echo "    This sets remote container env from the local secret (not live-container inherit)."
  echo "    If the secret differs from the previous live env, startup reconcile_owner_root_fingerprint"
  echo "    rewrites families.owner_root_fingerprint and revokes owner devices (root_password_rotated)."
  echo "    Intended only for a verified fresh deployment or authorized offline-migrate cutover."
  echo "    Fresh: unset secret/forward/TLS-bootstrap afterward; cutover: unset all four maintenance variables."
  echo "    (secret value not printed)"
  # Keep the secret out of SSH argv, process listings, and shell audit logs.
  printf '%s\n' "${LEZI_BOOTSTRAP_SECRET}" \
    | ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
      "cd ${remote_package_dir_q} && chmod +x remote-deploy.sh && ${remote_env_prefix}LEZI_BOOTSTRAP_SECRET_STDIN=1 ./remote-deploy.sh"
else
  if [[ -n "${LEZI_BOOTSTRAP_SECRET:-}" ]]; then
    echo "==> LEZI_BOOTSTRAP_SECRET is set locally but NOT forwarded (ordinary CD inherit path)"
    echo "    set LEZI_FORWARD_BOOTSTRAP_SECRET=1 only for the documented verified fresh/cutover flow"
  else
    echo "==> LEZI_BOOTSTRAP_SECRET unset; remote-deploy may inherit from live container"
  fi
  if [[ "${allow_tls_bootstrap}" == "1" ]]; then
    echo "==> WARNING: authorizing one-time TLS identity generation on a verified fresh data root"
  fi
  echo "    (fresh deployment requires secret+forward+TLS-bootstrap; offline cutover requires those plus secret-reseed)"
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
    "cd ${remote_package_dir_q} && chmod +x remote-deploy.sh && ${remote_env_prefix}./remote-deploy.sh"
fi

if [[ "${backup_after_deploy}" == "1" ]]; then
  echo "==> encrypted credential backup after first/recovery deployment"
  run_credential_backup
fi

echo "==> promote validated staging package to stable NAS path"
ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
  "${remote_package_dir_q}/promote-nas-package.sh promote ${remote_package_dir_q} ${stable_remote_dir_q} ${version_q} >/dev/null"
echo "==> retained validated NAS package: ${NAS_REMOTE_DIR}"
echo "==> done"
