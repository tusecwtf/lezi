#!/usr/bin/env bash
# Production phase owner for schema-cutover.sh. Each invocation runs one named phase.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SYNC_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${SYNC_ROOT}/../.." && pwd)"
phase="${1:-}"
state_dir="${LEZI_SCHEMA_CUTOVER_STATE_DIR:?}"

NAS_SSH="${NAS_SSH:-13096920600@192.168.50.4}"
NAS_SSH_PORT="${NAS_SSH_PORT:-10000}"
DATA_PATH="${LEZI_DATA_HOST_PATH:-/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data}"
CONTAINER_NAME="${LEZI_CONTAINER_NAME:-lezi-sync}"
PACKAGE_DIR="${LEZI_NAS_PACKAGE_DIR:-${REPO_ROOT}/dist/lezi-sync-0.4.0-nas}"
ROLLBACK_PACKAGE_DIR="${LEZI_SCHEMA_CUTOVER_ROLLBACK_PACKAGE_DIR:-}"
WORK_DIR="${LEZI_SCHEMA_CUTOVER_WORK_DIR:-${state_dir}/work}"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -p "${NAS_SSH_PORT}")

die() {
  echo "error: schema cutover ${phase}: $*" >&2
  exit 1
}

require_safe_path() {
  local name="$1" value="$2"
  if [[ "${value}" != /* || ! "${value}" =~ ^/[A-Za-z0-9._/-]+$ \
      || "${value}" == *'//'* || "${value}" == */./* || "${value}" == */../* \
      || "${value}" == */. || "${value}" == */.. ]]; then
    die "${name} must be a normalized absolute path"
  fi
  case "${value}" in
    /|/tmp|/var|/var/tmp|/home|/root) die "${name} is too broad" ;;
  esac
}

require_safe_path LEZI_DATA_HOST_PATH "${DATA_PATH}"
require_safe_path LEZI_SCHEMA_CUTOVER_STATE_DIR "${state_dir}"
if [[ ! "${CONTAINER_NAME}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]]; then
  die "LEZI_CONTAINER_NAME contains unsupported characters"
fi

manifest_value() {
  local file="$1" key="$2"
  python3 - "${file}" "${key}" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as stream:
    value = json.load(stream).get(sys.argv[2])
if not isinstance(value, (str, int)) or isinstance(value, bool):
    raise SystemExit(1)
print(value)
PY
}

attested_cutover_source() {
  case "$1:$2" in
    0.3.12:11|0.3.13:12) return 0 ;;
    *) return 1 ;;
  esac
}

rollback_source_version() {
  manifest_value "${state_dir}/rollback-package-manifest.json" version
}

rollback_source_schema() {
  manifest_value "${state_dir}/rollback-package-manifest.json" server_schema
}

rollback_source_image() {
  manifest_value "${state_dir}/rollback-package-manifest.json" image
}

require_attested_rollback_identity() {
  local version schema image
  version="$(rollback_source_version)"
  schema="$(rollback_source_schema)"
  image="$(rollback_source_image)"
  attested_cutover_source "${version}" "${schema}" \
    || die "rollback package must be attested 0.3.12/schema 11 or 0.3.13/schema 12"
  [[ "${image}" == "lezi-sync:${version}" ]] \
    || die "rollback package image must be lezi-sync:${version}"
}

resolve_sync_bin() {
  if [[ -n "${LEZI_SYNC_BIN:-}" && -x "${LEZI_SYNC_BIN}" ]]; then
    printf '%s' "${LEZI_SYNC_BIN}"
  elif [[ -x "${SYNC_ROOT}/target/debug/lezi-sync" ]]; then
    printf '%s' "${SYNC_ROOT}/target/debug/lezi-sync"
  elif command -v lezi-sync >/dev/null 2>&1; then
    command -v lezi-sync
  else
    die "set LEZI_SYNC_BIN to the current 0.4.0 lezi-sync binary"
  fi
}

operation_id_file="${state_dir}/operation-id"
operation_id() {
  [[ -f "${operation_id_file}" ]] || die "operation id is missing"
  local value
  value="$(<"${operation_id_file}")"
  [[ "${value}" =~ ^[0-9a-f]{32}$ ]] || die "operation id is invalid"
  printf '%s' "${value}"
}

remote_stage() {
  printf '/tmp/lezi-schema-cutover-%s' "$(operation_id)"
}

data_parent="$(dirname -- "${DATA_PATH}")"
lock_dir="${data_parent}/config/.lezi-sync-credential-deploy.lock"
update_lock_dir="${lock_dir}.app-update"

package_preflight() {
  [[ -d "${PACKAGE_DIR}" && ! -L "${PACKAGE_DIR}" ]] || die "0.4.0 package is missing"
  LEZI_SYNC_VERSION=0.4.0 LEZI_NAS_PACKAGE_DIR="${PACKAGE_DIR}" \
  LEZI_SKIP_PACKAGE=1 LEZI_DEPLOY_PREFLIGHT_ONLY=1 \
    "${SCRIPT_DIR}/push-and-deploy.sh" >/dev/null
  [[ "$(manifest_value "${PACKAGE_DIR}/MANIFEST.json" server_schema)" == 13 ]] \
    || die "target package server_schema is not 13"
  [[ "$(manifest_value "${PACKAGE_DIR}/MANIFEST.json" android_version_code)" == 21 ]] \
    || die "target package Android versionCode is not 21"
  [[ "$(manifest_value "${PACKAGE_DIR}/MANIFEST.json" minimum_supported_version_code)" == 21 ]] \
    || die "target package minimum supported code is not 21"

  [[ -n "${ROLLBACK_PACKAGE_DIR}" && -d "${ROLLBACK_PACKAGE_DIR}" \
      && ! -L "${ROLLBACK_PACKAGE_DIR}" ]] || die "LEZI_SCHEMA_CUTOVER_ROLLBACK_PACKAGE_DIR is required"
  rollback_version="$(manifest_value "${ROLLBACK_PACKAGE_DIR}/MANIFEST.json" version)"
  rollback_schema="$(manifest_value "${ROLLBACK_PACKAGE_DIR}/MANIFEST.json" server_schema)"
  rollback_image="$(manifest_value "${ROLLBACK_PACKAGE_DIR}/MANIFEST.json" image)"
  attested_cutover_source "${rollback_version}" "${rollback_schema}" \
    || die "rollback package must be attested 0.3.12/schema 11 or 0.3.13/schema 12"
  [[ "${rollback_image}" == "lezi-sync:${rollback_version}" ]] \
    || die "rollback package image must be lezi-sync:${rollback_version}"
  LEZI_SYNC_VERSION="${rollback_version}" LEZI_NAS_PACKAGE_DIR="${ROLLBACK_PACKAGE_DIR}" \
  LEZI_SKIP_PACKAGE=1 LEZI_DEPLOY_PREFLIGHT_ONLY=1 \
    "${SCRIPT_DIR}/push-and-deploy.sh" >/dev/null
  rollback_image_id="$(manifest_value "${ROLLBACK_PACKAGE_DIR}/MANIFEST.json" image_id)"
  [[ "${rollback_image_id}" =~ ^sha256:[0-9a-f]{64}$ ]] \
    || die "rollback image id is invalid"

  umask 077
  od -An -N16 -tx1 /dev/urandom | tr -d ' \n' >"${operation_id_file}"
  cp -- "${ROLLBACK_PACKAGE_DIR}/MANIFEST.json" "${state_dir}/rollback-package-manifest.json"
  cp -- "${ROLLBACK_PACKAGE_DIR}/SHA256SUMS" "${state_dir}/rollback-package-SHA256SUMS"
  sha256sum "${state_dir}/rollback-package-manifest.json" \
    >"${state_dir}/rollback-package-manifest.sha256"
  install -d -m 700 -- "${WORK_DIR}"
}

app_update_prepublish() {
  local stage expected_sha
  stage="$(remote_stage)"
  expected_sha="$(sha256sum "${PACKAGE_DIR}/app-update/app-release.apk" | awk '{print $1}')"
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
    "umask 077; test ! -e '${stage}' && mkdir -m 700 '${stage}'"
  scp -P "${NAS_SSH_PORT}" -o BatchMode=yes -o ConnectTimeout=15 -r \
    "${PACKAGE_DIR}/." "${NAS_SSH}:${stage}/"
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
    "cd '${stage}' && chmod +x validate-nas-package.sh credential-deploy-lock.sh export-nas-credentials.sh && ./validate-nas-package.sh . 0.4.0 >/dev/null"
  set +e
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" bash -s -- \
    "${CONTAINER_NAME}" "${stage}" "${expected_sha}" "${DATA_PATH}" <<'REMOTE'
set -euo pipefail
container="$1"
stage="$2"
expected_sha="$3"
data_path="$4"
image="$(docker inspect "${container}" --format '{{.Config.Image}}')"
test "$(docker inspect "${container}" --format '{{.State.Running}}')" = true
mkdir -m 700 "${stage}/rollback-app-update"
docker cp "${container}:/data/app-release.apk" "${stage}/rollback-app-update/app-release.apk"
docker cp "${container}:/data/app-update.json" "${stage}/rollback-app-update/app-update.json"
install_pair() {
  local src="$1"
  docker run --rm --user 10001:10001 \
    -v "${data_path}:/data" \
    -v "${src}:/src:ro" \
    --entrypoint /bin/sh \
    "${image}" \
    -ec 'cp /src/app-release.apk /data/app-release.apk.lezi-staging && cp /src/app-update.json /data/app-update.json.lezi-staging && mv -f /data/app-release.apk.lezi-staging /data/app-release.apk && mv -f /data/app-update.json.lezi-staging /data/app-update.json && chmod 644 /data/app-release.apk /data/app-update.json && test ! -e /data/app-release.apk.lezi-staging && test ! -e /data/app-update.json.lezi-staging'
}
restore_pair() {
  install_pair "${stage}/rollback-app-update"
  printf '%s\n' complete >"${stage}/app-update-restore-complete"
}
trap restore_pair ERR
printf '%s\n' ready >"${stage}/app-update-mutation-started"
install_pair "${stage}/app-update"
served_sha="$(curl -fsS --max-time 30 http://127.0.0.1:8767/download/lezi.apk | sha256sum | awk '{print $1}')"
test "${served_sha}" = "${expected_sha}"
trap - ERR
REMOTE
  publish_status=$?
  set -e
  set +e
  remote_markers="$(ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
    "test -f '${stage}/app-update-mutation-started' && printf started || true; test -f '${stage}/app-update-restore-complete' && printf ' restored' || true")"
  marker_status=$?
  set -e
  if [[ "${marker_status}" != 0 ]]; then
    printf '%s\n' uncertain >"${state_dir}/app-update-mutation-started"
    return 1
  fi
  if [[ "${remote_markers}" == *started* ]]; then
    printf '%s\n' started >"${state_dir}/app-update-mutation-started"
  fi
  if [[ "${remote_markers}" == *restored* ]]; then
    printf '%s\n' complete >"${state_dir}/app-update-restore-complete"
  fi
  [[ "${publish_status}" == 0 ]] || return "${publish_status}"
  printf '%s\n' "${expected_sha}" >"${state_dir}/prepublished-apk.sha256"
}

lease_acquire() {
  local token
  token="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
  [[ "${token}" =~ ^[0-9a-f]{64}$ ]] || die "could not generate lease token"
  printf '%s\n' "${token}" >"${state_dir}/lease-token"
  chmod 600 "${state_dir}/lease-token"
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
    "bash -s -- acquire '${lock_dir}' '${token}'" \
    <"${SCRIPT_DIR}/credential-deploy-lock.sh"
}

update_lease_acquire() {
  local token
  token="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
  [[ "${token}" =~ ^[0-9a-f]{64}$ ]] || die "could not generate update lease token"
  printf '%s\n' "${token}" >"${state_dir}/update-lease-token"
  chmod 600 "${state_dir}/update-lease-token"
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
    "bash -s -- acquire '${update_lock_dir}' '${token}'" \
    <"${SCRIPT_DIR}/credential-deploy-lock.sh"
}

update_lease_release() {
  local token
  token="$(<"${state_dir}/update-lease-token")"
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
    "bash -s -- release '${update_lock_dir}' '${token}'" \
    <"${SCRIPT_DIR}/credential-deploy-lock.sh"
}

source_preflight() {
  local rollback_image_id rollback_image rollback_schema sync_bin stage
  require_attested_rollback_identity
  rollback_image_id="$(manifest_value "${state_dir}/rollback-package-manifest.json" image_id)"
  rollback_image="$(rollback_source_image)"
  rollback_schema="$(rollback_source_schema)"
  stage="$(remote_stage)"
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" bash -s -- \
    "${CONTAINER_NAME}" "${rollback_image_id}" "${rollback_image}" "${stage}" <<'REMOTE' \
    >"${state_dir}/source-preflight.txt"
set -euo pipefail
container="$1"
expected_image="$2"
expected_tag="$3"
stage="$4"
test "$(docker inspect "${container}" --format '{{.State.Running}}')" = true
test "$(docker inspect "${container}" --format '{{.Image}}')" = "${expected_image}"
test "$(docker inspect "${container}" --format '{{.Config.Image}}')" = "${expected_tag}"
docker exec "${container}" /bin/sh -ec '
  test -s /data/lezi.db
  test -s /data/server.secret
  test -s /data/tls/server.crt
  test -s /data/tls/server.key
  openssl x509 -in /data/tls/server.crt -checkend 86400 -noout >/dev/null
  openssl pkey -in /data/tls/server.key -check -noout >/dev/null 2>&1
  printf "certificate_sha256="
  openssl dgst -sha256 -r /data/tls/server.crt | awk "{print \\$1}"
  printf "spki_sha256="
  openssl x509 -in /data/tls/server.crt -pubkey -noout \
    | openssl pkey -pubin -outform DER \
    | openssl dgst -sha256 -r | awk "{print \\$1}"
  find /data -type f -print0 | sort -z | xargs -0 -r sha256sum
'
docker save -o "${stage}/rollback-image.tar" "${expected_image}"
sha256sum "${stage}/rollback-image.tar" >"${stage}/rollback-image.tar.sha256"
REMOTE

  rm -rf -- "${WORK_DIR}/preflight-source"
  mkdir -p "${WORK_DIR}/preflight-source"
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
    "docker cp '${CONTAINER_NAME}:/data/.' -" \
    | tar -C "${WORK_DIR}/preflight-source" -xf -
  command -v sqlite3 >/dev/null 2>&1 || die "sqlite3 is required for source schema preflight"
  [[ "$(sqlite3 "${WORK_DIR}/preflight-source/lezi.db" 'PRAGMA user_version;')" == "${rollback_schema}" ]] \
    || die "cutover source must be exact server schema ${rollback_schema} for ${rollback_image}"
  printf 'schema=%s\n' "${rollback_schema}" >>"${state_dir}/source-preflight.txt"
  cmp -s "${WORK_DIR}/preflight-source/app-release.apk" \
    "${PACKAGE_DIR}/app-update/app-release.apk" \
    || die "live prepublished APK differs from the signer-attested target package"
  cmp -s "${WORK_DIR}/preflight-source/app-update.json" \
    "${PACKAGE_DIR}/app-update/app-update.json" \
    || die "live prepublished metadata differs from the signer-attested target package"
  sync_bin="$(resolve_sync_bin)"
  "${sync_bin}" offline-migrate dry-run --in "${WORK_DIR}/preflight-source" \
    >"${state_dir}/source-dry-run.txt"
}

rollback_backup() {
  local config_root backup_dir recipients output temporary frozen after old_image inspect_output
  require_attested_rollback_identity
  config_root="${XDG_CONFIG_HOME:-${HOME:?}/.config}"
  backup_dir="${LEZI_SCHEMA_CUTOVER_ROLLBACK_DIR:-${config_root}/lezi/schema-cutover-backups}"
  recipients="${LEZI_AGE_RECIPIENTS_FILE:-${config_root}/lezi/age-recipients.txt}"
  require_safe_path LEZI_SCHEMA_CUTOVER_ROLLBACK_DIR "${backup_dir}"
  [[ -s "${recipients}" ]] || die "age recipients file is missing"
  command -v age >/dev/null 2>&1 || die "age is required"
  if [[ ! -e "${backup_dir}" ]]; then
    install -d -m 700 -- "${backup_dir}"
  fi
  [[ ! -L "${backup_dir}" && "$(stat -c '%a' "${backup_dir}")" == 700 ]] \
    || die "rollback backup directory must be a real mode-700 directory"
  output="${backup_dir}/lezi-schema$(rollback_source_schema)-data-$(operation_id).tar.age"
  [[ ! -e "${output}" ]] || die "rollback backup already exists"
  temporary="${output}.tmp"
  frozen="$(sed '/^schema=/d' "${state_dir}/frozen-source-inventory.txt")"
  old_image="$(manifest_value "${state_dir}/rollback-package-manifest.json" image_id)"
  set +e
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" docker run --rm --user 10001:10001 \
    -v "${DATA_PATH}:/source:ro" --entrypoint /bin/sh "${old_image}" -ec \
    "'tar -C /source -cf - .'" \
    | age --encrypt --recipients-file "${recipients}" --output "${temporary}"
  statuses=("${PIPESTATUS[@]}")
  set -e
  if [[ "${statuses[0]}" != 0 || "${statuses[1]}" != 0 || ! -s "${temporary}" ]]; then
    rm -f -- "${temporary}"
    die "direct-to-age full data rollback backup failed"
  fi
  after="$(ssh "${SSH_OPTS[@]}" "${NAS_SSH}" docker run --rm --user 10001:10001 \
    -v "${DATA_PATH}:/source:ro" --entrypoint /bin/sh "${old_image}" -ec \
    "'cd /source; find . -type f -print0 | sort -z | xargs -0 -r sha256sum'")"
  if [[ "${frozen}" != "${after}" ]]; then
    rm -f -- "${temporary}"
    die "stopped source changed while rollback bundle was streaming"
  fi
  chmod 600 "${temporary}"
  mv -- "${temporary}" "${output}"
  sha256sum "${output}" >"${output}.sha256"
  chmod 600 "${output}.sha256"
  printf '%s\n' "${output}" >"${state_dir}/data-rollback-bundle.path"

  # The exact Docker start contract may contain credentials; stream it directly to age.
  inspect_output="${backup_dir}/lezi-schema$(rollback_source_schema)-start-$(operation_id).json.age"
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" "docker inspect '${CONTAINER_NAME}'" \
    | age --encrypt --recipients-file "${recipients}" --output "${inspect_output}"
  [[ -s "${inspect_output}" ]] || die "encrypted start-contract backup is empty"
  chmod 600 "${inspect_output}"
  sha256sum "${inspect_output}" >"${inspect_output}.sha256"
  chmod 600 "${inspect_output}.sha256"
  printf '%s\n' "${inspect_output}" >"${state_dir}/start-contract-backup.path"
}

credential_backup() {
  local token stage
  token="$(<"${state_dir}/lease-token")"
  stage="$(remote_stage)"
  NAS_SSH="${NAS_SSH}" NAS_SSH_PORT="${NAS_SSH_PORT}" \
  NAS_REMOTE_DIR="${stage}" LEZI_SYNC_VERSION=0.4.0 \
  LEZI_DEPLOY_LOCK_TOKEN="${token}" \
    "${SCRIPT_DIR}/backup-nas-credentials.sh" \
    >"${state_dir}/credential-backup.txt"
}

stop_source() {
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
    "test \"\$(docker inspect '${CONTAINER_NAME}' --format '{{.State.Running}}')\" = true && docker stop '${CONTAINER_NAME}' >/dev/null && test \"\$(docker inspect '${CONTAINER_NAME}' --format '{{.State.Running}}')\" = false"
}

frozen_inventory() {
  local old_image
  old_image="$(manifest_value "${state_dir}/rollback-package-manifest.json" image_id)"
  printf 'schema=%s\n' "$(rollback_source_schema)" >"${state_dir}/frozen-source-inventory.txt"
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" docker run --rm --user 10001:10001 \
    -v "${DATA_PATH}:/source:ro" --entrypoint /bin/sh "${old_image}" -ec \
    "'cd /source; find . -type f -print0 | sort -z | xargs -0 -r sha256sum'" \
    >>"${state_dir}/frozen-source-inventory.txt"
}

copy_out() {
  local local_inventory frozen_inventory
  rm -rf -- "${WORK_DIR}/source"
  mkdir -p "${WORK_DIR}/source"
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" "docker cp '${CONTAINER_NAME}:/data/.' -" \
    | tar -C "${WORK_DIR}/source" -xf -
  local_inventory="$(cd "${WORK_DIR}/source" && find . -type f -print0 | sort -z | xargs -0 -r sha256sum)"
  frozen_inventory="$(sed '/^schema=/d' "${state_dir}/frozen-source-inventory.txt")"
  [[ "${local_inventory}" == "${frozen_inventory}" ]] \
    || die "copy-out inventory differs from the frozen source"
  [[ "$(sqlite3 "${WORK_DIR}/source/lezi.db" 'PRAGMA user_version;')" == "$(rollback_source_schema)" ]] \
    || die "copy-out source schema differs from the frozen schema"
}

migrate() {
  local sync_bin
  sync_bin="$(resolve_sync_bin)"
  rm -rf -- "${WORK_DIR}/out"
  "${sync_bin}" offline-migrate migrate \
    --in "${WORK_DIR}/source" --out "${WORK_DIR}/out" \
    >"${state_dir}/migration.txt"
}

validate_migration() {
  local sync_bin
  sync_bin="$(resolve_sync_bin)"
  # The migrator intentionally excludes the update channel from the new root:
  # publication and data migration have separate rollback lifetimes. Bind the
  # already signer-attested target pair into the staged root before validating
  # the exact bytes that will become live.
  cp -- "${PACKAGE_DIR}/app-update/app-release.apk" \
    "${WORK_DIR}/out/app-release.apk"
  cp -- "${PACKAGE_DIR}/app-update/app-update.json" \
    "${WORK_DIR}/out/app-update.json"
  "${sync_bin}" offline-migrate validate --out "${WORK_DIR}/out" \
    >"${state_dir}/migration-validation.txt"
  (cd "${WORK_DIR}/out" && find . -type f ! -name lezi.db -print0 | sort -z | xargs -0 -r sha256sum) \
    >"${state_dir}/validated-target-inventory.txt"
  LC_ALL=C sort -o "${state_dir}/validated-target-inventory.txt" \
    "${state_dir}/validated-target-inventory.txt"
  sqlite3 "${WORK_DIR}/out/lezi.db" .dump | sha256sum \
    >"${state_dir}/validated-target-database-dump.sha256"
}

stage_copy_back() {
  local token staging parent old_image
  token="$(<"${state_dir}/lease-token")"
  staging="${DATA_PATH}.schema13-staging-${token}"
  parent="$(dirname -- "${DATA_PATH}")"
  old_image="$(manifest_value "${state_dir}/rollback-package-manifest.json" image_id)"
  : >"${WORK_DIR}/out/.schema-cutover-read-only"
  tar -C "${WORK_DIR}/out" -cf - . \
    | ssh "${SSH_OPTS[@]}" "${NAS_SSH}" docker run -i --rm --user 0 \
      -v "${parent}:/lezi-parent" --entrypoint /bin/sh "${old_image}" -ec \
      "'set -e; test ! -e /lezi-parent/$(basename -- "${staging}"); mkdir -m 700 /lezi-parent/$(basename -- "${staging}"); tar -C /lezi-parent/$(basename -- "${staging}") -xf -; chown -R 10001:10001 /lezi-parent/$(basename -- "${staging}")'"
  printf '%s\n' "${staging}" >"${state_dir}/remote-staging-data.path"
}

activate_target() {
  local token staging previous parent old_image
  token="$(<"${state_dir}/lease-token")"
  staging="$(<"${state_dir}/remote-staging-data.path")"
  previous="${DATA_PATH}.schema$(rollback_source_schema)-pre-cutover-${token}"
  parent="$(dirname -- "${DATA_PATH}")"
  old_image="$(manifest_value "${state_dir}/rollback-package-manifest.json" image_id)"
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" docker run --rm --user 0 \
    -v "${parent}:/lezi-parent" --entrypoint /bin/sh "${old_image}" -ec \
    "'set -e; test -d /lezi-parent/$(basename -- "${staging}"); test ! -e /lezi-parent/$(basename -- "${previous}"); mv /lezi-parent/$(basename -- "${DATA_PATH}") /lezi-parent/$(basename -- "${previous}"); mv /lezi-parent/$(basename -- "${staging}") /lezi-parent/$(basename -- "${DATA_PATH}")'"
  printf '%s\n' "${previous}" >"${state_dir}/remote-previous-data.path"

  NAS_SSH="${NAS_SSH}" NAS_SSH_PORT="${NAS_SSH_PORT}" \
  LEZI_DATA_HOST_PATH="${DATA_PATH}" LEZI_NAS_PACKAGE_DIR="${PACKAGE_DIR}" \
  LEZI_SKIP_PACKAGE=1 LEZI_ALLOW_SECRET_RECOVERY=1 \
  LEZI_SCHEMA_CUTOVER_APPROVAL=I_ACKNOWLEDGE_0_4_0_SCHEMA_CUTOVER \
  LEZI_INHERITED_DEPLOY_LOCK_TOKEN="${token}" \
    "${SCRIPT_DIR}/push-and-deploy.sh"
}

post_check() {
  local health expected_image cert_before spki_before sync_bin
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
    "docker exec '${CONTAINER_NAME}' lezi-sync healthcheck"
  health="$(curl -k -fsS --connect-timeout 5 \
    "https://${LEZI_LAN_HOST:-192.168.50.4}:8765/health")"
  python3 - "${health}" <<'PY'
import json, sys
body = json.loads(sys.argv[1])
if body.get("version") != "0.4.0" or body.get("server_schema") != 13:
    raise SystemExit("post-check requires version 0.4.0 and server_schema 13")
PY
  expected_image="$(manifest_value "${PACKAGE_DIR}/MANIFEST.json" image_id)"
  [[ "$(ssh "${SSH_OPTS[@]}" "${NAS_SSH}" "docker inspect '${CONTAINER_NAME}' --format '{{.Image}}'")" == "${expected_image}" ]] \
    || die "running image differs from target manifest"
  cert_before="$(sed -n 's/^certificate_sha256=//p' "${state_dir}/source-preflight.txt")"
  spki_before="$(sed -n 's/^spki_sha256=//p' "${state_dir}/source-preflight.txt")"
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" "docker exec '${CONTAINER_NAME}' /bin/sh -ec 'test \"\$(openssl dgst -sha256 -r /data/tls/server.crt | awk '\''{print \\$1}'\'')\" = \"${cert_before}\"; test \"\$(openssl x509 -in /data/tls/server.crt -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -r | awk '\''{print \\$1}'\'')\" = \"${spki_before}\"; sha256sum -c - >/dev/null'" \
    < <(grep -E '  /data/(media/|server.secret$)' "${state_dir}/source-preflight.txt")
  sync_bin="$(resolve_sync_bin)"
  rm -rf -- "${WORK_DIR}/postcheck"
  mkdir -p "${WORK_DIR}/postcheck"
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" "docker cp '${CONTAINER_NAME}:/data/.' -" \
    | tar -C "${WORK_DIR}/postcheck" -xf -
  rm -f -- "${WORK_DIR}/postcheck/.schema-cutover-read-only"
  "${sync_bin}" offline-migrate validate --out "${WORK_DIR}/postcheck" \
    >"${state_dir}/postcheck-data-validation.txt"
  (cd "${WORK_DIR}/postcheck" \
    && find . -type f ! -name lezi.db -print0 | sort -z | xargs -0 -r sha256sum) \
    >"${state_dir}/postcheck-file-inventory.txt"
  cmp -s "${state_dir}/validated-target-inventory.txt" \
    "${state_dir}/postcheck-file-inventory.txt" \
    || die "post-start complete file inventory differs from validated migration output"
  sqlite3 "${WORK_DIR}/postcheck/lezi.db" .dump | sha256sum \
    >"${state_dir}/postcheck-database-dump.sha256"
  cmp -s "${state_dir}/validated-target-database-dump.sha256" \
    "${state_dir}/postcheck-database-dump.sha256" \
    || die "post-start logical database inventory differs from validated migration output"
  sqlite3 "${WORK_DIR}/postcheck/lezi.db" \
    "SELECT 'families=' || COUNT(*) FROM families;
     SELECT 'memberships=' || COUNT(*) FROM memberships;
     SELECT 'entities=' || COUNT(*) FROM entities;
     SELECT 'committed_bundles=' || COUNT(*) FROM sync_bundles WHERE status = 'committed';" \
    >"${state_dir}/postcheck-semantic-inventory.txt"
  grep -E '^(families|memberships|entities|committed_bundles)=' \
    "${state_dir}/migration.txt" >"${state_dir}/expected-semantic-inventory.txt"
  cmp -s "${state_dir}/expected-semantic-inventory.txt" \
    "${state_dir}/postcheck-semantic-inventory.txt" \
    || die "post-start data inventory differs from the validated migration output"
}

remove_write_gate() {
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
    "docker exec '${CONTAINER_NAME}' rm -f /data/.schema-cutover-read-only"
}

restart_open() {
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
    "docker restart '${CONTAINER_NAME}' >/dev/null && docker exec '${CONTAINER_NAME}' lezi-sync healthcheck"
}

final_check() {
  LEZI_EXPECTED_VERSION=0.4.0 LEZI_PROBE_WRITE_EVIDENCE=0 \
  NAS_SSH="${NAS_SSH}" NAS_SSH_PORT="${NAS_SSH_PORT}" \
    "${SCRIPT_DIR}/live-cutover-probe.sh"
}

rollback_preopen() {
  local stage token previous staging old_image
  require_attested_rollback_identity
  stage="$(remote_stage)"
  old_image="$(manifest_value "${state_dir}/rollback-package-manifest.json" image_id)"
  if [[ -f "${state_dir}/lease-token" ]]; then
    token="$(<"${state_dir}/lease-token")"
    previous="${DATA_PATH}.schema$(rollback_source_schema)-pre-cutover-${token}"
    staging="${DATA_PATH}.schema13-staging-${token}"
  fi
  if [[ -n "${previous:-}" ]] \
      && ssh "${SSH_OPTS[@]}" "${NAS_SSH}" "test -d '${previous}'"; then
    ssh "${SSH_OPTS[@]}" "${NAS_SSH}" docker run --rm --user 0 \
      -v "${data_parent}:/lezi-parent" --entrypoint /bin/sh "${old_image}" -ec \
      "'set -e; test ! -e /lezi-parent/$(basename -- "${staging}"); test -d /lezi-parent/$(basename -- "${DATA_PATH}"); mv /lezi-parent/$(basename -- "${DATA_PATH}") /lezi-parent/$(basename -- "${staging}"); mv /lezi-parent/$(basename -- "${previous}") /lezi-parent/$(basename -- "${DATA_PATH}")'"
    NAS_SSH="${NAS_SSH}" NAS_SSH_PORT="${NAS_SSH_PORT}" \
    LEZI_SYNC_VERSION="$(rollback_source_version)" LEZI_DATA_HOST_PATH="${DATA_PATH}" \
    LEZI_NAS_PACKAGE_DIR="${ROLLBACK_PACKAGE_DIR}" LEZI_SKIP_PACKAGE=1 \
    LEZI_ALLOW_SECRET_RECOVERY=1 \
    LEZI_SCHEMA_CUTOVER_APPROVAL=I_ACKNOWLEDGE_0_4_0_SCHEMA_CUTOVER \
    LEZI_INHERITED_DEPLOY_LOCK_TOKEN="${token}" \
      "${SCRIPT_DIR}/push-and-deploy.sh"
  fi
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" bash -s -- \
    "${CONTAINER_NAME}" "${stage}" <<'REMOTE'
set -euo pipefail
container="$1"
stage="$2"
running="$(docker inspect "${container}" --format '{{.State.Running}}')"
test "${running}" = false || test "${running}" = true
docker cp "${stage}/rollback-app-update/app-release.apk" "${container}:/data/app-release.apk.lezi-staging"
docker cp "${stage}/rollback-app-update/app-update.json" "${container}:/data/app-update.json.lezi-staging"
if test "${running}" = false; then
  docker start "${container}" >/dev/null
fi
docker exec "${container}" /bin/sh -ec '
  chown 10001:10001 /data/app-release.apk.lezi-staging /data/app-update.json.lezi-staging
  chmod 644 /data/app-release.apk.lezi-staging /data/app-update.json.lezi-staging
  mv -f /data/app-release.apk.lezi-staging /data/app-release.apk
  mv -f /data/app-update.json.lezi-staging /data/app-update.json
'
REMOTE
  printf '%s\n' complete >"${state_dir}/app-update-restore-complete"
}

mark_manual_rollback() {
  printf '%s\n' \
    'TARGET MAY HAVE ACCEPTED SCHEMA-13 WRITES.' \
    "Do not restore schema-$(rollback_source_schema) data without explicit incident authorization and write reconciliation." \
    >"${state_dir}/MANUAL_ROLLBACK_REQUIRED"
  chmod 600 "${state_dir}/MANUAL_ROLLBACK_REQUIRED"
}

lease_release() {
  local token
  token="$(<"${state_dir}/lease-token")"
  ssh "${SSH_OPTS[@]}" "${NAS_SSH}" \
    "bash -s -- release '${lock_dir}' '${token}'" \
    <"${SCRIPT_DIR}/credential-deploy-lock.sh"
}

case "${phase}" in
  package_preflight) package_preflight ;;
  app_update_prepublish) app_update_prepublish ;;
  update_lease_acquire) update_lease_acquire ;;
  update_lease_release) update_lease_release ;;
  lease_acquire) lease_acquire ;;
  source_preflight) source_preflight ;;
  rollback_backup) rollback_backup ;;
  credential_backup) credential_backup ;;
  stop_source) stop_source ;;
  frozen_inventory) frozen_inventory ;;
  copy_out) copy_out ;;
  migrate) migrate ;;
  validate_migration) validate_migration ;;
  stage_copy_back) stage_copy_back ;;
  activate_target) activate_target ;;
  post_check) post_check ;;
  remove_write_gate) remove_write_gate ;;
  restart_open) restart_open ;;
  final_check) final_check ;;
  rollback_preopen) rollback_preopen ;;
  mark_manual_rollback) mark_manual_rollback ;;
  lease_release) lease_release ;;
  *) die "unknown phase" ;;
esac
