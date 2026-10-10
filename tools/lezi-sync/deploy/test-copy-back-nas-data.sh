#!/usr/bin/env bash
# Fail-closed smoke for copy-back-nas-data.sh (ticket 06). No network required.
# Aligns with test-init-tls.sh / test-package-nas-app-update.sh style.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COPY_BACK="${SCRIPT_DIR}/copy-back-nas-data.sh"
test -x "${COPY_BACK}" || chmod +x "${COPY_BACK}"

test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-copy-back-test.XXXXXX")"
# Explicit synthetic operator configuration; never inherit a real deployment target.
export NAS_SSH=fixture@example.invalid NAS_SSH_PORT=10000
export LEZI_DATA_HOST_PATH="${test_root}/nas-data"
export LEZI_TLS_HOST=192.168.77.10 LEZI_LAN_HOST=192.168.77.10
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
  sqlite3 "${test_root}/out/lezi.db" "PRAGMA user_version=12;"
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
  sqlite3 "${test_root}/short/lezi.db" "PRAGMA user_version=12;"
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
  sqlite3 "${test_root}/ov/lezi.db" "PRAGMA user_version=12;"
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
  sqlite3 "${good}/lezi.db" "PRAGMA user_version=12;"
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
  sqlite3 "${wal}/lezi.db" "PRAGMA user_version=12;"
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
sqlite3 "${live_gate}/lezi.db" "PRAGMA user_version=12;"
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

# live copy-back must bind schema-12 compatibility to one exact prebuilt package/image digest
schema12_package="${test_root}/schema12-package"
mkdir -p "${schema12_package}"
schema12_image_id="sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
printf '{\n  "image_id": "%s"\n}\n' "${schema12_image_id}" >"${schema12_package}/MANIFEST.json"

if LEZI_OUT_DIR="${live_gate}" \
  LEZI_CONFIRM_CONTAINER_STOPPED=1 LEZI_CONFIRM_DUAL_BACKUP=1 \
  LEZI_COPY_BACK_DRY_RUN=0 \
  LEZI_SYNC_BIN=/bin/true \
  "${COPY_BACK}" 2>"${test_root}/err_live_schema"; then
  fail "live must not proceed without an exact schema-12 package identity"
fi
grep -q 'LEZI_NAS_PACKAGE_DIR' "${test_root}/err_live_schema" \
  || fail "live compatibility gate missing: $(cat "${test_root}/err_live_schema")"
pass=$((pass + 1))

if LEZI_OUT_DIR="${live_gate}" \
  LEZI_CONFIRM_CONTAINER_STOPPED=1 LEZI_CONFIRM_DUAL_BACKUP=1 \
  LEZI_COPY_BACK_DRY_RUN=0 LEZI_SYNC_BIN=/bin/true \
  LEZI_NAS_PACKAGE_DIR="${schema12_package}" \
  LEZI_SCHEMA12_COMPATIBLE_IMAGE_ID="sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" \
  "${COPY_BACK}" 2>"${test_root}/err_live_schema_mismatch"; then
  fail "live must reject an attested digest that differs from the package manifest"
fi
grep -q 'does not exactly match' "${test_root}/err_live_schema_mismatch" \
  || fail "image mismatch gate missing: $(cat "${test_root}/err_live_schema_mismatch")"
pass=$((pass + 1))

if LEZI_OUT_DIR="${live_gate}" \
  LEZI_CONFIRM_CONTAINER_STOPPED=1 LEZI_CONFIRM_DUAL_BACKUP=1 \
  LEZI_COPY_BACK_DRY_RUN=0 LEZI_SYNC_BIN=/bin/true \
  LEZI_NAS_PACKAGE_DIR="${schema12_package}" \
  LEZI_SCHEMA12_COMPATIBLE_IMAGE_ID="${schema12_image_id}" \
  "${COPY_BACK}" 2>"${test_root}/err_live_schema_match"; then
  fail "fixture omits later live gates and must not complete"
fi
if ! grep -Eqi 'rsync is required|LEZI_NAS_BACKUP_PATH' "${test_root}/err_live_schema_match"; then
  fail "matching artifact identity did not reach the next live-only gate: $(cat "${test_root}/err_live_schema_match")"
fi
pass=$((pass + 1))

echo "copy-back smoke passed (${pass} checks)"
