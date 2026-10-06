#!/usr/bin/env bash
# Functional harness: remote-deploy publishes app-update as an atomic
# APK-then-metadata pair so a running service never sees "new min + bad package".
#
# Covers:
# 1) Direct-path stage→promote sequence (no docker).
# 2) Full if/else chain with mocked docker: simulated direct-write failure must
#    recover via the docker-copy path without leaving new min + old APK on finals.
# 3) Live-channel failure restores the prior APK and minimum-version metadata.
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
    if start is None and "A running old server is the only endpoint" in line:
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
container_running=0
export DIR data_path image CONTAINER_NAME container_running

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

# --- Live-channel failure restores the exact prior pair ---
cat >"${mock_bin}/curl" <<'MOCK_CURL'
#!/usr/bin/env bash
exit 22
MOCK_CURL
chmod +x "${mock_bin}/curl"

data_path_rollback="${test_root}/data-rollback"
mkdir -p "${data_path_rollback}"
printf 'old-apk-bytes-v1\n' >"${data_path_rollback}/app-release.apk"
printf '{"min_supported_version_code":6,"version_code":7}\n' >"${data_path_rollback}/app-update.json"
data_path="${data_path_rollback}"
container_running=1
export data_path container_running

if (
  PATH="${mock_bin}:${PATH}"
  # shellcheck disable=SC1090
  source "${install_fragment}"
); then
  fail "live-channel failure must abort before container replacement"
fi
grep -qx 'old-apk-bytes-v1' "${data_path_rollback}/app-release.apk" \
  || fail "live-channel failure must restore the prior APK"
grep -q '"min_supported_version_code":6' "${data_path_rollback}/app-update.json" \
  || fail "live-channel failure must restore the prior minimum-version metadata"
[[ ! -e "${data_path_rollback}/app-release.apk.lezi-rollback" ]] \
  || fail "APK rollback snapshot must be removed after restoration"
[[ ! -e "${data_path_rollback}/app-update.json.lezi-rollback" ]] \
  || fail "metadata rollback snapshot must be removed after restoration"

echo "remote-deploy app-update atomic pair publish smoke passed"
