#!/usr/bin/env bash
# Developer-owned, local-only schema 11/12 -> 13 cutover rehearsal.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEST_MODE="${LEZI_SCHEMA_CUTOVER_REHEARSAL_TEST_MODE:-0}"
FAIL_PHASES=(apk_hash backup copy_out migrate validate copy_back start health)

die() {
  echo "error: schema cutover rehearsal: $*" >&2
  exit 1
}

if [[ "${LEZI_ISOLATED_SCHEMA_CUTOVER_REHEARSAL:-0}" != "1" ]]; then
  die "set LEZI_ISOLATED_SCHEMA_CUTOVER_REHEARSAL=1 only for a developer-owned isolated instance"
fi

if [[ "${LEZI_REHEARSAL_HOST:-127.0.0.1}" != "127.0.0.1" \
    && "${LEZI_REHEARSAL_HOST:-127.0.0.1}" != "localhost" ]]; then
  die "LEZI_REHEARSAL_HOST must be loopback; family NAS and LAN hosts are forbidden"
fi
if [[ -n "${NAS_SSH:-}" || -n "${NAS_REMOTE_DIR:-}" || -n "${LEZI_DATA_HOST_PATH:-}" ]]; then
  die "NAS/CD variables are forbidden in the isolated rehearsal"
fi

https_port="${LEZI_REHEARSAL_HTTPS_PORT:-28765}"
internal_port="${LEZI_REHEARSAL_INTERNAL_PORT:-28766}"
for port in "${https_port}" "${internal_port}"; do
  if [[ ! "${port}" =~ ^[0-9]+$ || "${port}" -lt 1024 || "${port}" -gt 65535 ]]; then
    die "rehearsal ports must be unprivileged numeric ports"
  fi
done
[[ "${https_port}" != "${internal_port}" ]] || die "HTTPS and internal ports must differ"
(( internal_port < 65535 )) || die "internal port must leave room for the invite port"
invite_port=$((internal_port + 1))
[[ "${invite_port}" != "${https_port}" ]] || die "invite and HTTPS ports must differ"

runner="${LEZI_SCHEMA_CUTOVER_REHEARSAL_RUNNER:-${SCRIPT_DIR}/schema-cutover-rehearsal-steps.sh}"
h29_orchestrator="${SCRIPT_DIR}/schema-cutover.sh"
h29_adapter="${LEZI_SCHEMA_CUTOVER_REHEARSAL_H29_ADAPTER:-${SCRIPT_DIR}/schema-cutover-rehearsal-h29-adapter.sh}"
if [[ "${TEST_MODE}" != "0" && "${TEST_MODE}" != "1" ]]; then
  die "LEZI_SCHEMA_CUTOVER_REHEARSAL_TEST_MODE must be 0 or 1"
fi
if [[ "${TEST_MODE}" == "0" && -n "${LEZI_SCHEMA_CUTOVER_REHEARSAL_RUNNER:-}" ]]; then
  die "custom rehearsal runners are test-only"
fi
if [[ "${TEST_MODE}" == "0" && -n "${LEZI_SCHEMA_CUTOVER_REHEARSAL_H29_ADAPTER:-}" ]]; then
  die "custom H29 adapters are test-only"
fi
[[ -x "${runner}" ]] || die "rehearsal runner is missing or not executable: ${runner}"
[[ -x "${h29_orchestrator}" && -x "${h29_adapter}" ]] \
  || die "H29 production orchestrator or isolated adapter is missing"

state_root="${LEZI_SCHEMA_CUTOVER_REHEARSAL_ROOT:-}"
if [[ -z "${state_root}" ]]; then
  state_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-schema-cutover-rehearsal.XXXXXX")"
fi
[[ "${state_root}" == /* ]] || die "rehearsal root must be absolute"
state_root="$(realpath -m -- "${state_root}")"
case "${state_root}/" in
  /tmp/*|/var/tmp/*) ;;
  *) die "rehearsal root must be under /tmp or /var/tmp" ;;
esac
if [[ -L "${state_root}" ]]; then
  die "rehearsal root must not be a symlink"
fi
if [[ ! -e "${state_root}" ]]; then
  install -d -m 700 -- "${state_root}"
elif [[ ! -d "${state_root}" || "$(stat -c '%a' "${state_root}")" != 700 ]]; then
  die "existing rehearsal root must be a mode-700 directory"
fi
if [[ -n "$(find "${state_root}" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  die "rehearsal root must be empty"
fi

export LEZI_SCHEMA_CUTOVER_REHEARSAL_ROOT="${state_root}"
export LEZI_REHEARSAL_HOST="${LEZI_REHEARSAL_HOST:-127.0.0.1}"
export LEZI_REHEARSAL_HTTPS_PORT="${https_port}"
export LEZI_REHEARSAL_INTERNAL_PORT="${internal_port}"

receipt_value() {
  local file="$1" key="$2" value
  value="$(sed -nE "s/^${key}=([^[:space:]]+)$/\\1/p" "${file}")"
  [[ -n "${value}" && "$(grep -c "^${key}=" "${file}")" == 1 ]] \
    || die "receipt $(basename "${file}") has invalid ${key}"
  printf '%s' "${value}"
}

inventory_value() {
  local receipt="$1" field="$2" inventory value
  inventory="$(receipt_value "${receipt}" semantic_inventory)"
  value="$(tr ';' '\n' <<<"${inventory}" | sed -nE "s/^${field}=([0-9]+)$/\\1/p")"
  [[ -n "${value}" ]] || die "receipt inventory is missing ${field}"
  printf '%s' "${value}"
}

validate_receipt() {
  local file="$1" expected_schema="$2" require_service="${3:-1}"
  [[ -f "${file}" && ! -L "${file}" ]] || die "missing regular receipt ${file}"
  [[ "$(receipt_value "${file}" user_version)" == "${expected_schema}" ]] \
    || die "receipt schema mismatch in ${file}"
  local key value
  for key in data_sha256 tls_cert_sha256 tls_spki_sha256 server_secret_sha256 apk_sha256 app_update_sha256 facts_sha256 identity_sha256 causal_sha256 media_inventory_sha256; do
    value="$(receipt_value "${file}" "${key}")"
    [[ "${value}" =~ ^[0-9a-f]{64}$ ]] || die "receipt ${key} is not a SHA-256 digest"
  done
  value="$(receipt_value "${file}" image_id)"
  [[ "${value}" =~ ^sha256:[0-9a-f]{64}$ ]] || die "receipt image_id is incomplete"
  receipt_value "${file}" semantic_inventory >/dev/null
  if [[ "${require_service}" == 1 ]]; then
    value="$(receipt_value "${file}" health_receipt_sha256)"
    [[ "${value}" =~ ^[0-9a-f]{64}$ ]] || die "receipt health digest is invalid"
    [[ "$(receipt_value "${file}" ready_status)" == ok ]] || die "receipt readiness did not pass"
    receipt_value "${file}" fact_read_status >/dev/null
  fi
  if rg -n '192\.168\.50\.4|13096920600|secret=|BEGIN .*PRIVATE KEY' "${file}" >/dev/null; then
    die "receipt contains production identity or secret material"
  fi
}

compare_preserved() {
  local source="$1" observed="$2" context="$3" exact_rollback="${4:-0}" key
  local keys=(tls_cert_sha256 tls_spki_sha256 server_secret_sha256)
  if [[ "${exact_rollback}" == "1" ]]; then
    keys+=(data_sha256)
  fi
  if [[ "${exact_rollback}" == "1" ]]; then
    keys+=(apk_sha256 app_update_sha256)
  fi
  for key in "${keys[@]}"; do
    [[ "$(receipt_value "${source}" "${key}")" == "$(receipt_value "${observed}" "${key}")" ]] \
      || die "${context} changed preserved ${key}"
  done
}

compare_semantic_inventory() {
  local source="$1" observed="$2" context="$3" source_schema="$4" field
  for field in families facts branches conflicts media sessions; do
    [[ "$(inventory_value "${source}" "${field}")" \
        == "$(inventory_value "${observed}" "${field}")" ]] \
      || die "${context} changed semantic inventory ${field}"
  done
  local source_versions observed_versions
  source_versions="$(inventory_value "${source}" versions)"
  observed_versions="$(inventory_value "${observed}" versions)"
  if [[ "${source_schema}" == 11 ]]; then
    [[ "${observed_versions}" == "$(inventory_value "${source}" facts)" ]] \
      || die "${context} did not mint exactly one migration base per v11 fact"
  else
    [[ "${source_versions}" == "${observed_versions}" ]] \
      || die "${context} changed schema-12 version inventory"
  fi
  for field in facts_sha256 identity_sha256 media_inventory_sha256; do
    [[ "$(receipt_value "${source}" "${field}")" == "$(receipt_value "${observed}" "${field}")" ]] \
      || die "${context} changed ${field}"
  done
  if [[ "${source_schema}" == 12 ]]; then
    [[ "$(receipt_value "${source}" causal_sha256)" == "$(receipt_value "${observed}" causal_sha256)" ]] \
      || die "${context} changed schema-12 causal associations"
  fi
}

printf 'source_schema\tscenario\tresult\treceipt\n' >"${state_root}/matrix.tsv"

run_case() {
  local schema="$1" scenario="$2" expected_failure="$3"
  local case_name="schema${schema}-${scenario}"
  local case_dir="${state_root}/${case_name}"
  install -d -m 700 -- "${case_dir}"
  "${runner}" prepare "${case_dir}" "${schema}"
  "${runner}" capture_source "${case_dir}" "${schema}"
  validate_receipt "${case_dir}/source.receipt" "${schema}"

  local failed=0
  install -d -m 700 -- "${case_dir}/h29-state"
  if ! LEZI_SCHEMA_CUTOVER_APPROVAL=I_ACKNOWLEDGE_0_4_0_SCHEMA_CUTOVER \
      LEZI_SCHEMA_CUTOVER_TEST_MODE=1 \
      LEZI_SCHEMA_CUTOVER_STEP_RUNNER="${h29_adapter}" \
      LEZI_SCHEMA_CUTOVER_STATE_DIR="${case_dir}/h29-state" \
      LEZI_REHEARSAL_CASE_DIR="${case_dir}" \
      LEZI_REHEARSAL_SOURCE_SCHEMA="${schema}" \
      LEZI_REHEARSAL_FAIL_PHASE="${expected_failure}" \
      "${h29_orchestrator}" >"${case_dir}/h29.out" 2>"${case_dir}/h29.err"; then
    failed=1
  fi

  if [[ -z "${expected_failure}" ]]; then
    [[ "${failed}" == 0 ]] || die "${case_name} success path failed"
    "${runner}" capture_target "${case_dir}" "${schema}"
    validate_receipt "${case_dir}/target.receipt" 13
    compare_preserved "${case_dir}/source.receipt" "${case_dir}/target.receipt" "${case_name}"
    compare_semantic_inventory \
      "${case_dir}/source.receipt" "${case_dir}/target.receipt" "${case_name}" "${schema}"
    [[ "$(receipt_value "${case_dir}/target.receipt" apk_sha256)" \
        == "$(<"${case_dir}/target-apk.sha256")" ]] \
      || die "${case_name} did not activate the attested target APK"
    [[ "$(receipt_value "${case_dir}/target.receipt" app_update_sha256)" \
        == "$(<"${case_dir}/target-app-update.sha256")" ]] \
      || die "${case_name} did not activate the attested target metadata"
    [[ "$(receipt_value "${case_dir}/source.receipt" image_id)" \
        != "$(receipt_value "${case_dir}/target.receipt" image_id)" ]] \
      || die "${case_name} did not replace the source image"
    printf '%s\t%s\tsuccess\t%s\n' "${schema}" "${scenario}" "${case_name}/target.receipt" \
      >>"${state_root}/matrix.tsv"
  else
    [[ "${failed}" == 1 ]] || die "${case_name} did not inject ${expected_failure} failure"
    "${runner}" capture_rollback "${case_dir}" "${schema}"
    validate_receipt "${case_dir}/rollback.receipt" "${schema}" 0
    compare_preserved "${case_dir}/source.receipt" "${case_dir}/rollback.receipt" "${case_name} rollback" 1
    [[ "$(receipt_value "${case_dir}/source.receipt" semantic_inventory)" \
        == "$(receipt_value "${case_dir}/rollback.receipt" semantic_inventory)" ]] \
      || die "${case_name} rollback changed semantic inventory"
    for digest in facts_sha256 identity_sha256 causal_sha256 media_inventory_sha256; do
      [[ "$(receipt_value "${case_dir}/source.receipt" "${digest}")" \
          == "$(receipt_value "${case_dir}/rollback.receipt" "${digest}")" ]] \
        || die "${case_name} rollback changed ${digest}"
    done
    [[ "$(receipt_value "${case_dir}/source.receipt" image_id)" \
        == "$(receipt_value "${case_dir}/rollback.receipt" image_id)" ]] \
      || die "${case_name} rollback changed the source image"
    "${runner}" verify_rollback "${case_dir}" "${schema}"
    "${runner}" capture_rollback_service "${case_dir}" "${schema}"
    local value key
    value="$(receipt_value "${case_dir}/rollback-service.receipt" image_id)"
    [[ "${value}" =~ ^sha256:[0-9a-f]{64}$ \
        && "${value}" == "$(receipt_value "${case_dir}/source.receipt" image_id)" ]] \
      || die "${case_name} rollback service did not run the pinned source image"
    for key in health_receipt_sha256 fact_read_receipt_sha256; do
      value="$(receipt_value "${case_dir}/rollback-service.receipt" "${key}")"
      [[ "${value}" =~ ^[0-9a-f]{64}$ ]] \
        || die "${case_name} rollback service ${key} is invalid"
    done
    [[ "$(receipt_value "${case_dir}/rollback-service.receipt" ready_status)" == ok \
        && "$(receipt_value "${case_dir}/rollback-service.receipt" fact_read_status)" == ok ]] \
      || die "${case_name} rollback service did not become readable"
    printf '%s\t%s\trollback\t%s\n' "${schema}" "${scenario}" "${case_name}/rollback.receipt" \
      >>"${state_root}/matrix.tsv"
  fi
  "${runner}" cleanup "${case_dir}" "${schema}"
}

for schema in 11 12; do
  run_case "${schema}" success ''
  for phase in "${FAIL_PHASES[@]}"; do
    run_case "${schema}" "${phase}-failure" "${phase}"
  done
done

(
  cd "${state_root}"
  find . -type f ! -name receipts.sha256 -print0 \
    | LC_ALL=C sort -z \
    | xargs -0 sha256sum
) >"${state_root}/receipts.sha256"
chmod 600 "${state_root}/matrix.tsv" "${state_root}/receipts.sha256"

echo "isolated schema-cutover rehearsal passed"
echo "matrix=${state_root}/matrix.tsv"
echo "checksums=${state_root}/receipts.sha256"
