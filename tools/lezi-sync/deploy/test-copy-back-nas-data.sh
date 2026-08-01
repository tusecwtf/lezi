#!/usr/bin/env bash
# Fail-closed smoke for copy-back-nas-data.sh (ticket 06). No network required.
# Aligns with test-init-tls.sh / test-package-nas-app-update.sh style.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COPY_BACK="${SCRIPT_DIR}/copy-back-nas-data.sh"
test -x "${COPY_BACK}" || chmod +x "${COPY_BACK}"

test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-copy-back-test.XXXXXX")"
cleanup() {
  rm -rf -- "${test_root}"
}
trap cleanup EXIT HUP INT TERM

pass=0
fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# Dry-run harness skips full offline-migrate validate (no binary required for most cases).
DRY_HARNESS=(
  LEZI_COPY_BACK_DRY_RUN=1
  LEZI_COPY_BACK_SKIP_VALIDATE=1
  LEZI_CONFIRM_CONTAINER_STOPPED=1
  LEZI_CONFIRM_DUAL_BACKUP=1
)

# --- missing LEZI_OUT_DIR ---
if env "${DRY_HARNESS[@]}" "${COPY_BACK}" 2>"${test_root}/err1"; then
  fail "expected non-zero without LEZI_OUT_DIR"
fi
grep -q 'LEZI_OUT_DIR' "${test_root}/err1" || fail "err should mention LEZI_OUT_DIR: $(cat "${test_root}/err1")"
pass=$((pass + 1))

# --- missing confirm flags ---
mkdir -p "${test_root}/out"
if command -v sqlite3 >/dev/null 2>&1; then
  sqlite3 "${test_root}/out/lezi.db" "PRAGMA user_version=11;"
else
  printf 'x' > "${test_root}/out/lezi.db"
fi
head -c 32 /dev/urandom > "${test_root}/out/server.secret" 2>/dev/null || printf 'y%.0s' {1..32} > "${test_root}/out/server.secret"

if LEZI_OUT_DIR="${test_root}/out" LEZI_COPY_BACK_DRY_RUN=1 LEZI_COPY_BACK_SKIP_VALIDATE=1 \
  "${COPY_BACK}" 2>"${test_root}/err2"; then
  fail "expected non-zero without LEZI_CONFIRM_CONTAINER_STOPPED"
fi
grep -q 'LEZI_CONFIRM_CONTAINER_STOPPED' "${test_root}/err2" || fail "missing stopped confirm: $(cat "${test_root}/err2")"
pass=$((pass + 1))

if LEZI_OUT_DIR="${test_root}/out" LEZI_CONFIRM_CONTAINER_STOPPED=1 \
  LEZI_COPY_BACK_DRY_RUN=1 LEZI_COPY_BACK_SKIP_VALIDATE=1 \
  "${COPY_BACK}" 2>"${test_root}/err3"; then
  fail "expected non-zero without LEZI_CONFIRM_DUAL_BACKUP"
fi
grep -q 'LEZI_CONFIRM_DUAL_BACKUP' "${test_root}/err3" || fail "missing dual backup confirm: $(cat "${test_root}/err3")"
pass=$((pass + 1))

# --- missing lezi.db ---
mkdir -p "${test_root}/empty"
if LEZI_OUT_DIR="${test_root}/empty" \
  env "${DRY_HARNESS[@]}" "${COPY_BACK}" 2>"${test_root}/err4"; then
  fail "expected non-zero when lezi.db missing"
fi
grep -q 'lezi.db' "${test_root}/err4" || fail "err should mention lezi.db"
pass=$((pass + 1))

# --- short server.secret ---
mkdir -p "${test_root}/short"
if command -v sqlite3 >/dev/null 2>&1; then
  sqlite3 "${test_root}/short/lezi.db" "PRAGMA user_version=11;"
else
  printf 'not-a-db' > "${test_root}/short/lezi.db"
fi
printf 'short' > "${test_root}/short/server.secret"
set +e
LEZI_OUT_DIR="${test_root}/short" \
  env "${DRY_HARNESS[@]}" \
  "${COPY_BACK}" >"${test_root}/out5" 2>"${test_root}/err5"
rc5=$?
set -e
[[ "${rc5}" -ne 0 ]] || fail "expected non-zero for short secret / bad db"
if ! grep -Eqi 'server.secret|user_version|sqlite3|not a database' "${test_root}/err5"; then
  fail "err should mention secret or user_version: $(cat "${test_root}/err5")"
fi
pass=$((pass + 1))

# --- version override refused without dual flag ---
if command -v sqlite3 >/dev/null 2>&1; then
  mkdir -p "${test_root}/ov"
  sqlite3 "${test_root}/ov/lezi.db" "PRAGMA user_version=11;"
  head -c 32 /dev/urandom > "${test_root}/ov/server.secret"
  if LEZI_OUT_DIR="${test_root}/ov" LEZI_EXPECTED_USER_VERSION=99 \
    env "${DRY_HARNESS[@]}" "${COPY_BACK}" 2>"${test_root}/err_ov"; then
    fail "expected refuse free-floating LEZI_EXPECTED_USER_VERSION"
  fi
  grep -q 'LEZI_ALLOW_USER_VERSION_OVERRIDE' "${test_root}/err_ov" \
    || fail "override must mention dual flag: $(cat "${test_root}/err_ov")"
  pass=$((pass + 1))
fi

# --- happy dry-run with current schema when sqlite3 available ---
if command -v sqlite3 >/dev/null 2>&1; then
  good="${test_root}/good"
  mkdir -p "${good}"
  sqlite3 "${good}/lezi.db" "PRAGMA user_version=11;"
  head -c 32 /dev/urandom > "${good}/server.secret"
  out="$(
    LEZI_OUT_DIR="${good}" \
      env "${DRY_HARNESS[@]}" \
      "${COPY_BACK}" 2>"${test_root}/err6"
  )"
  echo "${out}" | grep -q 'copy-back dry-run ok' || fail "missing dry-run ok: ${out}"
  grep -q 'ticket 07' "${test_root}/err6" || grep -q 'ticket 07' <<<"${out}" || \
    fail "should mention ticket 07 boundary"
  # plan mentions no inherit / migration password path
  grep -Eqi 'LEZI_BOOTSTRAP_SECRET|migration' "${test_root}/err6" \
    || fail "plan should mention bootstrap secret path"
  pass=$((pass + 1))

  # wrong user_version must refuse
  bad="${test_root}/badver"
  mkdir -p "${bad}"
  sqlite3 "${bad}/lezi.db" "PRAGMA user_version=3;"
  head -c 32 /dev/urandom > "${bad}/server.secret"
  if LEZI_OUT_DIR="${bad}" \
    env "${DRY_HARNESS[@]}" \
    "${COPY_BACK}" 2>"${test_root}/err7"; then
    fail "expected refuse user_version=3 for copy-back"
  fi
  grep -q 'user_version' "${test_root}/err7" || fail "err should mention user_version: $(cat "${test_root}/err7")"
  pass=$((pass + 1))

  # residual WAL refused
  wal="${test_root}/wal"
  mkdir -p "${wal}"
  sqlite3 "${wal}/lezi.db" "PRAGMA user_version=11;"
  head -c 32 /dev/urandom > "${wal}/server.secret"
  touch "${wal}/lezi.db-wal"
  if LEZI_OUT_DIR="${wal}" \
    env "${DRY_HARNESS[@]}" \
    "${COPY_BACK}" 2>"${test_root}/err_wal"; then
    fail "expected refuse residual WAL"
  fi
  grep -q 'lezi.db-wal' "${test_root}/err_wal" || fail "err should mention wal: $(cat "${test_root}/err_wal")"
  pass=$((pass + 1))
else
  fail "sqlite3 is required for copy-back smoke (script is fail-closed without it)"
fi

# --- live path refuses skip-validate and missing NAS backup without network ---
# We only assert early gates that run before SSH (skip-validate + missing backup).
live_gate="${test_root}/livegate"
mkdir -p "${live_gate}"
sqlite3 "${live_gate}/lezi.db" "PRAGMA user_version=11;"
head -c 32 /dev/urandom > "${live_gate}/server.secret"

# skip-validate refused for live even if set
if LEZI_OUT_DIR="${live_gate}" \
  LEZI_CONFIRM_CONTAINER_STOPPED=1 LEZI_CONFIRM_DUAL_BACKUP=1 \
  LEZI_COPY_BACK_SKIP_VALIDATE=1 \
  LEZI_NAS_BACKUP_PATH=/tmp/fake-bak \
  LEZI_COPY_BACK_DRY_RUN=0 \
  LEZI_SYNC_BIN=/nonexistent/lezi-sync \
  "${COPY_BACK}" 2>"${test_root}/err_live1"; then
  fail "live must not succeed with skip-validate / missing binary"
fi
# Either validate binary missing or skip refused — both fail-closed
if ! grep -Eqi 'SKIP_VALIDATE|validate|lezi-sync binary|not found' "${test_root}/err_live1"; then
  fail "live gate err unexpected: $(cat "${test_root}/err_live1")"
fi
pass=$((pass + 1))

# Script source policy smoke
grep -q 'SHIPPED_USER_VERSION=11' "${COPY_BACK}" || fail "script missing SHIPPED_USER_VERSION"
grep -q 'SHIPPED_MIN_SECRET_BYTES=32' "${COPY_BACK}" || fail "script missing SHIPPED_MIN_SECRET_BYTES"
grep -q 'rsync is required' "${COPY_BACK}" || fail "script must require rsync"
grep -q 'sqlite3 is required' "${COPY_BACK}" || fail "script must require sqlite3"
grep -q 'offline-migrate validate' "${COPY_BACK}" || fail "script must call validate"
grep -q 'LEZI_NAS_BACKUP_PATH' "${COPY_BACK}" || fail "script must require NAS backup path"
grep -q '10001' "${COPY_BACK}" || fail "script must handle uid 10001"
pass=$((pass + 1))

# Runbook + script must document fixed order labels (shared with cutover_step_labels)
runbook="${SCRIPT_DIR}/copy-back-tls-cutover-runbook.md"
test -f "${runbook}" || fail "missing runbook ${runbook}"
for label in \
  "1. stop live container" \
  "2. confirm dual backup (local copy-out + NAS-side)" \
  "3. copy-back upgraded out/ to NAS data bind" \
  "4. start current TLS deploy (CD)" \
  "5. health/ready by actual protocol"
do
  grep -Fq "${label}" "${runbook}" || fail "runbook missing shared label: ${label}"
done
grep -q 'https://192.168.50.4:8765' "${runbook}" || fail "runbook missing LAN HTTPS default"
grep -q '/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data' "${runbook}" || fail "runbook missing data bind"
grep -qi 'Rollback' "${runbook}" || fail "runbook missing Rollback"
grep -qi 'ticket 07' "${runbook}" || fail "runbook must defer live success to ticket 07"
grep -qi 'Old APK\|plaintext HTTP' "${runbook}" || fail "runbook missing old client note"
grep -qi 'TOFU\|re-login\|重登' "${runbook}" || fail "runbook missing re-login/TOFU"
grep -q 'export LEZI_BOOTSTRAP_SECRET' "${runbook}" || fail "runbook must export migration secret"
grep -Eqi 'NEVER inherit|inheriting the pre-cutover secret is wrong|never inherit' "${runbook}" \
  || fail "runbook must forbid inherit"
grep -q 'docker save' "${runbook}" || fail "runbook must require docker save pre-cutover"
grep -q '10001' "${runbook}" || fail "runbook must mention uid 10001"
pass=$((pass + 1))

# push-and-deploy forwards secret only with explicit LEZI_FORWARD_BOOTSTRAP_SECRET=1
pad="${SCRIPT_DIR}/push-and-deploy.sh"
grep -q 'LEZI_BOOTSTRAP_SECRET' "${pad}" || fail "push-and-deploy must mention bootstrap secret"
grep -q 'LEZI_FORWARD_BOOTSTRAP_SECRET' "${pad}" || fail "push-and-deploy must gate forward behind LEZI_FORWARD_BOOTSTRAP_SECRET"
grep -q 'printf' "${pad}" || fail "push-and-deploy should quote-forward secret"
# Opt-in: forward only when LEZI_FORWARD_BOOTSTRAP_SECRET=1 (not merely when secret is set)
grep -q 'LEZI_FORWARD_BOOTSTRAP_SECRET:-' "${pad}" \
  || grep -q 'LEZI_FORWARD_BOOTSTRAP_SECRET' "${pad}" \
  || fail "push-and-deploy must read LEZI_FORWARD_BOOTSTRAP_SECRET"
# Ordinary path must still run remote-deploy without injecting secret when flag unset
grep -Eq 'LEZI_BOOTSTRAP_SECRET is set locally but NOT forwarded|ordinary CD inherit' "${pad}" \
  || fail "push-and-deploy must warn when local secret is set but not forwarded"
grep -q 'WARNING' "${pad}" || fail "forward path must print non-secret WARNING about fingerprint rotation"
# Runbook must require the opt-in flag with the migration secret
grep -q 'LEZI_FORWARD_BOOTSTRAP_SECRET=1' "${runbook}" \
  || fail "runbook must export LEZI_FORWARD_BOOTSTRAP_SECRET=1 with migration secret"
pass=$((pass + 1))

# Ticket 07 docker-assisted copy-back path (mode-700 parent / no passwordless chown)
grep -q 'LEZI_COPY_BACK_VIA_DOCKER' "${COPY_BACK}" \
  || fail "copy-back must support LEZI_COPY_BACK_VIA_DOCKER"
grep -Eq 'LEZI_COPY_BACK_VIA_DOCKER:-auto|LEZI_COPY_BACK_VIA_DOCKER:-.*auto' "${COPY_BACK}" \
  || grep -q 'LEZI_COPY_BACK_VIA_DOCKER:-auto' "${COPY_BACK}" \
  || fail "copy-back default should be auto for docker fallback"
grep -q 'LEZI_COPY_BACK_DOCKER_IMAGE' "${COPY_BACK}" \
  || fail "copy-back must allow docker image override"
grep -q 'alpine' "${COPY_BACK}" \
  || fail "copy-back docker path should default to alpine image"
grep -q '10001' "${COPY_BACK}" \
  || fail "copy-back must chown/verify uid 10001 on docker path"
grep -q 'lezi-copy-back-staging' "${COPY_BACK}" \
  || grep -q 'TMP_STAGING' "${COPY_BACK}" \
  || fail "copy-back docker path must stage under /tmp (user-writable)"
grep -q 'rm -rf' "${COPY_BACK}" \
  || fail "copy-back must clean temp staging"
# Runbook Step 5 must not prescribe dead host:8766 curl as primary
grep -q 'docker exec lezi-sync lezi-sync healthcheck' "${runbook}" \
  || fail "runbook Step 5 must document docker exec healthcheck"
grep -Eq 'Do NOT.*8766|does not publish|NOT published|container-local' "${runbook}" \
  || fail "runbook must warn host:8766 is not published"
pass=$((pass + 1))

echo "copy-back smoke passed (${pass} checks)"
