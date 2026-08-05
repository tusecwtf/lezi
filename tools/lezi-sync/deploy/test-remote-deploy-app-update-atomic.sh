#!/usr/bin/env bash
# Contract + functional harness: remote-deploy publishes app-update as an atomic
# APK-then-metadata pair so a running service never sees "new min + bad package".
#
# Covers:
# 1) Static structural contract on remote-deploy.sh (staging names, promote order,
#    direct-then-docker if/else, post-check).
# 2) Direct-path stage→promote sequence (no docker).
# 3) Full if/else chain with mocked docker: simulated direct-write failure must
#    recover via the docker-copy path without leaving new min + old APK on finals.
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

# --- Static structural contract on remote-deploy.sh ---
# Assert executable install markers (not comment prose alone).
grep -q 'app-release.apk.lezi-staging' "${REMOTE_DEPLOY}" \
  || fail "remote-deploy must stage APK under app-release.apk.lezi-staging"
grep -q 'app-update.json.lezi-staging' "${REMOTE_DEPLOY}" \
  || fail "remote-deploy must stage metadata under app-update.json.lezi-staging"
grep -q 'mv -f "${app_update_apk_staging}" "${data_path}/app-release.apk"' "${REMOTE_DEPLOY}" \
  || fail "direct path must promote staged APK with mv -f"
grep -q 'mv -f "${app_update_meta_staging}" "${data_path}/app-update.json"' "${REMOTE_DEPLOY}" \
  || fail "direct path must promote staged metadata with mv -f"
grep -q 'cp /src/app-release.apk /data/app-release.apk.lezi-staging' "${REMOTE_DEPLOY}" \
  || fail "docker-copy path must stage APK from /src"
grep -q 'mv -f /data/app-release.apk.lezi-staging /data/app-release.apk' "${REMOTE_DEPLOY}" \
  || fail "docker-copy path must promote staged APK"
grep -q 'mv -f /data/app-update.json.lezi-staging /data/app-update.json' "${REMOTE_DEPLOY}" \
  || fail "docker-copy path must promote staged metadata"
grep -q 'test ! -e /data/app-update.json.lezi-staging' "${REMOTE_DEPLOY}" \
  || fail "post-install check must assert metadata staging leftover is gone"
grep -q 'test ! -e /data/app-release.apk.lezi-staging' "${REMOTE_DEPLOY}" \
  || fail "post-install check must assert APK staging leftover is gone"
grep -q 'http://127.0.0.1:8767/download/lezi.apk' "${REMOTE_DEPLOY}" \
  || fail "ordinary CD must verify the live LAN APK channel before replacement"

# Direct path must write both staging files before either final rename, and
# promote APK before metadata (so new min never lands ahead of matching APK).
direct_block="$(
  awk '
    /install app-update artifacts/ { in_block = 1 }
    in_block && /direct install not writable/ { exit }
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

# --- Functional smoke of the direct install sequence (no docker) ---
package_dir="${test_root}/package/app-update"
data_path="${test_root}/data-direct"
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

# --- Full if/else chain with mocked docker (direct failure → docker recovery) ---
# Runs the real install fragment from remote-deploy.sh: direct install attempt, on
# failure clean staging and docker-copy, then post-check. Mock `install` forces
# the direct path to fail while the data bind stays writable for the mock docker
# body (so recovery can complete and post-check can pass).
data_path_fallback="${test_root}/data-fallback"
mkdir -p "${data_path_fallback}"
printf 'old-apk-bytes-v1\n' >"${data_path_fallback}/app-release.apk"
printf '{"min_supported_version_code":6,"version_code":7}\n' >"${data_path_fallback}/app-update.json"

mock_bin="${test_root}/mock-bin"
mkdir -p "${mock_bin}"

# Force direct path failure without making the bind unwritable for docker recovery.
cat >"${mock_bin}/install" <<'MOCK_INSTALL'
#!/usr/bin/env bash
echo "mock install: simulating non-writable data bind" >&2
exit 1
MOCK_INSTALL
chmod +x "${mock_bin}/install"

cat >"${mock_bin}/docker" <<'MOCK_DOCKER'
#!/usr/bin/env bash
set -euo pipefail
# Parse: docker run --rm --user … -v host:container[:ro] … --entrypoint /bin/sh IMAGE -ec 'body'
# Executes the shell body with /data and /src rewritten to host bind sources.
data_host=""
src_host=""
ec_body=""
args=("$@")
i=0
while [[ $i -lt ${#args[@]} ]]; do
  case "${args[$i]}" in
    -v)
      i=$((i + 1))
      spec="${args[$i]}"
      host="${spec%%:*}"
      rest="${spec#*:}"
      container="${rest%%:*}"
      case "${container}" in
        /data) data_host="${host}" ;;
        /src) src_host="${host}" ;;
      esac
      ;;
    -ec|-c)
      i=$((i + 1))
      ec_body="${args[$i]}"
      ;;
  esac
  i=$((i + 1))
done
[[ -n "${ec_body}" ]] || { echo "mock docker: missing -ec body" >&2; exit 2; }
[[ -n "${data_host}" ]] || { echo "mock docker: missing /data bind" >&2; exit 2; }
# Rewrite container paths to host binds for the install / post-check bodies.
rewritten="${ec_body//\/data\//${data_host}/}"
if [[ -n "${src_host}" ]]; then
  rewritten="${rewritten//\/src\//${src_host}/}"
fi
/bin/sh -ec "${rewritten}"
MOCK_DOCKER
chmod +x "${mock_bin}/docker"

# Extract the install + post-check fragment from remote-deploy.sh so the real
# if/else chain and post-check run (not a reimplemented twin that can drift).
install_fragment="${test_root}/install-fragment.sh"
python3 - "${REMOTE_DEPLOY}" "${install_fragment}" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read().splitlines()
start = end = None
for i, line in enumerate(text):
    if start is None and "install app-update artifacts into" in line:
        start = i
    if start is not None and "stop/remove existing container" in line:
        end = i
        break
if start is None or end is None:
    raise SystemExit("could not isolate app-update install fragment from remote-deploy.sh")
open(dst, "w", encoding="utf-8").write(
    "#!/usr/bin/env bash\nset -euo pipefail\n" + "\n".join(text[start:end]) + "\n"
)
PY

DIR="${test_root}/package"
data_path="${data_path_fallback}"
image="lezi-sync:test-mock"
CONTAINER_NAME="lezi-sync"
export DIR data_path image CONTAINER_NAME

if ! (
  PATH="${mock_bin}:${PATH}"
  # shellcheck disable=SC1090
  source "${install_fragment}"
); then
  fail "install fragment with mock docker failed"
fi

# After docker recovery: finals must be the new pair; no staging leftovers; never
# new min with old APK.
grep -qx 'new-apk-bytes-v2' "${data_path_fallback}/app-release.apk" \
  || fail "docker fallback must install new APK"
grep -q '"min_supported_version_code":9' "${data_path_fallback}/app-update.json" \
  || fail "docker fallback must install new metadata"
[[ ! -e "${data_path_fallback}/app-release.apk.lezi-staging" ]] \
  || fail "APK staging leftover after docker fallback"
[[ ! -e "${data_path_fallback}/app-update.json.lezi-staging" ]] \
  || fail "metadata staging leftover after docker fallback"

echo "remote-deploy app-update atomic pair publish smoke passed"
