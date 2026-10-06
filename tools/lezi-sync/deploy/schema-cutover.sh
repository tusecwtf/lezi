#!/usr/bin/env bash
# Dedicated 0.4.0/schema-13 maintenance orchestration. Ordinary CD never calls this.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APPROVAL='I_ACKNOWLEDGE_0_4_0_SCHEMA_CUTOVER'
test_mode="${LEZI_SCHEMA_CUTOVER_TEST_MODE:-0}"

die() {
  echo "error: schema cutover: $*" >&2
  exit 1
}

if [[ "${LEZI_SCHEMA_CUTOVER_APPROVAL:-}" != "${APPROVAL}" ]]; then
  die "set LEZI_SCHEMA_CUTOVER_APPROVAL=${APPROVAL} only for the confirmed maintenance window"
fi
if [[ "${test_mode}" != "0" && "${test_mode}" != "1" ]]; then
  die "LEZI_SCHEMA_CUTOVER_TEST_MODE must be 0 or 1"
fi

if [[ "${test_mode}" == "1" ]]; then
  runner="${LEZI_SCHEMA_CUTOVER_STEP_RUNNER:-}"
  [[ -n "${runner}" && -x "${runner}" ]] \
    || die "test mode requires an executable LEZI_SCHEMA_CUTOVER_STEP_RUNNER"
else
  [[ -z "${LEZI_SCHEMA_CUTOVER_STEP_RUNNER:-}" ]] \
    || die "custom step runners are test-only"
  runner="${SCRIPT_DIR}/schema-cutover-steps.sh"
  [[ -x "${runner}" ]] || die "missing executable production step runner: ${runner}"
fi

state_dir="${LEZI_SCHEMA_CUTOVER_STATE_DIR:-}"
if [[ -z "${state_dir}" ]]; then
  config_root="${XDG_CONFIG_HOME:-${HOME:?HOME is required when XDG_CONFIG_HOME is unset}/.config}"
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  state_dir="${config_root}/lezi/schema-cutovers/0.4.0-${stamp}-${BASHPID}"
fi
if [[ "${state_dir}" != /* ]]; then
  die "LEZI_SCHEMA_CUTOVER_STATE_DIR must be absolute"
fi
state_dir="$(realpath -m -- "${state_dir}")"
repo_root="$(realpath -- "${SCRIPT_DIR}/../../..")"
case "${state_dir}/" in
  /|"${repo_root}/"*)
    die "cutover state must be outside the repository and filesystem root"
    ;;
esac
if [[ -L "${state_dir}" ]]; then
  die "cutover state directory must not be a symlink"
fi
if [[ ! -e "${state_dir}" ]]; then
  install -d -m 700 -- "${state_dir}"
elif [[ ! -d "${state_dir}" || "$(stat -c '%a' "${state_dir}")" != "700" ]]; then
  die "existing cutover state directory must be a mode-700 directory"
elif [[ "${test_mode}" != "1" && -n "$(find "${state_dir}" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  die "existing cutover state directory must be empty; never resume an ambiguous maintenance run"
fi
export LEZI_SCHEMA_CUTOVER_STATE_DIR="${state_dir}"

lease_acquired=0
update_lease_acquired=0
update_published=0
writes_may_be_open=0
cleanup_started=0

run_phase() {
  local phase="$1"
  printf '%s\n' "${phase}" >"${state_dir}/current-phase"
  chmod 600 "${state_dir}/current-phase"
  "${runner}" "${phase}"
  printf '%s\n' "${phase}" >>"${state_dir}/completed-phases"
  chmod 600 "${state_dir}/completed-phases"
}

finish() {
  local original_status=$?
  if [[ "${cleanup_started}" == "1" ]]; then
    exit "${original_status}"
  fi
  cleanup_started=1
  trap - EXIT HUP INT TERM

  if [[ "${original_status}" -ne 0 && "${update_published}" == "1" ]]; then
    if [[ "${writes_may_be_open}" == "0" ]]; then
      if ! run_phase rollback_preopen; then
        echo "error: automatic pre-open rollback failed; keep the lease and inspect ${state_dir}" >&2
        exit 1
      fi
    else
      if ! run_phase mark_manual_rollback; then
        echo "error: could not persist the manual-rollback marker in ${state_dir}" >&2
      fi
    fi
  fi
  if [[ "${original_status}" -ne 0 && "${writes_may_be_open}" == "0" \
      && "${update_published}" == "0" \
      && -f "${state_dir}/app-update-mutation-started" \
      && ! -f "${state_dir}/app-update-restore-complete" ]]; then
    if ! run_phase rollback_preopen; then
      echo "error: app-update recovery failed; publication lease remains held for incident recovery" >&2
      exit 1
    fi
  fi

  if [[ "${lease_acquired}" == "1" ]]; then
    if ! run_phase lease_release; then
      echo "error: failed to release the schema-cutover lease; inspect it before retrying" >&2
      exit 1
    fi
  fi
  if [[ "${update_lease_acquired}" == "1" ]]; then
    if ! run_phase update_lease_release; then
      echo "error: failed to release the app-update publication lease" >&2
      exit 1
    fi
  fi
  exit "${original_status}"
}
trap finish EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

run_phase package_preflight
run_phase update_lease_acquire
update_lease_acquired=1
run_phase app_update_prepublish
update_published=1
run_phase lease_acquire
lease_acquired=1
run_phase source_preflight
run_phase credential_backup
run_phase stop_source
run_phase frozen_inventory
run_phase rollback_backup
run_phase copy_out
run_phase migrate
run_phase validate_migration
run_phase stage_copy_back

run_phase activate_target
run_phase post_check
run_phase remove_write_gate

# The running process still holds its read-only configuration after marker
# removal. The next restart is the first operation that may expose writes.
writes_may_be_open=1
run_phase restart_open
run_phase final_check

update_published=0
run_phase lease_release
lease_acquired=0
run_phase update_lease_release
update_lease_acquired=0
printf '%s\n' complete >"${state_dir}/status"
chmod 600 "${state_dir}/status"
echo "schema cutover complete"
echo "state=${state_dir}"
