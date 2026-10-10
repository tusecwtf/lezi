#!/usr/bin/env bash
# Exercise full packaging and standalone validation with isolated tool fixtures
# for the current release identity (0.5.4) and the rollback-era predecessors
# (0.5.3, 0.5.2). The correct identity must package and validate; every MANIFEST
# identity field mismatch must be rejected fail-closed; packaging overrides
# outside the pinned stanza combination must exit 1; and the CHECK_ONLY
# app-update gate must accept the paired fixtures while refusing a stale
# rollback-shaped manifest.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-nas-release-identity.XXXXXX")"
# Explicit synthetic operator configuration; never inherit a real deployment target.
export NAS_SSH=fixture@example.invalid NAS_SSH_PORT=10000
export LEZI_DATA_HOST_PATH="${test_root}/nas-data"
export LEZI_TLS_HOST=192.168.77.10 LEZI_LAN_HOST=192.168.77.10
trap 'rm -rf -- "${test_root}"' EXIT
fail() { echo "error: $*" >&2; exit 1; }

verify_release_identity() {
  local version="$1" code="$2" floor="$3" schema="$4"
  local rollback_source="$5" rollback_schema="$6" wrong_rollback_source="$7"
  local work="${test_root}/${version}"
  mkdir -p "${work}/bin"
  printf 'isolated APK identity fixture\n' >"${work}/app.apk"
  local apk_sha
  apk_sha="$(sha256sum "${work}/app.apk" | cut -d ' ' -f1)"
  cat >"${work}/app-update.json" <<EOF
{
  "package_name": "com.lezi.babylog",
  "version_code": ${code},
  "version_name": "${version}",
  "min_supported_version_code": ${floor},
  "sha256": "${apk_sha}",
  "release_notes": "isolated release identity regression"
}
EOF
  sed -e "s/__VERSION__/${version}/g" -e "s/__CODE__/${code}/g" \
    >"${work}/bin/apkanalyzer" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1 $2" == 'manifest print' ]]
cat <<MANIFEST
<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionCode="__CODE__" android:versionName="__VERSION__" package="com.lezi.babylog">
  <application>
    <meta-data android:name="com.lezi.babylog.LOCAL_DATA_CONTRACT_VERSION" android:value="6" />
    <meta-data android:name="com.lezi.babylog.MINIMUM_MIGRATABLE_LOCAL_DATA_CONTRACT_VERSION" android:value="1" />
  </application>
</manifest>
MANIFEST
EOF
  cat >"${work}/bin/apksigner" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1 $2 $3" == 'verify --verbose --print-certs' && "$#" -eq 4 ]]
echo "Signer #1 certificate SHA-256 digest: ${LEZI_TEST_SIGNER:?}"
EOF
  sed -e "s/__VERSION__/${version}/g" \
    >"${work}/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  'image inspect lezi-sync:__VERSION__')
    printf '[{"Id":"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}]\n' ;;
  'image inspect lezi-sync:__VERSION__ --format {{.Os}}') printf 'linux\n' ;;
  'image inspect lezi-sync:__VERSION__ --format {{.Architecture}}') printf 'amd64\n' ;;
  'save lezi-sync:__VERSION__ -o '*) printf 'isolated image archive\n' >"$4" ;;
  *) echo "unexpected fixture docker invocation: $*" >&2; exit 1 ;;
esac
EOF
  chmod +x "${work}/bin/"*
  local package_dir="${work}/lezi-sync-${version}-nas"
  local package_env=(
    env -u LEZI_PACKAGE_SERVER_SCHEMA -u LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION
    -u LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA
    "PATH=${work}/bin:${PATH}"
    "LEZI_TEST_SIGNER=$(tr -d '\r\n' <"${REPO_ROOT}/config/release-apk-signer-sha256.txt")"
    "LEZI_APK_ANALYZER=${work}/bin/apkanalyzer"
    "LEZI_APK_SIGNER=${work}/bin/apksigner"
    "LEZI_RELEASE_APK=${work}/app.apk"
    "LEZI_APP_UPDATE_JSON=${work}/app-update.json"
    "LEZI_NAS_PACKAGE_DIR=${package_dir}"
    LEZI_SYNC_VERSION=${version} LEZI_SYNC_IMAGE=lezi-sync:${version}
    LEZI_PACKAGE_BUILD_IMAGE=0
  )
  if ! "${package_env[@]}" LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=0 \
      "${SCRIPT_DIR}/package-nas.sh" >"${work}/package.log" 2>&1; then
    cat "${work}/package.log" >&2
    fail "full ${version} packaging requires no schema or rollback overrides"
  fi
  python3 - \
    "${package_dir}" "${SCRIPT_DIR}/validate-nas-package.sh" \
    "${version}" "${code}" "${floor}" "${schema}" \
    "${rollback_source}" "${rollback_schema}" <<'PY'
import hashlib
import json
from pathlib import Path
import subprocess
import sys

package = Path(sys.argv[1])
validator = sys.argv[2]
version, code, floor, schema, rollback_source, rollback_schema = sys.argv[3:9]
manifest_path = package / 'MANIFEST.json'
original = manifest_path.read_bytes()
manifest = json.loads(original)
expected = {
    'server_schema': schema,
    'android_version_code': code,
    'minimum_supported_version_code': floor,
    'rollback_source_version': rollback_source,
    'rollback_source_server_schema': rollback_schema,
}
assert {key: manifest[key] for key in expected} == expected
checksums_path = package / 'SHA256SUMS'
original_checksums = checksums_path.read_text()
subprocess.run(['bash', validator, str(package), version], check=True)
# Re-seal the manifest so a checksum mismatch cannot mask a missing identity guard.
minor, patch = rollback_source.rsplit('.', 1)
wrong_values = {
    'server_schema': '14',
    'android_version_code': str(int(code) - 1),
    'minimum_supported_version_code': str(int(floor) - 1),
    'rollback_source_version': f'{minor}.{int(patch) - 1}',
    'rollback_source_server_schema': str(int(rollback_schema) - 1),
}
for key, wrong in wrong_values.items():
    mutated = dict(manifest, **{key: wrong})
    content = (json.dumps(mutated, indent=2) + '\n').encode()
    manifest_path.write_bytes(content)
    digest = hashlib.sha256(content).hexdigest()
    checksums_path.write_text(''.join(
        f'{digest}  MANIFEST.json\n' if line.endswith('  MANIFEST.json\n') else line
        for line in original_checksums.splitlines(keepends=True)
    ))
    result = subprocess.run(['bash', validator, str(package), version], capture_output=True)
    assert result.returncode != 0, f'validator accepted {key}={wrong}'
manifest_path.write_bytes(original)
checksums_path.write_text(original_checksums)
subprocess.run(['bash', validator, str(package), version], check=True)
PY
  # The stanza must accept exactly one schema/rollback combination: each
  # override taken alone is fatal.
  if "${package_env[@]}" LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=0 \
      LEZI_PACKAGE_SERVER_SCHEMA=14 \
      "${SCRIPT_DIR}/package-nas.sh" >"${work}/wrong-schema.log" 2>&1; then
    fail "${version} packaging accepted a schema override outside the stanza"
  fi
  if "${package_env[@]}" LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=0 \
      LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION="${wrong_rollback_source}" \
      "${SCRIPT_DIR}/package-nas.sh" >"${work}/wrong-source.log" 2>&1; then
    fail "${version} packaging accepted an unattested ${wrong_rollback_source} rollback source"
  fi
  if "${package_env[@]}" LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=0 \
      LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA=12 \
      "${SCRIPT_DIR}/package-nas.sh" >"${work}/wrong-rollback-schema.log" 2>&1; then
    fail "${version} packaging accepted a rollback schema override outside the stanza"
  fi
  # CHECK_ONLY smoke: the dedicated APK+app-update gate accepts the paired
  # identity fixtures and exits before any docker packaging.
  if ! "${package_env[@]}" LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1 \
      "${SCRIPT_DIR}/package-nas.sh" >"${work}/check-only.log" 2>&1; then
    cat "${work}/check-only.log" >&2
    fail "${version} app-update check-only smoke failed on paired fixtures"
  fi
  grep -q 'app-update check only' "${work}/check-only.log" \
    || fail "${version} check-only run did not log the CHECK_ONLY early-exit marker"
  grep -q 'app-update inputs OK' "${work}/check-only.log" \
    || fail "${version} check-only success path did not log 'app-update inputs OK'"
  if grep -Eqi 'docker save|package complete' "${work}/check-only.log"; then
    fail "${version} check-only path looks like full packaging ran"
  fi
  # A stale rollback-shaped app-update manifest must never bless the new APK.
  cat >"${work}/app-update-stale.json" <<EOF
{
  "package_name": "com.lezi.babylog",
  "version_code": $((code - 1)),
  "version_name": "${rollback_source}",
  "min_supported_version_code": ${floor},
  "sha256": "${apk_sha}",
  "release_notes": "stale rollback-shaped manifest must not bless a ${version} package"
}
EOF
  if "${package_env[@]}" LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1 \
      LEZI_APP_UPDATE_JSON="${work}/app-update-stale.json" \
      "${SCRIPT_DIR}/package-nas.sh" >"${work}/stale-check.log" 2>&1; then
    fail "${version} check-only accepted a ${rollback_source}-shaped app-update manifest"
  fi
  grep -q 'APK versionCode does not match app-update.json' "${work}/stale-check.log" \
    || fail "${version} stale-manifest rejection did not identify APK versus app-update.json"
}

# Current release identity: 0.5.4/code34/floor21/schema13 with a
# 0.5.3/schema 13 rollback source.
verify_release_identity 0.5.4 34 21 13 0.5.3 13 0.5.2
# Regression: the 0.5.3 identity and its 0.5.2/schema 13 rollback path stay intact.
verify_release_identity 0.5.3 33 21 13 0.5.2 13 0.5.1
# Regression: the 0.5.2 identity and its 0.5.1/schema 13 rollback path stay intact.
verify_release_identity 0.5.2 32 21 13 0.5.1 13 0.5.0

echo 'NAS 0.5.4/0.5.3/0.5.2 full packaging, rollback identity, and check-only regressions passed'
