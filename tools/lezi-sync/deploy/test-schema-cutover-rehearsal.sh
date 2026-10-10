#!/usr/bin/env bash
# Public-seam contract for the developer-owned H30 cutover rehearsal.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REHEARSAL="${SCRIPT_DIR}/schema-cutover-rehearsal.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

[[ -x "${REHEARSAL}" ]] || fail "missing executable schema-cutover-rehearsal.sh"

test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-schema-rehearsal-test.XXXXXX")"
cleanup() {
  rm -rf -- "${test_root}"
}
trap cleanup EXIT HUP INT TERM

if "${REHEARSAL}" >"${test_root}/without-approval.out" 2>"${test_root}/without-approval.err"; then
  fail "rehearsal must require explicit isolated authorization"
fi
grep -q 'LEZI_ISOLATED_SCHEMA_CUTOVER_REHEARSAL=1' "${test_root}/without-approval.err" \
  || fail "authorization failure must name the exact opt-in"

runner="${test_root}/runner.sh"
cat >"${runner}" <<'RUNNER'
#!/usr/bin/env bash
set -euo pipefail
action="$1"
case_dir="$2"
schema="$3"
printf '%s\t%s\t%s\n' "$(basename "${case_dir}")" "${schema}" "${action}" \
  >>"${LEZI_REHEARSAL_TEST_LOG:?}"

source_image="sha256:$(printf '%064d' "${schema}")"
target_image='sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
inventory="families=1;facts=2;versions=$((schema - 10));branches=$((schema - 11));conflicts=$((schema - 11));media=1;sessions=1"

write_receipt() {
  local path="$1" user_version="$2" image_id="$3" apk="${4:-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee}" metadata="${5:-ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff}"
  cat >"${path}" <<EOF
user_version=${user_version}
image_id=${image_id}
data_sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
tls_cert_sha256=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
tls_spki_sha256=dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
server_secret_sha256=9999999999999999999999999999999999999999999999999999999999999999
apk_sha256=${apk}
app_update_sha256=${metadata}
semantic_inventory=${inventory}
facts_sha256=1212121212121212121212121212121212121212121212121212121212121212
identity_sha256=1313131313131313131313131313131313131313131313131313131313131313
causal_sha256=1414141414141414141414141414141414141414141414141414141414141414
media_inventory_sha256=1515151515151515151515151515151515151515151515151515151515151515
health_receipt_sha256=1616161616161616161616161616161616161616161616161616161616161616
ready_status=ok
fact_read_status=ok
EOF
}

case "${action}" in
  prepare)
    mkdir -p "${case_dir}"
    printf '%064d\n' 7 >"${case_dir}/target-apk.sha256"
    printf '%064d\n' 8 >"${case_dir}/target-app-update.sha256"
    ;;
  capture_source) write_receipt "${case_dir}/source.receipt" "${schema}" "${source_image}" ;;
  capture_target)
    inventory="families=1;facts=2;versions=2;branches=$((schema - 11));conflicts=$((schema - 11));media=1;sessions=1"
    write_receipt "${case_dir}/target.receipt" 13 "${target_image}" "$(printf '%064d' 7)" "$(printf '%064d' 8)"
    ;;
  capture_rollback) write_receipt "${case_dir}/rollback.receipt" "${schema}" "${source_image}" ;;
  capture_rollback_service)
    cat >"${case_dir}/rollback-service.receipt" <<EOF
image_id=${source_image}
health_receipt_sha256=1717171717171717171717171717171717171717171717171717171717171717
fact_read_receipt_sha256=1818181818181818181818181818181818181818181818181818181818181818
ready_status=ok
fact_read_status=ok
EOF
    ;;
  rollback|cleanup) ;;
  *)
    if [[ "${LEZI_REHEARSAL_FAIL_PHASE:-}" == "${action}" ]]; then
      exit 42
    fi
    ;;
esac
RUNNER
chmod +x "${runner}"

adapter="${test_root}/h29-adapter.sh"
cat >"${adapter}" <<'ADAPTER'
#!/usr/bin/env bash
set -euo pipefail
phase="$1"
case_dir="${LEZI_REHEARSAL_CASE_DIR:?}"
schema="${LEZI_REHEARSAL_SOURCE_SCHEMA:?}"
runner="${LEZI_SCHEMA_CUTOVER_REHEARSAL_RUNNER:?}"
failure="${LEZI_REHEARSAL_FAIL_PHASE:-}"
run() { LEZI_REHEARSAL_FAIL_PHASE="${2:-}" "${runner}" "$1" "${case_dir}" "${schema}"; }
case "${phase}" in
  package_preflight) ;;
  update_lease_acquire|update_lease_release|lease_acquire|lease_release|source_preflight|credential_backup|stop_source|frozen_inventory|remove_write_gate) ;;
  app_update_prepublish) run apk_hash "${failure}" ;;
  rollback_backup) run backup "${failure}" ;;
  copy_out) run copy_out "${failure}" ;;
  migrate) run migrate "${failure}" ;;
  validate_migration) run validate "${failure}" ;;
  stage_copy_back) run copy_back "${failure}" ;;
  activate_target) run start "${failure}" ;;
  post_check) run health "${failure}" ;;
  restart_open) run open_writes ;;
  final_check) run health ;;
  rollback_preopen) run rollback ;;
  mark_manual_rollback) exit 1 ;;
  *) exit 1 ;;
esac
ADAPTER
chmod +x "${adapter}"

state_root="${test_root}/state"
mkdir -m 700 "${state_root}"
env \
  LEZI_ISOLATED_SCHEMA_CUTOVER_REHEARSAL=1 \
  LEZI_SCHEMA_CUTOVER_REHEARSAL_TEST_MODE=1 \
  LEZI_SCHEMA_CUTOVER_REHEARSAL_RUNNER="${runner}" \
  LEZI_SCHEMA_CUTOVER_REHEARSAL_H29_ADAPTER="${adapter}" \
  LEZI_REHEARSAL_TEST_LOG="${test_root}/actions.tsv" \
  LEZI_SCHEMA_CUTOVER_REHEARSAL_ROOT="${state_root}" \
  LEZI_REHEARSAL_HOST=127.0.0.1 \
  LEZI_REHEARSAL_HTTPS_PORT=28765 \
  LEZI_REHEARSAL_INTERNAL_PORT=28766 \
  "${REHEARSAL}" >"${test_root}/matrix.out"

matrix="${state_root}/matrix.tsv"
[[ -s "${matrix}" ]] || fail "matrix receipt is missing"
[[ "$(awk 'NR > 1 { count += 1 } END { print count + 0 }' "${matrix}")" == 18 ]] \
  || fail "matrix must contain two successes plus sixteen failure/rollback cases"
[[ "$(awk -F '\t' '$3 == "success" { count += 1 } END { print count + 0 }' "${matrix}")" == 2 ]] \
  || fail "both schema sources must have a successful schema-13 cutover"
[[ "$(awk -F '\t' '$3 == "rollback" { count += 1 } END { print count + 0 }' "${matrix}")" == 16 ]] \
  || fail "every pre-open failure must have an exact rollback receipt"
grep -q $'^12\thealth-failure\trollback\t' "${matrix}" \
  || fail "health failure must be covered by rollback"
grep -q $'^11\tapk_hash-failure\trollback\t' "${matrix}" \
  || fail "APK hash failure must be covered by rollback"
grep -q $'^schema11-apk_hash-failure\t11\tapk_hash$' "${test_root}/actions.tsv" \
  || fail "APK failure case must reach the app-update hash/publication action"
grep -q $'^schema11-apk_hash-failure\t11\tverify_rollback$' "${test_root}/actions.tsv" \
  || fail "APK publication failure must reopen and read the old service"
[[ -s "${state_root}/receipts.sha256" ]] || fail "closed receipt checksum set is missing"
if rg -n '192\.168\.[0-9]+\.[0-9]+|10\.[0-9]+\.[0-9]+\.[0-9]+|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]+\.[0-9]+|LEZI_BOOTSTRAP_SECRET' "${state_root}" >/dev/null; then
  fail "isolated receipts contain a production address or secret name"
fi

unsafe_root="${test_root}/unsafe"
mkdir -m 700 "${unsafe_root}"
if env \
    LEZI_ISOLATED_SCHEMA_CUTOVER_REHEARSAL=1 \
    LEZI_SCHEMA_CUTOVER_REHEARSAL_TEST_MODE=1 \
    LEZI_SCHEMA_CUTOVER_REHEARSAL_RUNNER="${runner}" \
    LEZI_REHEARSAL_TEST_LOG="${test_root}/unsafe-actions.tsv" \
    LEZI_SCHEMA_CUTOVER_REHEARSAL_ROOT="${unsafe_root}" \
    LEZI_REHEARSAL_HOST=192.168.77.10 \
    "${REHEARSAL}" >/dev/null 2>"${test_root}/unsafe.err"; then
  fail "rehearsal must reject non-loopback hosts"
fi
grep -q 'loopback' "${test_root}/unsafe.err" || fail "unsafe host error must explain the loopback gate"

if env \
    LEZI_SCHEMA_CUTOVER_REHEARSAL_ROOT="${test_root}" \
    LEZI_REHEARSAL_FAIL_PHASE=never \
    LEZI_REHEARSAL_SQLITE3_BIN=/bin/true \
    LEZI_REHEARSAL_SOURCE_ROOT_11=/ \
    LEZI_REHEARSAL_SOURCE_IMAGE_11=unused \
    "${SCRIPT_DIR}/schema-cutover-rehearsal-steps.sh" prepare "${test_root}/case" 11 \
    >/dev/null 2>"${test_root}/unsafe-root.err"; then
  fail "phase runner must reject source fixtures outside /tmp and /var/tmp"
fi
grep -q 'isolated fixture' "${test_root}/unsafe-root.err" \
  || fail "unsafe source-root error must explain the isolated fixture boundary"

echo "schema-cutover rehearsal contract smoke passed"
