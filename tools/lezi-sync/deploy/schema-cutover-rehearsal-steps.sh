#!/usr/bin/env bash
# Local Docker/filesystem phase runner for schema-cutover-rehearsal.sh.
set -euo pipefail

action="${1:-}"
case_dir="${2:-}"
source_schema="${3:-}"

die() {
  echo "error: isolated rehearsal ${action}: $*" >&2
  exit 1
}

[[ "${case_dir}" == "${LEZI_SCHEMA_CUTOVER_REHEARSAL_ROOT:?}"/* ]] \
  || die "case directory escaped the rehearsal root"
[[ "${source_schema}" == 11 || "${source_schema}" == 12 ]] \
  || die "source schema must be 11 or 12"
if [[ "${LEZI_REHEARSAL_FAIL_PHASE:-}" == "${action}" ]]; then
  exit 42
fi

command -v docker >/dev/null 2>&1 || die "docker is required"
command -v curl >/dev/null 2>&1 || die "curl is required"
command -v openssl >/dev/null 2>&1 || die "openssl is required"
sqlite3_bin="${LEZI_REHEARSAL_SQLITE3_BIN:-}"
if [[ -z "${sqlite3_bin}" ]]; then
  sqlite3_bin="$(command -v sqlite3 || true)"
fi
[[ -x "${sqlite3_bin}" ]] || die "set LEZI_REHEARSAL_SQLITE3_BIN to sqlite3"

source_root_var="LEZI_REHEARSAL_SOURCE_ROOT_${source_schema}"
source_image_var="LEZI_REHEARSAL_SOURCE_IMAGE_${source_schema}"
source_root="${!source_root_var:-}"
source_image="${!source_image_var:-}"
target_image="${LEZI_REHEARSAL_TARGET_IMAGE:-lezi-sync:0.4.0}"
target_apk="${LEZI_REHEARSAL_TARGET_APK:-}"
target_metadata="${LEZI_REHEARSAL_TARGET_APP_UPDATE:-}"
[[ -n "${source_root}" && "${source_root}" == /* && -d "${source_root}" && ! -L "${source_root}" ]] \
  || die "${source_root_var} must name an absolute fixture data root"
source_root="$(realpath -e -- "${source_root}")"
case "${source_root}/" in
  /tmp/*|/var/tmp/*) ;;
  *) die "${source_root_var} must be an isolated fixture under /tmp or /var/tmp" ;;
esac
case "${source_root}/" in
  /tmp/zfsv3/*) die "family NAS bind ancestry is forbidden" ;;
esac
fixture_marker="${source_root}/.isolated-schema-cutover-fixture"
[[ -f "${fixture_marker}" && ! -L "${fixture_marker}" \
    && "$(stat -c '%u' "${fixture_marker}")" == "$(id -u)" \
    && "$(<"${fixture_marker}")" == "lezi-h30-synthetic-fixture-v1 schema=${source_schema}" ]] \
  || die "${source_root_var} lacks the developer-owned synthetic fixture marker"
[[ -n "${source_image}" ]] || die "${source_image_var} is required"
[[ -f "${target_apk}" && ! -L "${target_apk}" ]] || die "LEZI_REHEARSAL_TARGET_APK is required"
[[ -f "${target_metadata}" && ! -L "${target_metadata}" ]] \
  || die "LEZI_REHEARSAL_TARGET_APP_UPDATE is required"

live="${case_dir}/live"
original="${case_dir}/original"
copy_out="${case_dir}/copy-out"
migrated="${case_dir}/migrated"
staged="${case_dir}/staged"
container="lezi-h30-$(basename "${case_dir}" | tr -c 'A-Za-z0-9_.-' '-')"
container="${container:0:63}"
invite_port=$((LEZI_REHEARSAL_INTERNAL_PORT + 1))

image_id() {
  docker image inspect "$1" --format '{{.Id}}'
}

stop_container() {
  docker rm -f "${container}" >/dev/null 2>&1 || true
}

checkpoint_data_root() {
  local root="$1"
  "${sqlite3_bin}" "${root}/lezi.db" 'PRAGMA wal_checkpoint(TRUNCATE);' >/dev/null
  [[ ! -s "${root}/lezi.db-wal" ]] \
    || die "SQLite WAL remained non-empty after stopped-source checkpoint"
  rm -f -- "${root}/lezi.db-wal" "${root}/lezi.db-shm"
}

start_source_and_check() {
  local receipt_prefix="${1:-source}"
  local public_health="${case_dir}/${receipt_prefix}-health.json"
  local pull="${case_dir}/${receipt_prefix}-pull.json" scheme
  stop_container
  export LEZI_BOOTSTRAP_SECRET='isolated-rehearsal-only'
  docker run -d --name "${container}" --user "$(id -u):$(id -g)" \
    -e LEZI_BOOTSTRAP_SECRET -e LEZI_HOST=0.0.0.0 \
    -v "${live}:/data" \
    -p "127.0.0.1:${LEZI_REHEARSAL_HTTPS_PORT}:8765" \
    -p "127.0.0.1:${LEZI_REHEARSAL_INTERNAL_PORT}:8766" \
    -p "127.0.0.1:${invite_port}:8767" \
    "${source_image}" >/dev/null
  local ready=0
  for _ in $(seq 1 10); do
    if [[ "$(docker inspect "${container}" --format '{{.State.Running}}' 2>/dev/null || true)" == true ]] \
        && docker exec "${container}" lezi-sync healthcheck >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 1
  done
  if [[ "${ready}" != 1 ]]; then
    docker logs "${container}" >&2 || true
    die "source image could not open and ready schema-${source_schema} fixture"
  fi
  if [[ "${receipt_prefix}" == rollback ]]; then
    docker inspect "${container}" --format '{{.Image}}' >"${case_dir}/rollback-running-image-id"
  fi
  : >"${public_health}"
  for scheme in https http; do
    if curl -kfsS --max-time 2 "${scheme}://127.0.0.1:${LEZI_REHEARSAL_HTTPS_PORT}/health" \
        >"${public_health}" 2>/dev/null; then
      break
    fi
  done
  [[ -s "${public_health}" ]] || die "source public health endpoint was unreadable"
  local generation probe="${case_dir}/${receipt_prefix}-generation-probe.json"
  curl -ksS --max-time 3 \
    -H 'Authorization: Bearer isolated-access-token' \
    -H 'X-Lezi-Client-Version-Code: 20' \
    "https://127.0.0.1:${LEZI_REHEARSAL_HTTPS_PORT}/v1/pull?cursor=0&generation=isolated-probe" \
    >"${probe}" 2>/dev/null || true
  generation="$(sed -nE 's/.*"server_generation":"([^"]+)".*/\1/p' "${probe}")"
  [[ -n "${generation}" ]] || die "old source did not disclose its generation-change recovery receipt"
  : >"${pull}"
  for scheme in https http; do
    if curl -kfsS --max-time 3 \
        -H 'Authorization: Bearer isolated-access-token' \
        -H 'X-Lezi-Client-Version-Code: 20' \
        "${scheme}://127.0.0.1:${LEZI_REHEARSAL_HTTPS_PORT}/v1/pull?cursor=0&generation=${generation}" \
        >"${pull}" 2>/dev/null; then
      break
    fi
  done
  rg -q 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' "${pull}" \
    && rg -q '33333333-3333-3333-3333-333333333333' "${pull}" \
    || die "old source service did not return every fixture fact"
  chmod 600 "${public_health}" "${probe}" "${pull}"
  stop_container
  checkpoint_data_root "${live}"
}

copy_tree() {
  local from="$1" to="$2"
  [[ ! -e "${to}" ]] || die "copy destination already exists: ${to}"
  mkdir -p "${to}"
  cp -a -- "${from}/." "${to}/"
}

tree_digest() {
  local root="$1"
  (
    cd "${root}"
    find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum
  ) | sha256sum | awk '{print $1}'
}

table_count() {
  local database="$1" table="$2"
  if [[ "$("${sqlite3_bin}" "${database}" "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='${table}';")" == 1 ]]; then
    "${sqlite3_bin}" "${database}" "SELECT COUNT(*) FROM ${table};"
  else
    printf '0'
  fi
}

semantic_inventory() {
  local root="$1" database="${root}/lezi.db" media_count
  media_count="$(find "${root}/media" -type f 2>/dev/null | wc -l)"
  printf 'families=%s;facts=%s;versions=%s;branches=%s;conflicts=%s;media=%s;sessions=%s' \
    "$(table_count "${database}" families)" \
    "$(table_count "${database}" entities)" \
    "$(table_count "${database}" entity_versions)" \
    "$(table_count "${database}" conflict_branches)" \
    "$(table_count "${database}" conflicts)" \
    "${media_count//[[:space:]]/}" \
    "$(table_count "${database}" device_sessions)"
}

rows_digest() {
  local database="$1"
  shift
  {
    local query
    for query in "$@"; do
      printf '%s\n' "${query}"
      "${sqlite3_bin}" -quote "${database}" "${query}"
    done
  } | sha256sum | awk '{print $1}'
}

facts_digest() {
  rows_digest "$1" \
    'SELECT * FROM entities ORDER BY family_id, entity_type, client_uuid;'
}

identity_digest() {
  rows_digest "$1" \
    'SELECT * FROM families ORDER BY id;' \
    'SELECT * FROM memberships ORDER BY membership_id;' \
    'SELECT * FROM devices ORDER BY device_id;' \
    'SELECT * FROM device_sessions ORDER BY session_id;'
}

causal_digest() {
  local database="$1"
  if [[ "$(table_count "${database}" entity_versions)" == 0 \
      && "$("${sqlite3_bin}" "${database}" "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='entity_versions';")" == 0 ]]; then
    printf '%064d' 0
    return
  fi
  rows_digest "${database}" \
    'SELECT * FROM entity_versions ORDER BY family_id, version_id;' \
    'SELECT * FROM entity_stable_heads ORDER BY family_id, entity_type, client_uuid;' \
    'SELECT * FROM conflicts ORDER BY family_id, conflict_id;' \
    'SELECT * FROM conflict_branches ORDER BY family_id, conflict_id, branch_version_id;' \
    'SELECT * FROM entity_version_media ORDER BY family_id, version_id, media_uuid;' \
    'SELECT * FROM media_publications ORDER BY family_id, media_uuid;' \
    'SELECT family_id, membership_id, media_uuid, sha256, byte_size, created_at, expires_at, status, consumed_at FROM causal_media_staging ORDER BY family_id, media_uuid;'
}

media_digest() {
  local root="$1" database="${root}/lezi.db"
  {
    (cd "${root}/media" && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum)
    if [[ "$("${sqlite3_bin}" "${database}" "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='entity_version_media';")" == 1 ]]; then
      "${sqlite3_bin}" -quote "${database}" \
        'SELECT * FROM entity_version_media ORDER BY family_id, version_id, media_uuid;'
      "${sqlite3_bin}" -quote "${database}" \
        'SELECT * FROM media_publications ORDER BY family_id, media_uuid;'
    fi
  } | sha256sum | awk '{print $1}'
}

write_receipt() {
  local root="$1" image="$2" output="$3" version health_receipt fact_read_status
  stop_container
  version="$("${sqlite3_bin}" "${root}/lezi.db" 'PRAGMA user_version;')"
  if [[ "$(basename "${output}")" == target.receipt ]]; then
    health_receipt="${case_dir}/target-health.json"
    fact_read_status=semantic-validated
  else
    health_receipt="${case_dir}/source-health.json"
    fact_read_status=ok
  fi
  [[ -s "${health_receipt}" ]] || die "health receipt is missing"
  cat >"${output}" <<EOF
user_version=${version}
image_id=$(image_id "${image}")
data_sha256=$(tree_digest "${root}")
tls_cert_sha256=$(sha256sum "${root}/tls/server.crt" | awk '{print $1}')
tls_spki_sha256=$(openssl x509 -in "${root}/tls/server.crt" -pubkey -noout | openssl pkey -pubin -outform DER | sha256sum | awk '{print $1}')
server_secret_sha256=$(sha256sum "${root}/server.secret" | awk '{print $1}')
apk_sha256=$(sha256sum "${root}/app-release.apk" | awk '{print $1}')
app_update_sha256=$(sha256sum "${root}/app-update.json" | awk '{print $1}')
semantic_inventory=$(semantic_inventory "${root}")
facts_sha256=$(facts_digest "${root}/lezi.db")
identity_sha256=$(identity_digest "${root}/lezi.db")
causal_sha256=$(causal_digest "${root}/lezi.db")
media_inventory_sha256=$(media_digest "${root}")
health_receipt_sha256=$(sha256sum "${health_receipt}" | awk '{print $1}')
ready_status=ok
fact_read_status=${fact_read_status}
EOF
  chmod 600 "${output}"
}

run_migrator() {
  local subcommand="$1" input="$2" output="${3:-}"
  local -a command=(docker run --rm --user "$(id -u):$(id -g)" --entrypoint lezi-sync)
  command+=(-v "${case_dir}:/work")
  command+=("${target_image}" offline-migrate "${subcommand}")
  if [[ "${subcommand}" == validate ]]; then
    command+=(--out "/work/$(basename "${output}")")
  elif [[ "${subcommand}" == migrate ]]; then
    command+=(--in "/work/$(basename "${input}")" --out "/work/$(basename "${output}")")
  else
    die "unsupported migrator action ${subcommand}"
  fi
  "${command[@]}" >/dev/null
}

case "${action}" in
  prepare)
    [[ -z "$(find "${case_dir}" -mindepth 1 -maxdepth 1 -print -quit)" ]] \
      || die "case directory is not empty"
    copy_tree "${source_root}" "${live}"
    rm -- "${live}/.isolated-schema-cutover-fixture"
    [[ "$("${sqlite3_bin}" "${live}/lezi.db" 'PRAGMA user_version;')" == "${source_schema}" ]] \
      || die "fixture user_version does not match ${source_schema}"
    [[ "$(od -An -N2 -tx1 "${live}/app-release.apk" | tr -d ' \n')" == 504b ]] \
      || die "source rollback APK is not installable"
    if [[ "${source_schema}" == 11 ]]; then
      grep -q '"version_name"[[:space:]]*:[[:space:]]*"0.3.12"' "${live}/app-update.json" \
        || die "source rollback metadata is not pinned to 0.3.12"
    else
      grep -q '"version_name"[[:space:]]*:[[:space:]]*"0.3.13"' "${live}/app-update.json" \
        || die "source rollback metadata is not pinned to 0.3.13"
    fi
    printf '%s\n' "$(sha256sum "${target_apk}" | awk '{print $1}')" >"${case_dir}/target-apk.sha256"
    printf '%s\n' "$(sha256sum "${target_metadata}" | awk '{print $1}')" \
      >"${case_dir}/target-app-update.sha256"
    # Freeze the exact source only after its pinned old image has opened it.
    # This is always local Docker; the orchestrator rejects NAS/LAN endpoints.
    start_source_and_check
    copy_tree "${live}" "${original}"
    ;;
  capture_source) write_receipt "${original}" "${source_image}" "${case_dir}/source.receipt" ;;
  apk_hash)
    cp -- "${target_apk}" "${live}/app-release.apk.next"
    cp -- "${target_metadata}" "${live}/app-update.json.next"
    [[ "$(sha256sum "${live}/app-release.apk.next" | awk '{print $1}')" == "$(<"${case_dir}/target-apk.sha256")" ]] \
      || die "target APK staging hash drifted"
    [[ "$(sha256sum "${live}/app-update.json.next" | awk '{print $1}')" == "$(<"${case_dir}/target-app-update.sha256")" ]] \
      || die "target app-update staging hash drifted"
    mv -f -- "${live}/app-release.apk.next" "${live}/app-release.apk"
    mv -f -- "${live}/app-update.json.next" "${live}/app-update.json"
    ;;
  backup) copy_tree "${live}" "${case_dir}/rollback-backup" ;;
  copy_out)
    # Receipt/inventory readers may open a WAL database and create an empty
    # shm sidecar. Re-checkpoint immediately before the authoritative copy-out.
    checkpoint_data_root "${live}"
    copy_tree "${live}" "${copy_out}"
    checkpoint_data_root "${copy_out}"
    ;;
  migrate)
    run_migrator migrate "${copy_out}" "${migrated}"
    ;;
  validate)
    cp -- "${target_apk}" "${migrated}/app-release.apk"
    cp -- "${target_metadata}" "${migrated}/app-update.json"
    run_migrator validate "${migrated}" "${migrated}"
    ;;
  copy_back) copy_tree "${migrated}" "${staged}" ;;
  start)
    rm -rf -- "${live}"
    mv -- "${staged}" "${live}"
    : >"${live}/.schema-cutover-read-only"
    export LEZI_BOOTSTRAP_SECRET='isolated-rehearsal-only'
    docker run -d --name "${container}" --user "$(id -u):$(id -g)" \
      -e LEZI_BOOTSTRAP_SECRET -e LEZI_HOST=0.0.0.0 \
      -e LEZI_LAN_APK_DOWNLOAD_ORIGIN=http://127.0.0.1:8767 \
      -v "${live}:/data" \
      -p "127.0.0.1:${LEZI_REHEARSAL_HTTPS_PORT}:8765" \
      -p "127.0.0.1:${LEZI_REHEARSAL_INTERNAL_PORT}:8766" \
      -p "127.0.0.1:${invite_port}:8767" \
      "${target_image}" >/dev/null
    ;;
  health)
    for _ in $(seq 1 20); do
      if docker exec "${container}" lezi-sync healthcheck >/dev/null 2>&1 \
          && curl -kfsS --max-time 2 "https://127.0.0.1:${LEZI_REHEARSAL_HTTPS_PORT}/health" \
            >"${case_dir}/target-health.json" \
          && grep -q '0.4.0' "${case_dir}/target-health.json" \
          && [[ "$(curl -fsS --max-time 2 "http://127.0.0.1:${invite_port}/download/lezi.apk" \
                | sha256sum | awk '{print $1}')" == "$(<"${case_dir}/target-apk.sha256")" ]]; then
        chmod 600 "${case_dir}/target-health.json"
        exit 0
      fi
      sleep 1
    done
    docker logs "${container}" >&2 || true
    die "schema-13 target did not become healthy and ready"
    ;;
  open_writes)
    stop_container
    rm -f -- "${live}/.schema-cutover-read-only"
    export LEZI_BOOTSTRAP_SECRET='isolated-rehearsal-only'
    docker run -d --name "${container}" --user "$(id -u):$(id -g)" \
      -e LEZI_BOOTSTRAP_SECRET -e LEZI_HOST=0.0.0.0 \
      -e LEZI_LAN_APK_DOWNLOAD_ORIGIN=http://127.0.0.1:8767 \
      -v "${live}:/data" \
      -p "127.0.0.1:${LEZI_REHEARSAL_HTTPS_PORT}:8765" \
      -p "127.0.0.1:${LEZI_REHEARSAL_INTERNAL_PORT}:8766" \
      -p "127.0.0.1:${invite_port}:8767" \
      "${target_image}" >/dev/null
    for _ in $(seq 1 20); do
      if docker exec "${container}" lezi-sync healthcheck >/dev/null 2>&1; then
        exit 0
      fi
      sleep 1
    done
    die "schema-13 target did not reopen for writes"
    ;;
  capture_target) write_receipt "${live}" "${target_image}" "${case_dir}/target.receipt" ;;
  rollback)
    stop_container
    rm -rf -- "${live}"
    copy_tree "${original}" "${live}"
    ;;
  verify_rollback) start_source_and_check rollback ;;
  capture_rollback) write_receipt "${live}" "${source_image}" "${case_dir}/rollback.receipt" ;;
  capture_rollback_service)
    cat >"${case_dir}/rollback-service.receipt" <<EOF
image_id=$(<"${case_dir}/rollback-running-image-id")
health_receipt_sha256=$(sha256sum "${case_dir}/rollback-health.json" | awk '{print $1}')
fact_read_receipt_sha256=$(sha256sum "${case_dir}/rollback-pull.json" | awk '{print $1}')
ready_status=ok
fact_read_status=ok
EOF
    chmod 600 "${case_dir}/rollback-service.receipt"
    ;;
  cleanup) stop_container ;;
  *) die "unknown phase" ;;
esac
