#!/usr/bin/env bash
# Public-seam contract and failure-injection tests for the dedicated schema cutover.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CUTOVER="${SCRIPT_DIR}/schema-cutover.sh"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-schema-cutover-test.XXXXXX")"
cleanup() {
  rm -rf -- "${test_root}"
}
trap cleanup EXIT HUP INT TERM

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

[[ -x "${CUTOVER}" ]] || fail "missing executable schema-cutover.sh"

runner="${test_root}/runner.sh"
cat >"${runner}" <<'RUNNER'
#!/usr/bin/env bash
set -euo pipefail
phase="$1"
printf '%s\n' "${phase}" >>"${LEZI_SCHEMA_CUTOVER_TEST_LOG:?}"
if [[ "${phase}" == app_update_prepublish \
    && "${LEZI_SCHEMA_CUTOVER_SIMULATE_MUTATION:-0}" == 1 ]]; then
  printf 'started\n' >"${LEZI_SCHEMA_CUTOVER_STATE_DIR}/app-update-mutation-started"
fi
if [[ "${phase}" == app_update_prepublish \
    && "${LEZI_SCHEMA_CUTOVER_SIMULATE_PROBE_FAILURE:-0}" == 1 ]]; then
  printf 'uncertain\n' >"${LEZI_SCHEMA_CUTOVER_STATE_DIR}/app-update-mutation-started"
  exit 43
fi
if [[ "${phase}" == rollback_preopen ]]; then
  printf 'complete\n' >"${LEZI_SCHEMA_CUTOVER_STATE_DIR}/app-update-restore-complete"
fi
if [[ "${phase}" == "${LEZI_SCHEMA_CUTOVER_FAIL_PHASE:-}" ]]; then
  exit 42
fi
RUNNER
chmod +x "${runner}"

common=(
  LEZI_SCHEMA_CUTOVER_TEST_MODE=1
  LEZI_SCHEMA_CUTOVER_STEP_RUNNER="${runner}"
  LEZI_SCHEMA_CUTOVER_TEST_LOG="${test_root}/phases"
  LEZI_SCHEMA_CUTOVER_STATE_DIR="${test_root}/state"
)

if env "${common[@]}" "${CUTOVER}" 2>"${test_root}/approval.err"; then
  fail "cutover must require the exact maintenance approval"
fi
grep -q 'LEZI_SCHEMA_CUTOVER_APPROVAL' "${test_root}/approval.err" \
  || fail "approval failure must name the required variable"

approval='I_ACKNOWLEDGE_0_4_0_SCHEMA_CUTOVER'
env "${common[@]}" LEZI_SCHEMA_CUTOVER_APPROVAL="${approval}" "${CUTOVER}"
expected_success="${test_root}/expected-success"
cat >"${expected_success}" <<'EOF'
package_preflight
update_lease_acquire
app_update_prepublish
lease_acquire
source_preflight
credential_backup
stop_source
frozen_inventory
rollback_backup
copy_out
migrate
validate_migration
stage_copy_back
activate_target
post_check
remove_write_gate
restart_open
final_check
lease_release
update_lease_release
EOF
cmp -s "${expected_success}" "${test_root}/phases" \
  || fail "success phase order drifted: $(tr '\n' ' ' <"${test_root}/phases")"

# A failure after prepublish but before stop must restore the old APK pair.
: >"${test_root}/phases"
if env "${common[@]}" LEZI_SCHEMA_CUTOVER_APPROVAL="${approval}" \
  LEZI_SCHEMA_CUTOVER_FAIL_PHASE=source_preflight "${CUTOVER}"; then
  fail "source preflight injection must fail"
fi
expected_pre_stop="${test_root}/expected-pre-stop"
cat >"${expected_pre_stop}" <<'EOF'
package_preflight
update_lease_acquire
app_update_prepublish
lease_acquire
source_preflight
rollback_preopen
lease_release
update_lease_release
EOF
cmp -s "${expected_pre_stop}" "${test_root}/phases" \
  || fail "pre-stop failure did not restore the forced-update channel"

# An early prepublish failure has not changed the live pair, so it only releases
# the publication lease. A later data-lease failure restores the published pair.
: >"${test_root}/phases"
if env "${common[@]}" LEZI_SCHEMA_CUTOVER_APPROVAL="${approval}" \
    LEZI_SCHEMA_CUTOVER_FAIL_PHASE=app_update_prepublish "${CUTOVER}"; then
  fail "app_update_prepublish injection must fail"
fi
if grep -qx rollback_preopen "${test_root}/phases"; then
  fail "failed prepublish must not restore from an unready rollback pair"
fi
[[ "$(tail -n 1 "${test_root}/phases")" == update_lease_release ]] \
  || fail "failed prepublish did not release the publication lease"

: >"${test_root}/phases"
rm -f "${test_root}/state/app-update-mutation-started" \
  "${test_root}/state/app-update-restore-complete"
if env "${common[@]}" LEZI_SCHEMA_CUTOVER_APPROVAL="${approval}" \
    LEZI_SCHEMA_CUTOVER_SIMULATE_MUTATION=1 \
    LEZI_SCHEMA_CUTOVER_FAIL_PHASE=app_update_prepublish "${CUTOVER}"; then
  fail "mutated app_update_prepublish injection must fail"
fi
grep -qx rollback_preopen "${test_root}/phases" \
  || fail "mutated prepublish failure did not retry prior-pair restore"
[[ "$(tail -n 1 "${test_root}/phases")" == update_lease_release ]] \
  || fail "restored prepublish failure did not release the publication lease"

: >"${test_root}/phases"
rm -f "${test_root}/state/app-update-mutation-started" \
  "${test_root}/state/app-update-restore-complete"
if env "${common[@]}" LEZI_SCHEMA_CUTOVER_APPROVAL="${approval}" \
    LEZI_SCHEMA_CUTOVER_SIMULATE_PROBE_FAILURE=1 "${CUTOVER}"; then
  fail "uncertain marker probe injection must fail"
fi
grep -qx rollback_preopen "${test_root}/phases" \
  || fail "uncertain marker probe did not force conservative restore"


for fail_phase in lease_acquire; do
  : >"${test_root}/phases"
  if env "${common[@]}" LEZI_SCHEMA_CUTOVER_APPROVAL="${approval}" \
      LEZI_SCHEMA_CUTOVER_FAIL_PHASE="${fail_phase}" "${CUTOVER}"; then
    fail "${fail_phase} injection must fail"
  fi
  grep -qx rollback_preopen "${test_root}/phases" \
    || fail "${fail_phase} failure did not restore the APK pair"
  [[ "$(tail -n 1 "${test_root}/phases")" == update_lease_release ]] \
    || fail "${fail_phase} failure did not release the publication lease"
done

# A failure after stop but before activation must restore the old bind/container.
: >"${test_root}/phases"
if env "${common[@]}" LEZI_SCHEMA_CUTOVER_APPROVAL="${approval}" \
  LEZI_SCHEMA_CUTOVER_FAIL_PHASE=validate_migration "${CUTOVER}"; then
  fail "migration validation injection must fail"
fi
grep -qx 'rollback_preopen' "${test_root}/phases" \
  || fail "pre-open failure must run automatic rollback"
tail -n 2 "${test_root}/phases" | grep -qx lease_release \
  || fail "data lease must release before the publication lease"

# A read-only target that fails post-check still rolls back automatically.
: >"${test_root}/phases"
if env "${common[@]}" LEZI_SCHEMA_CUTOVER_APPROVAL="${approval}" \
  LEZI_SCHEMA_CUTOVER_FAIL_PHASE=post_check "${CUTOVER}"; then
  fail "post-check injection must fail"
fi
grep -qx 'rollback_preopen' "${test_root}/phases" \
  || fail "read-only target failure must roll back automatically"

# Only a failure after the write gate opens requires manual reconciliation.
: >"${test_root}/phases"
if env "${common[@]}" LEZI_SCHEMA_CUTOVER_APPROVAL="${approval}" \
  LEZI_SCHEMA_CUTOVER_FAIL_PHASE=final_check "${CUTOVER}"; then
  fail "final-check injection must fail"
fi
grep -qx 'mark_manual_rollback' "${test_root}/phases" \
  || fail "post-open failure must mark the manual rollback boundary"
if grep -qx 'rollback_preopen' "${test_root}/phases"; then
  fail "post-open failure must not automatically overwrite schema-13 writes"
fi
tail -n 2 "${test_root}/phases" | grep -qx lease_release \
  || fail "data lease must release after post-open failure"

# Ordinary CD must contain no path that invokes the offline migrator.
if rg -n 'offline-migrate[[:space:]]+(dry-run|migrate|validate)' \
  "${SCRIPT_DIR}/push-and-deploy.sh" >/dev/null; then
  fail "ordinary push-and-deploy must not invoke offline-migrate"
fi

# The closed package carries current guards and fixed release/cutover identity,
# never a database, credential, or rollback ciphertext.
package_script="${SCRIPT_DIR}/package-nas.sh"
validator="${SCRIPT_DIR}/validate-nas-package.sh"
for helper in schema-cutover.sh schema-cutover-steps.sh; do
  grep -Fq "${helper}" "${package_script}" || fail "package omits ${helper}"
  grep -Fq "${helper}" "${validator}" || fail "validator omits ${helper}"
done
grep -Fq '0.3.12:11|0.3.13:12' "${validator}" \
  || fail "validator must accept attested 0.3.12/11 or 0.3.13/12 rollback sources"
grep -Fq '0.3.12 rollback packages must declare server schema 11' "${validator}" \
  || fail "validator must pin 0.3.12 packages to schema 11"
grep -Fq 'local-data contract skip is only for attested 0.3.12/0.3.13 rollback APKs' \
  "${package_script}" \
  || fail "0.3.12/0.3.13 rollback packages must not require the current local-data ledger"
for fixed in \
  'package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-13}"' \
  'rollback_source_version="${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION:-0.3.13}"' \
  'rollback_source_server_schema="${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA:-12}"' \
  '0.3.12:11|0.3.13:12' \
  'package_android_version_code' \
  'package_min_supported_version_code'
do
  grep -Fq "${fixed}" "${package_script}" || fail "package identity omits ${fixed}"
done
if rg -n 'cp .*(lezi\.db|server\.secret|\.age)' "${package_script}" >/dev/null; then
  fail "release package must not carry DB, credentials, or rollback ciphertext"
fi

# The inherited outer lease is usable only by the exact maintenance approval.
grep -Fq 'LEZI_INHERITED_DEPLOY_LOCK_TOKEN' "${SCRIPT_DIR}/push-and-deploy.sh" \
  || fail "push must accept the already-held outer cutover lease"
grep -Fq 'I_ACKNOWLEDGE_0_4_0_SCHEMA_CUTOVER' "${SCRIPT_DIR}/push-and-deploy.sh" \
  || fail "inherited lease must require cutover approval"

# Production runner contracts: both packages are fully re-attested before NAS
# mutation; the stopped source owns rollback bytes; target writes stay gated
# through semantic/TLS/image checks.
runner_source="${SCRIPT_DIR}/schema-cutover-steps.sh"
[[ "$(rg -n '^app_update_prepublish$|^lease_acquire$' "${expected_success}" | cut -d: -f1 | tr '\n' ' ')" == '3 4 ' ]] \
  || fail "verified APK publication must precede the exclusive cutover lease"
grep -Fq 'LEZI_DEPLOY_PREFLIGHT_ONLY=1' "${runner_source}" \
  || fail "target and rollback packages need full local deploy preflight"
grep -Fq 'frozen-source-inventory.txt' "${runner_source}" \
  || fail "stopped-source rollback must bind to a frozen full inventory"
grep -Fq '.schema-cutover-read-only' "${runner_source}" \
  || fail "target activation must retain a write gate through post-check"
grep -Fq 'postcheck-semantic-inventory.txt' "${runner_source}" \
  || fail "post-check must compare migrated semantic inventory"
grep -Fq '"${PACKAGE_DIR}/app-update/app-release.apk"' "${runner_source}" \
  || fail "validated schema-13 root must contain the signer-attested target APK"
grep -Fq '"${PACKAGE_DIR}/app-update/app-update.json"' "${runner_source}" \
  || fail "validated schema-13 root must contain the attested target metadata"
grep -Fq 'LEZI_SYNC_VERSION="$(rollback_source_version)"' "${runner_source}" \
  || fail "automatic pre-open rollback must recreate the attested source service"
grep -Fq 'docker run --rm --user 10001:10001' "${runner_source}" \
  || fail "app-update prepublish must install the pair as uid 10001 on the data bind"
grep -Fq '0.3.12:11|0.3.13:12' "${runner_source}" \
  || fail "cutover must accept exactly the attested 0.3.12/11 or 0.3.13/12 source tuples"
grep -Fq 'lezi-sync:${rollback_version}' "${runner_source}" \
  || fail "live container image tag must match the attested rollback package"

# Execute the production stopped-source rollback phase with isolated command
# doubles. This covers the real ssh -> tar -> age pipeline and its frozen
# inventory comparison without contacting a NAS.
production_root="${test_root}/production"
mkdir -p "${production_root}/bin" "${production_root}/state" \
  "${production_root}/config" "${production_root}/backups"
chmod 700 "${production_root}/state" "${production_root}/backups"
printf 'recipient\n' >"${production_root}/config/recipients.txt"
printf '0123456789abcdef0123456789abcdef\n' >"${production_root}/state/operation-id"
printf 'schema=11\nabc  ./lezi.db\n' >"${production_root}/state/frozen-source-inventory.txt"
cat >"${production_root}/state/rollback-package-manifest.json" <<'EOF'
{
  "version": "0.3.12",
  "server_schema": "11",
  "image": "lezi-sync:0.3.12",
  "image_id": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
EOF
cat >"${production_root}/bin/ssh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
joined="$*"
if [[ "${joined}" == *'docker inspect'* ]]; then
  printf '[{"Id":"source"}]\n'
elif [[ "${joined}" == *'find . -type f'* ]]; then
  if [[ "${LEZI_TEST_INVENTORY_MISMATCH:-0}" == 1 ]]; then
    printf 'changed  ./lezi.db\n'
  else
    printf 'abc  ./lezi.db\n'
  fi
elif [[ "${joined}" == *'tar -C /source'* ]]; then
  printf 'frozen-tar-stream'
else
  exit 97
fi
EOF
cat >"${production_root}/bin/age" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
output=''
while [[ "$#" -gt 0 ]]; do
  if [[ "$1" == '--output' ]]; then
    output="$2"
    shift 2
  else
    shift
  fi
done
[[ -n "${output}" ]]
cat >"${output}"
EOF
chmod +x "${production_root}/bin/ssh" "${production_root}/bin/age"
env PATH="${production_root}/bin:${PATH}" \
  LEZI_SCHEMA_CUTOVER_STATE_DIR="${production_root}/state" \
  LEZI_SCHEMA_CUTOVER_ROLLBACK_DIR="${production_root}/backups" \
  LEZI_AGE_RECIPIENTS_FILE="${production_root}/config/recipients.txt" \
  LEZI_DATA_HOST_PATH=/tmp/lezi-schema-cutover-test/data \
  "${runner_source}" rollback_backup
[[ -s "${production_root}/backups/lezi-schema11-data-0123456789abcdef0123456789abcdef.tar.age" ]] \
  || fail "production rollback phase did not create the encrypted full-data stream"
[[ -s "${production_root}/backups/lezi-schema11-start-0123456789abcdef0123456789abcdef.json.age" ]] \
  || fail "production rollback phase did not create the encrypted start contract"

failure_state="${production_root}/failure-state"
mkdir -m 700 "${failure_state}"
printf 'fedcba9876543210fedcba9876543210\n' >"${failure_state}/operation-id"
printf 'schema=11\nabc  ./lezi.db\n' >"${failure_state}/frozen-source-inventory.txt"
cp "${production_root}/state/rollback-package-manifest.json" "${failure_state}/rollback-package-manifest.json"
if env PATH="${production_root}/bin:${PATH}" LEZI_TEST_INVENTORY_MISMATCH=1 \
    LEZI_SCHEMA_CUTOVER_STATE_DIR="${failure_state}" \
    LEZI_SCHEMA_CUTOVER_ROLLBACK_DIR="${production_root}/backups" \
    LEZI_AGE_RECIPIENTS_FILE="${production_root}/config/recipients.txt" \
    LEZI_DATA_HOST_PATH=/tmp/lezi-schema-cutover-test/data \
    "${runner_source}" rollback_backup >/dev/null 2>&1; then
  fail "production rollback phase accepted stopped-source inventory drift"
fi
[[ ! -e "${production_root}/backups/lezi-schema11-data-fedcba9876543210fedcba9876543210.tar.age" ]] \
  || fail "failed rollback capture promoted ciphertext after inventory drift"

# Schema-12 identity still derives backup names from the attested rollback package.
schema12_state="${production_root}/schema12-state"
mkdir -m 700 "${schema12_state}"
printf 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n' >"${schema12_state}/operation-id"
printf 'schema=12\nabc  ./lezi.db\n' >"${schema12_state}/frozen-source-inventory.txt"
cat >"${schema12_state}/rollback-package-manifest.json" <<'EOF'
{
  "version": "0.3.13",
  "server_schema": "12",
  "image": "lezi-sync:0.3.13",
  "image_id": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
EOF
env PATH="${production_root}/bin:${PATH}" \
  LEZI_SCHEMA_CUTOVER_STATE_DIR="${schema12_state}" \
  LEZI_SCHEMA_CUTOVER_ROLLBACK_DIR="${production_root}/backups" \
  LEZI_AGE_RECIPIENTS_FILE="${production_root}/config/recipients.txt" \
  LEZI_DATA_HOST_PATH=/tmp/lezi-schema-cutover-test/data \
  "${runner_source}" rollback_backup
[[ -s "${production_root}/backups/lezi-schema12-data-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.tar.age" ]] \
  || fail "schema-12 attested source did not derive schema-12 backup names"

# Executed fail-closed identity: unattested tuples and image-tag drift never
# create a rollback ciphertext. Schema-11 success already ran above.
reject_identity() {
  local name="$1"
  local state="${production_root}/${name}-state"
  mkdir -m 700 "${state}"
  printf 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n' >"${state}/operation-id"
  printf 'schema=11\nabc  ./lezi.db\n' >"${state}/frozen-source-inventory.txt"
  cat >"${state}/rollback-package-manifest.json"
  if env PATH="${production_root}/bin:${PATH}" \
      LEZI_SCHEMA_CUTOVER_STATE_DIR="${state}" \
      LEZI_SCHEMA_CUTOVER_ROLLBACK_DIR="${production_root}/backups" \
      LEZI_AGE_RECIPIENTS_FILE="${production_root}/config/recipients.txt" \
      LEZI_DATA_HOST_PATH=/tmp/lezi-schema-cutover-test/data \
      "${runner_source}" rollback_backup >/dev/null 2>&1; then
    fail "${name} identity must fail closed"
  fi
  [[ ! -e "${production_root}/backups/lezi-schema11-data-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.tar.age" ]] \
    || fail "${name} identity promoted ciphertext"
  [[ ! -e "${production_root}/backups/lezi-schema10-data-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.tar.age" ]] \
    || fail "${name} identity promoted ciphertext under a drifted schema name"
}

reject_identity unattested-version <<'EOF'
{
  "version": "0.3.11",
  "server_schema": "10",
  "image": "lezi-sync:0.3.11",
  "image_id": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
EOF
reject_identity crossed-tuple <<'EOF'
{
  "version": "0.3.12",
  "server_schema": "12",
  "image": "lezi-sync:0.3.12",
  "image_id": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
EOF
reject_identity image-tag-drift <<'EOF'
{
  "version": "0.3.12",
  "server_schema": "11",
  "image": "lezi-sync:0.3.13",
  "image_id": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
EOF

echo "schema-cutover state-machine smoke passed"
