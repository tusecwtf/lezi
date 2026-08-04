#!/usr/bin/env bash
# Contract + direct-path smoke: remote-deploy publishes app-update as an atomic
# APK-then-metadata pair so a running service never sees "new min + bad package".
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REMOTE_DEPLOY="${SCRIPT_DIR}/remote-deploy.sh"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-remote-app-update-atomic.XXXXXX")"
cleanup() {
  rm -rf -- "${test_root}"
}
trap cleanup EXIT HUP INT TERM

fail() {
  echo "error: $*" >&2
  exit 1
}

[[ -f "${REMOTE_DEPLOY}" ]] || fail "missing remote-deploy.sh"

# --- Static contract on remote-deploy.sh ---
grep -q 'app-release.apk.lezi-staging' "${REMOTE_DEPLOY}" \
  || fail "remote-deploy must stage APK under app-release.apk.lezi-staging"
grep -q 'app-update.json.lezi-staging' "${REMOTE_DEPLOY}" \
  || fail "remote-deploy must stage metadata under app-update.json.lezi-staging"
grep -q 'atomic pair' "${REMOTE_DEPLOY}" \
  || fail "remote-deploy must document atomic pair publish for app-update"

# Direct path must write both staging files before either final rename, and
# promote APK before metadata (so new min never lands ahead of matching APK).
direct_block="$(
  awk '
    /install app-update artifacts/ { in_block = 1 }
    in_block && /docker-copy as 10001/ { exit }
    in_block { print }
  ' "${REMOTE_DEPLOY}"
)"
[[ -n "${direct_block}" ]] || fail "could not isolate direct app-update install block"

apk_stage_line="$(printf '%s\n' "${direct_block}" | grep -n 'app-release.apk.lezi-staging' | head -1 | cut -d: -f1)"
meta_stage_line="$(printf '%s\n' "${direct_block}" | grep -n 'app-update.json.lezi-staging' | head -1 | cut -d: -f1)"
apk_mv_line="$(printf '%s\n' "${direct_block}" | grep -n 'mv -f .*app-release.apk' | head -1 | cut -d: -f1)"
meta_mv_line="$(printf '%s\n' "${direct_block}" | grep -n 'mv -f .*app-update.json' | head -1 | cut -d: -f1)"

[[ -n "${apk_stage_line}" && -n "${meta_stage_line}" ]] \
  || fail "direct path must stage both APK and metadata"
[[ -n "${apk_mv_line}" && -n "${meta_mv_line}" ]] \
  || fail "direct path must rename both staging files into place"
[[ "${apk_stage_line}" -lt "${apk_mv_line}" ]] \
  || fail "APK must be staged before it is promoted"
[[ "${meta_stage_line}" -lt "${meta_mv_line}" ]] \
  || fail "metadata must be staged before it is promoted"
[[ "${apk_mv_line}" -lt "${meta_mv_line}" ]] \
  || fail "APK must be promoted before metadata (no new-min-before-package window)"

# Docker fallback must also stage then promote APK before metadata in one -ec body.
docker_ec_line="$(
  grep -n "cp /src/app-release.apk /data/app-release.apk.lezi-staging" "${REMOTE_DEPLOY}" \
    | head -1
)"
[[ -n "${docker_ec_line}" ]] || fail "docker-copy path must stage APK from /src"
docker_ec_body="${docker_ec_line#*:}"
printf '%s\n' "${docker_ec_body}" | grep -q 'app-update.json.lezi-staging' \
  || fail "docker-copy path must stage metadata in the same -ec body"
# Order of mv in the single -ec string: APK before metadata.
apk_mv_pos="$(
  printf '%s' "${docker_ec_body}" \
    | python3 -c 'import sys; s=sys.stdin.read(); print(s.find("mv -f /data/app-release.apk.lezi-staging"))'
)"
meta_mv_pos="$(
  printf '%s' "${docker_ec_body}" \
    | python3 -c 'import sys; s=sys.stdin.read(); print(s.find("mv -f /data/app-update.json.lezi-staging"))'
)"
[[ "${apk_mv_pos}" -ge 0 && "${meta_mv_pos}" -ge 0 ]] \
  || fail "docker-copy path must rename both staging files"
[[ "${apk_mv_pos}" -lt "${meta_mv_pos}" ]] \
  || fail "docker-copy path must promote APK before metadata"

# Fail-closed post-check must reject leftover staging names on the bind.
grep -q 'app-update.json.lezi-staging' "${REMOTE_DEPLOY}" \
  && grep -q '! -e /data/app-update.json.lezi-staging' "${REMOTE_DEPLOY}" \
  || fail "post-install check must assert staging leftovers are gone"

# --- Functional smoke of the direct install sequence (no docker) ---
package_dir="${test_root}/package/app-update"
data_path="${test_root}/data"
mkdir -p "${package_dir}" "${data_path}"
printf 'new-apk-bytes-v2\n' >"${package_dir}/app-release.apk"
printf '{"min_supported_version_code":9,"version_code":10}\n' >"${package_dir}/app-update.json"
# Pre-existing live pair (old floor / old package) that must not be left half-replaced.
printf 'old-apk-bytes-v1\n' >"${data_path}/app-release.apk"
printf '{"min_supported_version_code":6,"version_code":7}\n' >"${data_path}/app-update.json"

app_update_apk_staging="${data_path}/app-release.apk.lezi-staging"
app_update_meta_staging="${data_path}/app-update.json.lezi-staging"
install -m 644 "${package_dir}/app-release.apk" "${app_update_apk_staging}"
install -m 644 "${package_dir}/app-update.json" "${app_update_meta_staging}"
# After staging only, finals must still be the old pair (no partial promotion).
grep -qx 'old-apk-bytes-v1' "${data_path}/app-release.apk" \
  || fail "staging must not replace final APK early"
grep -q '"min_supported_version_code":6' "${data_path}/app-update.json" \
  || fail "staging must not replace final metadata early"
mv -f "${app_update_apk_staging}" "${data_path}/app-release.apk"
# Mid-promote: new APK + old metadata is the only allowed intermediate (not new min + old APK).
grep -qx 'new-apk-bytes-v2' "${data_path}/app-release.apk" \
  || fail "APK promote failed"
grep -q '"min_supported_version_code":6' "${data_path}/app-update.json" \
  || fail "metadata must remain old until its own promote"
mv -f "${app_update_meta_staging}" "${data_path}/app-update.json"
grep -q '"min_supported_version_code":9' "${data_path}/app-update.json" \
  || fail "metadata promote failed"
[[ ! -e "${app_update_apk_staging}" && ! -e "${app_update_meta_staging}" ]] \
  || fail "staging leftovers must be gone after promote"
grep -qx 'new-apk-bytes-v2' "${data_path}/app-release.apk" \
  || fail "final APK content wrong"

echo "remote-deploy app-update atomic pair publish smoke passed"
