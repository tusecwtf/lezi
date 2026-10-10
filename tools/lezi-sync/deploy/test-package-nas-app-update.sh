#!/usr/bin/env bash
# Lightweight fail-closed smoke for package-nas app-update inputs (no docker save).
# Aligns with test-init-tls.sh: temp workspace, non-zero exit on bad inputs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-package-app-update-test.XXXXXX")"
# Explicit synthetic operator configuration; never inherit a real deployment target.
export NAS_SSH=fixture@example.invalid NAS_SSH_PORT=10000
export LEZI_DATA_HOST_PATH="${test_root}/nas-data"
export LEZI_TLS_HOST=192.168.77.10 LEZI_LAN_HOST=192.168.77.10
cleanup() {
  rm -rf -- "${test_root}"
}
trap cleanup EXIT HUP INT TERM

# A source checkout can legitimately be client-ahead of the channel package.
# Resolve the APK identity and its contract from the published metadata/catalog,
# not by equating Android upgrade_target and the server crate version.
catalog_identity="$(python3 - "${REPO_ROOT}/config/android-release-compatibility.json" "${SCRIPT_DIR}/app-update.json" "${REPO_ROOT}/config/local-data-contracts.json" <<'PY2'
import json, sys
catalog, metadata, ledger = [json.load(open(path, encoding="utf-8")) for path in sys.argv[1:]]
entries = [entry for entry in catalog["released_versions"] + [catalog["upgrade_target"]]
           if (entry["version_code"], entry["version_name"]) == (metadata["version_code"], metadata["version_name"])]
if len(entries) != 1:
    raise SystemExit("channel identity has no unique release catalog entry")
entry = entries[0]
print(metadata["version_code"])
print(metadata["version_name"])
print(metadata["min_supported_version_code"])
print(entry["local_data_contract"])
print(ledger["minimum_migratable_contract"])
PY2
)"
target_version_code="$(printf '%s\n' "${catalog_identity}" | sed -n '1p')"
target_version_name="$(printf '%s\n' "${catalog_identity}" | sed -n '2p')"
target_min_supported="$(printf '%s\n' "${catalog_identity}" | sed -n '3p')"
current_local_data_contract="$(printf '%s\n' "${catalog_identity}" | sed -n '4p')"
minimum_local_data_contract="$(printf '%s\n' "${catalog_identity}" | sed -n '5p')"
expected_signer_sha256="$(tr -d '\r\n' <"${REPO_ROOT}/config/release-apk-signer-sha256.txt")"

apk_path="${test_root}/app-release.apk"
printf 'lezi-fake-release-apk-bytes-for-gate-test\n' >"${apk_path}"
chmod 0600 "${apk_path}"
apk_sha="$(sha256sum "${apk_path}" | awk '{print $1}')"
wrong_sha="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
apk_analyzer="${test_root}/apkanalyzer"
cat >"${apk_analyzer}" <<'EOF'
#!/usr/bin/env bash
cat <<MANIFEST
<manifest
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:versionCode="${LEZI_FAKE_VERSION_CODE:-${LEZI_TEST_TARGET_VERSION_CODE:?}}"
    android:versionName="${LEZI_FAKE_VERSION_NAME:-${LEZI_TEST_TARGET_VERSION_NAME:?}}"
    package="${LEZI_FAKE_PACKAGE_NAME:-com.lezi.babylog}">
  <application>
    <meta-data android:name="com.lezi.babylog.LOCAL_DATA_CONTRACT_VERSION" android:value="${LEZI_FAKE_LOCAL_DATA_CONTRACT:-${LEZI_TEST_CURRENT_LOCAL_DATA_CONTRACT:?}}" />
    <meta-data android:name="com.lezi.babylog.MINIMUM_MIGRATABLE_LOCAL_DATA_CONTRACT_VERSION" android:value="${LEZI_FAKE_MINIMUM_LOCAL_DATA_CONTRACT:-${LEZI_TEST_MINIMUM_LOCAL_DATA_CONTRACT:?}}" />
  </application>
</manifest>
MANIFEST
EOF
chmod +x "${apk_analyzer}"

apk_signer="${test_root}/apksigner"
cat >"${apk_signer}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" != "verify" || "${2:-}" != "--verbose" \
    || "${3:-}" != "--print-certs" || "$#" -ne 4 ]]; then
  echo "unexpected apksigner invocation: $*" >&2
  exit 64
fi
if [[ "${LEZI_FAKE_APK_SIGNATURE_INVALID:-0}" == "1" ]]; then
  echo "DOES NOT VERIFY" >&2
  exit 1
fi
echo "Verifies"
echo "Signer #1 certificate SHA-256 digest: ${LEZI_TEST_EXPECTED_SIGNER_SHA256:?}"
EOF
chmod +x "${apk_signer}"

write_meta() {
  local dest="$1"
  local sha="$2"
  local notes="$3"
  cat >"${dest}" <<EOF
{
  "package_name": "com.lezi.babylog",
  "version_code": ${target_version_code},
  "version_name": "${target_version_name}",
  "min_supported_version_code": ${target_min_supported},
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
  LEZI_TEST_CURRENT_LOCAL_DATA_CONTRACT="${current_local_data_contract}" \
    LEZI_TEST_MINIMUM_LOCAL_DATA_CONTRACT="${minimum_local_data_contract}" \
    LEZI_TEST_EXPECTED_SIGNER_SHA256="${expected_signer_sha256}" \
    LEZI_TEST_TARGET_VERSION_CODE="${target_version_code}" \
    LEZI_TEST_TARGET_VERSION_NAME="${target_version_name}" \
  LEZI_SYNC_VERSION="${target_version_name}" \
  LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1 \
    LEZI_APK_ANALYZER="${apk_analyzer}" \
    LEZI_APK_SIGNER="${LEZI_TEST_APK_SIGNER:-${apk_signer}}" \
    LEZI_RELEASE_APK="$1" \
    LEZI_APP_UPDATE_JSON="$2" \
    "${SCRIPT_DIR}/package-nas.sh"
}

# A configured-but-missing verifier must stop both check-only and full package paths.
missing_signer_log="${test_root}/missing-signer.log"
if LEZI_TEST_APK_SIGNER="${test_root}/missing-apksigner" \
    run_check "${apk_path}" "${good_json}" >"${missing_signer_log}" 2>&1; then
  echo "error: package gate ran without an available apksigner" >&2
  cat "${missing_signer_log}" >&2
  exit 1
fi
if ! grep -q 'apksigner is required' "${missing_signer_log}"; then
  echo "error: missing-apksigner failure did not identify the required tool" >&2
  cat "${missing_signer_log}" >&2
  exit 1
fi

# The package gate must independently reject an APK with an invalid signature.
bad_signature_log="${test_root}/bad-signature.log"
if LEZI_FAKE_APK_SIGNATURE_INVALID=1 \
    run_check "${apk_path}" "${good_json}" >"${bad_signature_log}" 2>&1; then
  echo "error: APK with an invalid signature was accepted" >&2
  cat "${bad_signature_log}" >&2
  exit 1
fi
if ! grep -q 'release APK signature verification failed' "${bad_signature_log}"; then
  echo "error: invalid-signature failure did not identify signature verification" >&2
  cat "${bad_signature_log}" >&2
  exit 1
fi

# APK identity, not only metadata prose, owns the install target. A different
# applicationId must never be staged under com.lezi.babylog metadata.
bad_package_log="${test_root}/bad-package.log"
if LEZI_FAKE_PACKAGE_NAME=com.example.impostor \
    run_check "${apk_path}" "${good_json}" >"${bad_package_log}" 2>&1; then
  echo "error: APK package/applicationId mismatch was accepted" >&2
  cat "${bad_package_log}" >&2
  exit 1
fi
if ! grep -q 'APK package/applicationId does not match app-update.json' "${bad_package_log}"; then
  echo "error: package mismatch did not identify APK versus app-update.json" >&2
  cat "${bad_package_log}" >&2
  exit 1
fi

# A metadata version_code cannot bless a differently versioned APK.
bad_version_code_log="${test_root}/bad-version-code.log"
if LEZI_FAKE_VERSION_CODE=9 \
    run_check "${apk_path}" "${good_json}" >"${bad_version_code_log}" 2>&1; then
  echo "error: APK versionCode mismatch was accepted" >&2
  cat "${bad_version_code_log}" >&2
  exit 1
fi
if ! grep -q 'APK versionCode does not match app-update.json' "${bad_version_code_log}"; then
  echo "error: versionCode mismatch did not identify APK versus app-update.json" >&2
  cat "${bad_version_code_log}" >&2
  exit 1
fi

# Human-facing release metadata must describe the exact APK versionName too.
bad_version_name_log="${test_root}/bad-version-name.log"
if LEZI_FAKE_VERSION_NAME=0.3.2 \
    run_check "${apk_path}" "${good_json}" >"${bad_version_name_log}" 2>&1; then
  echo "error: APK versionName mismatch was accepted" >&2
  cat "${bad_version_name_log}" >&2
  exit 1
fi
if ! grep -q 'APK versionName does not match app-update.json' "${bad_version_name_log}"; then
  echo "error: versionName mismatch did not identify APK versus app-update.json" >&2
  cat "${bad_version_name_log}" >&2
  exit 1
fi

# Manifest contract outside the tracked ledger → fail closed before staging.
bad_contract_log="${test_root}/bad-contract.log"
if LEZI_FAKE_LOCAL_DATA_CONTRACT=999999 run_check "${apk_path}" "${good_json}" \
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

# Exercise the same public gate in a separate source checkout representing the
# legitimate frozen-wire split: Android 0.5.4/code34 and server 0.5.3, wire0.4,
# floor21, contract6. No released metadata or production version is rewritten.
if [[ "${LEZI_TEST_CLIENT_AHEAD_CHILD:-0}" != 1 ]]; then
  fixture="${test_root}/client-ahead"
  mkdir -p "${fixture}/tools/lezi-sync" "${fixture}/config"
  cp -a "${SCRIPT_DIR}" "${fixture}/tools/lezi-sync/deploy"
  cp "${REPO_ROOT}/config/android-release-compatibility.json" "${REPO_ROOT}/config/local-data-contracts.json" "${REPO_ROOT}/config/release-apk-signer-sha256.txt" "${fixture}/config/"
  printf '[package]\nversion = "0.5.3"\n' >"${fixture}/tools/lezi-sync/Cargo.toml"
  python3 - "${fixture}" <<'PY2'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
path = root / "config/android-release-compatibility.json"
catalog = json.loads(path.read_text())
entry = next(x for x in catalog["released_versions"] if x["version_name"] == "0.5.4")
catalog["upgrade_target"] = entry
catalog["released_versions"] = [x for x in catalog["released_versions"] if x["version_code"] < entry["version_code"]]
catalog["minimum_sync_version_code"] = 21
path.write_text(json.dumps(catalog))
meta_path = root / "tools/lezi-sync/deploy/app-update.json"
metadata = json.loads(meta_path.read_text())
metadata.update(version_name="0.5.3", version_code=33, min_supported_version_code=21)
meta_path.write_text(json.dumps(metadata))
assert entry["local_data_contract"] == 6
assert next(x for x in catalog["released_versions"] if x["version_name"] == "0.5.3")["local_data_contract"] == 6
PY2
  LEZI_TEST_CLIENT_AHEAD_CHILD=1 bash "${fixture}/tools/lezi-sync/deploy/test-package-nas-app-update.sh"
  echo "frozen-wire client-ahead package smoke passed (Android 0.5.4, server/channel 0.5.3)"
fi
echo "package-nas app-update fail-closed smoke passed"
