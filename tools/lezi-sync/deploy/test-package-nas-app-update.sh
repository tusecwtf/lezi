#!/usr/bin/env bash
# Lightweight fail-closed smoke for package-nas app-update inputs (no docker save).
# Aligns with test-init-tls.sh: temp workspace, non-zero exit on bad inputs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-package-app-update-test.XXXXXX")"
cleanup() {
  rm -rf -- "${test_root}"
}
trap cleanup EXIT HUP INT TERM

apk_path="${test_root}/app-release.apk"
printf 'lezi-fake-release-apk-bytes-for-gate-test\n' >"${apk_path}"
apk_sha="$(sha256sum "${apk_path}" | awk '{print $1}')"
wrong_sha="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
apk_analyzer="${test_root}/apkanalyzer"
cat >"${apk_analyzer}" <<'EOF'
#!/usr/bin/env bash
cat <<MANIFEST
<manifest package="com.lezi.babylog">
  <application>
    <meta-data android:name="com.lezi.babylog.LOCAL_DATA_CONTRACT_VERSION" android:value="${LEZI_FAKE_LOCAL_DATA_CONTRACT:-1}" />
    <meta-data android:name="com.lezi.babylog.MINIMUM_MIGRATABLE_LOCAL_DATA_CONTRACT_VERSION" android:value="${LEZI_FAKE_MINIMUM_LOCAL_DATA_CONTRACT:-1}" />
  </application>
</manifest>
MANIFEST
EOF
chmod +x "${apk_analyzer}"

write_meta() {
  local dest="$1"
  local sha="$2"
  local notes="$3"
  cat >"${dest}" <<EOF
{
  "package_name": "com.lezi.babylog",
  "version_code": 8,
  "version_name": "0.3.1",
  "min_supported_version_code": 6,
  "sha256": "${sha}",
  "release_notes": "${notes}"
}
EOF
}

good_json="${test_root}/app-update-good.json"
bad_sha_json="${test_root}/app-update-bad-sha.json"
write_meta "${good_json}" "${apk_sha}" "gate smoke"
write_meta "${bad_sha_json}" "${wrong_sha}" "wrong hash"

run_check() {
  LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1 \
    LEZI_APK_ANALYZER="${apk_analyzer}" \
    LEZI_RELEASE_APK="$1" \
    LEZI_APP_UPDATE_JSON="$2" \
    "${SCRIPT_DIR}/package-nas.sh"
}

# Manifest contract outside the tracked ledger → fail closed before staging.
bad_contract_log="${test_root}/bad-contract.log"
if LEZI_FAKE_LOCAL_DATA_CONTRACT=2 run_check "${apk_path}" "${good_json}" \
    >"${bad_contract_log}" 2>&1; then
  echo "error: APK with untracked local-data contract was accepted" >&2
  cat "${bad_contract_log}" >&2
  exit 1
fi
if ! grep -q 'local-data contract' "${bad_contract_log}"; then
  echo "error: bad-contract failure did not explain the local-data contract" >&2
  cat "${bad_contract_log}" >&2
  exit 1
fi

# Missing APK → fail closed before staging; diagnostics must mention the missing path.
missing_log="${test_root}/missing-apk.log"
if run_check "${test_root}/missing.apk" "${good_json}" >"${missing_log}" 2>&1; then
  echo "error: missing APK was accepted" >&2
  cat "${missing_log}" >&2
  exit 1
fi
if ! grep -q 'release APK missing' "${missing_log}"; then
  echo "error: missing-APK failure did not report 'release APK missing'" >&2
  cat "${missing_log}" >&2
  exit 1
fi

# Wrong sha256 → fail closed; diagnostics must mention the mismatch.
bad_sha_log="${test_root}/bad-sha.log"
if run_check "${apk_path}" "${bad_sha_json}" >"${bad_sha_log}" 2>&1; then
  echo "error: sha256 mismatch was accepted" >&2
  cat "${bad_sha_log}" >&2
  exit 1
fi
if ! grep -q 'sha256 does not match release APK' "${bad_sha_log}"; then
  echo "error: sha-mismatch failure did not report 'sha256 does not match release APK'" >&2
  cat "${bad_sha_log}" >&2
  exit 1
fi

# Matching inputs → success; logs must prove CHECK_ONLY early-exit (no full package).
ok_log="${test_root}/matching-ok.log"
if ! run_check "${apk_path}" "${good_json}" >"${ok_log}" 2>&1; then
  echo "error: matching app-update inputs failed" >&2
  cat "${ok_log}" >&2
  exit 1
fi
if ! grep -q 'app-update check only' "${ok_log}"; then
  echo "error: success path did not log 'app-update check only' (CHECK_ONLY early-exit)" >&2
  cat "${ok_log}" >&2
  exit 1
fi
if ! grep -q 'app-update inputs OK' "${ok_log}"; then
  echo "error: success path did not log 'app-update inputs OK'" >&2
  cat "${ok_log}" >&2
  exit 1
fi
# Full packaging would mention docker save / image packaging; CHECK_ONLY must not.
if grep -Eqi 'docker save|saving image|package complete' "${ok_log}"; then
  echo "error: success path looks like full packaging ran despite CHECK_ONLY" >&2
  cat "${ok_log}" >&2
  exit 1
fi

echo "package-nas app-update fail-closed smoke passed"
