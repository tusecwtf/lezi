#!/usr/bin/env bash
# Public-contract smoke for the independent LAN APK download listener.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-package-lan-apk-test.XXXXXX")"
cleanup() {
  rm -rf -- "${test_root}"
}
trap cleanup EXIT HUP INT TERM

fail() {
  echo "error: $*" >&2
  exit 1
}

ledger_values="$(python3 - "${REPO_ROOT}/config/local-data-contracts.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    ledger = json.load(source)
print(ledger["current_contract"])
print(ledger["minimum_migratable_contract"])
PY
)"
current_local_data_contract="$(printf '%s\n' "${ledger_values}" | sed -n '1p')"
minimum_local_data_contract="$(printf '%s\n' "${ledger_values}" | sed -n '2p')"
expected_signer_sha256="$(tr -d '\r\n' <"${REPO_ROOT}/config/release-apk-signer-sha256.txt")"

mkdir -p "${test_root}/bin"
apk_path="${test_root}/app-release.apk"
printf 'lezi-fake-release-apk-for-lan-download\n' >"${apk_path}"
apk_sha="$(sha256sum "${apk_path}" | awk '{print $1}')"
app_update_json="${test_root}/app-update.json"
cat >"${app_update_json}" <<EOF
{
  "package_name": "com.lezi.babylog",
  "version_code": 12,
  "version_name": "0.3.5",
  "min_supported_version_code": 6,
  "sha256": "${apk_sha}",
  "release_notes": "LAN APK download smoke"
}
EOF

apk_analyzer="${test_root}/apkanalyzer"
cat >"${apk_analyzer}" <<'EOF'
#!/usr/bin/env bash
cat <<MANIFEST
<manifest
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:versionCode="12"
    android:versionName="0.3.5"
    package="com.lezi.babylog">
  <application>
    <meta-data android:name="com.lezi.babylog.LOCAL_DATA_CONTRACT_VERSION" android:value="${LEZI_TEST_CURRENT_LOCAL_DATA_CONTRACT:?}" />
    <meta-data android:name="com.lezi.babylog.MINIMUM_MIGRATABLE_LOCAL_DATA_CONTRACT_VERSION" android:value="${LEZI_TEST_MINIMUM_LOCAL_DATA_CONTRACT:?}" />
  </application>
</manifest>
MANIFEST
EOF
chmod +x "${apk_analyzer}"

apk_signer="${test_root}/apksigner"
cat >"${apk_signer}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == "verify" && "${2:-}" == "--verbose" \
    && "${3:-}" == "--print-certs" && "$#" -eq 4 ]]
echo "Verifies"
echo "Signer #1 certificate SHA-256 digest: ${LEZI_TEST_EXPECTED_SIGNER_SHA256:?}"
EOF
chmod +x "${apk_signer}"

cat >"${test_root}/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-} ${2:-}" in
  "image inspect")
    if [[ " $* " == *" --format "* ]]; then
      if [[ "$*" == *'{{.Os}}'* ]]; then
        printf 'linux\n'
      elif [[ "$*" == *'{{.Architecture}}'* ]]; then
        printf 'amd64\n'
      else
        echo "unexpected docker inspect format: $*" >&2
        exit 1
      fi
    else
      printf '[{"Id":"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}]\n'
    fi
    ;;
  "save lezi-sync:9.9.9")
    output=""
    while [[ "$#" -gt 0 ]]; do
      if [[ "$1" == "-o" ]]; then
        output="$2"
        break
      fi
      shift
    done
    [[ -n "${output}" ]]
    printf 'fake image tar\n' >"${output}"
    ;;
  *)
    echo "unexpected docker command: $*" >&2
    exit 1
    ;;
esac
EOF
chmod +x "${test_root}/bin/docker"

common_env=(
  env
  "PATH=${test_root}/bin:${PATH}"
  "LEZI_SYNC_VERSION=9.9.9"
  "LEZI_SYNC_IMAGE=lezi-sync:9.9.9"
  "LEZI_PACKAGE_SERVER_SCHEMA=99"
  "LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION=9.9.8"
  "LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA=98"
  "LEZI_APK_ANALYZER=${apk_analyzer}"
  "LEZI_APK_SIGNER=${apk_signer}"
  "LEZI_RELEASE_APK=${apk_path}"
  "LEZI_APP_UPDATE_JSON=${app_update_json}"
  "LEZI_TEST_CURRENT_LOCAL_DATA_CONTRACT=${current_local_data_contract}"
  "LEZI_TEST_MINIMUM_LOCAL_DATA_CONTRACT=${minimum_local_data_contract}"
  "LEZI_TEST_EXPECTED_SIGNER_SHA256=${expected_signer_sha256}"
  "LEZI_TLS_HOST=192.168.50.4"
)

package_dir="${test_root}/lezi-sync-9.9.9-nas"
if ! "${common_env[@]}" \
    "LEZI_NAS_PACKAGE_DIR=${package_dir}" \
    "${SCRIPT_DIR}/package-nas.sh" >"${test_root}/package.log" 2>&1; then
  cat "${test_root}/package.log" >&2
  fail "package-nas rejected the default LAN APK download origin"
fi

python3 - "${package_dir}/MANIFEST.json" <<'PY' || fail "MANIFEST missing LAN APK download origin"
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    manifest = json.load(source)
assert manifest["lan_apk_download_origin"] == "http://192.168.50.4:8767"
PY

grep -Fq -- '- LEZI_LAN_APK_DOWNLOAD_ORIGIN=http://192.168.50.4:8767' \
  "${package_dir}/docker-compose.yml" \
  || fail "NAS compose missing the LAN APK download origin"
grep -Fq '"0.0.0.0:8767:8767"' "${package_dir}/docker-compose.yml" \
  || fail "NAS compose does not publish LAN APK download port 8767"
if grep -Fq '8766:8766' "${package_dir}/docker-compose.yml"; then
  fail "NAS compose must keep internal readiness port 8766 unpublished"
fi

invalid_origins=(
  'https://192.168.50.4:8767'
  'http://192.168.50.5:8767'
  'http://192.168.50.4'
  'http://192.168.50.4:8765'
  'http://user@192.168.50.4:8767'
  'http://192.168.50.4:8767/'
  'http://192.168.50.4:8767?download=1'
  'http://192.168.50.4:8767?'
  'http://192.168.50.4:8767#invite'
  'http://192.168.50.4:8767#'
)
for origin in "${invalid_origins[@]}"; do
  failure_log="${test_root}/invalid-$(printf '%s' "${origin}" | sha256sum | awk '{print $1}').log"
  if "${common_env[@]}" \
      LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1 \
      "LEZI_LAN_APK_DOWNLOAD_ORIGIN=${origin}" \
      "${SCRIPT_DIR}/package-nas.sh" >"${failure_log}" 2>&1; then
    fail "package-nas accepted invalid LAN APK download origin: ${origin}"
  fi
  grep -q 'LEZI_LAN_APK_DOWNLOAD_ORIGIN' "${failure_log}" \
    || fail "invalid origin diagnostic omitted the variable name: ${origin}"
done

ipv6_log="${test_root}/invalid-ipv6.log"
if env \
    "PATH=${test_root}/bin:${PATH}" \
    LEZI_SYNC_VERSION=9.9.9 \
    LEZI_SYNC_IMAGE=lezi-sync:9.9.9 \
    "LEZI_APK_ANALYZER=${apk_analyzer}" \
    "LEZI_APK_SIGNER=${apk_signer}" \
    "LEZI_RELEASE_APK=${apk_path}" \
    "LEZI_APP_UPDATE_JSON=${app_update_json}" \
    "LEZI_TEST_EXPECTED_SIGNER_SHA256=${expected_signer_sha256}" \
    LEZI_TLS_HOST=2001:db8::1 \
    'LEZI_LAN_APK_DOWNLOAD_ORIGIN=http://[2001:db8::1]:8767' \
    LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1 \
    "${SCRIPT_DIR}/package-nas.sh" >"${ipv6_log}" 2>&1; then
  fail "package-nas advertised IPv6 although the fixed listener publishes IPv4 only"
fi
grep -q 'LEZI_LAN_APK_DOWNLOAD_ORIGIN' "${ipv6_log}" \
  || fail "IPv6 origin diagnostic omitted the variable name"

local_compose="${SCRIPT_DIR}/../docker-compose.yml"
grep -Fq 'LEZI_LAN_APK_DOWNLOAD_ORIGIN:' "${local_compose}" \
  || fail "local compose does not pass the LAN APK download origin"
grep -Fq ':8767' "${local_compose}" \
  || fail "local compose does not publish LAN APK download port 8767"

echo "package-nas LAN APK download wiring smoke passed"
