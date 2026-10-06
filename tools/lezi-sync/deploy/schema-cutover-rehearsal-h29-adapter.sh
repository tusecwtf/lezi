#!/usr/bin/env bash
# Map the production H29 state machine onto developer-owned local Docker phases.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
phase="${1:-}"
case_dir="${LEZI_REHEARSAL_CASE_DIR:?}"
schema="${LEZI_REHEARSAL_SOURCE_SCHEMA:?}"
steps="${SCRIPT_DIR}/schema-cutover-rehearsal-steps.sh"
failure="${LEZI_REHEARSAL_FAIL_PHASE:-}"

run() {
  LEZI_REHEARSAL_FAIL_PHASE="${2:-}" "${steps}" "$1" "${case_dir}" "${schema}"
}

case "${phase}" in
  package_preflight) ;;
  update_lease_acquire|update_lease_release|lease_acquire|lease_release|source_preflight|credential_backup|stop_source|frozen_inventory|remove_write_gate)
    ;;
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
  rollback_preopen)
    run rollback
    ;;
  mark_manual_rollback)
    echo "error: isolated rehearsal must never fail after opening writes" >&2
    exit 1
    ;;
  *)
    echo "error: unknown H29 rehearsal phase ${phase}" >&2
    exit 1
    ;;
esac
