#!/usr/bin/env bash
# Build a NAS deploy package: image tar + zdocker-friendly compose + checksums
# + fail-closed self-hosted app-update artifacts (release APK + app-update.json).
# Does not deploy. Prefer: ./package-nas.sh && ./push-and-deploy.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SYNC_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${SYNC_ROOT}/../.." && pwd)"

version="${LEZI_SYNC_VERSION:-}"
if [[ -z "${version}" ]]; then
  version="$(sed -n 's/^version = "\([^"]*\)"/\1/p' "${SYNC_ROOT}/Cargo.toml" | head -1)"
fi
if [[ -z "${version}" ]]; then
  echo "error: could not determine LEZI_SYNC_VERSION" >&2
  exit 1
fi
if [[ ! "${version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$ ]]; then
  echo "error: LEZI_SYNC_VERSION is outside the release-version contract" >&2
  exit 1
fi

image="${LEZI_SYNC_IMAGE:-lezi-sync:${version}}"
if [[ "${image}" != "lezi-sync:${version}" ]]; then
  echo "error: LEZI_SYNC_IMAGE must be lezi-sync:${version}" >&2
  exit 1
fi
data_host_path="${LEZI_DATA_HOST_PATH:-/tmp/zfsv3/sata1/nas-account/data/Docker/lezi/data}"
if [[ "${data_host_path}" != /* \
    || "${data_host_path}" == "/" \
    || ! "${data_host_path}" =~ ^/[A-Za-z0-9._/-]+$ \
    || "${data_host_path}" == *'//'* \
    || "${data_host_path}" == */./* \
    || "${data_host_path}" == */../* \
    || "${data_host_path}" == */. \
    || "${data_host_path}" == */.. ]]; then
  echo "error: LEZI_DATA_HOST_PATH must be a normalized absolute NAS path using only A-Z a-z 0-9 . _ / -" >&2
  exit 1
fi
case "${data_host_path}" in
  /etc|/usr|/var|/home|/root|/tmp|/opt|/srv)
    echo "error: refusing a broad/system LEZI_DATA_HOST_PATH target" >&2
    exit 1
    ;;
esac
tls_host="${LEZI_TLS_HOST:-192.168.77.4}"
if [[ ! "${tls_host}" =~ ^[A-Za-z0-9.:-]+$ ]] || [[ "${#tls_host}" -gt 253 ]]; then
  echo "error: LEZI_TLS_HOST must be a plain DNS name or IP address" >&2
  exit 1
fi
lan_apk_download_host="${tls_host}"
if [[ "${lan_apk_download_host}" == *:* ]]; then
  lan_apk_download_host="[${lan_apk_download_host}]"
fi
lan_apk_download_origin="${LEZI_LAN_APK_DOWNLOAD_ORIGIN:-http://${lan_apk_download_host}:8767}"
if ! python3 - "${tls_host}" "${lan_apk_download_origin}" <<'PY'
import ipaddress
import sys
import urllib.parse

tls_host, origin = sys.argv[1:]
if origin != origin.strip() or any(ord(char) < 0x20 or ord(char) == 0x7f for char in origin):
    raise SystemExit(1)
try:
    parsed = urllib.parse.urlsplit(origin)
    port = parsed.port
except ValueError:
    raise SystemExit(1)
if (
    parsed.scheme != "http"
    or not parsed.hostname
    or ":" in parsed.hostname
    or ":" in tls_host.strip("[]")
    or parsed.username is not None
    or parsed.password is not None
    or port != 8767
    or parsed.path
    or parsed.query
    or parsed.fragment
    or "?" in origin
    or "#" in origin
):
    raise SystemExit(1)

def normalized_host(value):
    value = value.strip("[]")
    try:
        return ("ip", ipaddress.ip_address(value))
    except ValueError:
        return ("dns", value.lower())

if normalized_host(parsed.hostname) != normalized_host(tls_host):
    raise SystemExit(1)
PY
then
  echo "error: LEZI_LAN_APK_DOWNLOAD_ORIGIN must be http://<IPv4-or-DNS LEZI_TLS_HOST>:8767 with no userinfo, path, query, or fragment" >&2
  exit 1
fi
# The accepted origin is semantically same-host; persist one canonical spelling
# so later closed-package validation does not disagree on DNS case.
lan_apk_download_origin="http://${tls_host}:8767"
out_root="${LEZI_NAS_PACKAGE_DIR:-${REPO_ROOT}/dist/lezi-sync-${version}-nas}"
if [[ -L "${out_root}" ]]; then
  echo "error: LEZI_NAS_PACKAGE_DIR must not be a symlink" >&2
  exit 1
fi
out_root="$(realpath -m -- "${out_root}")"
if [[ "$(basename -- "${out_root}")" != "lezi-sync-${version}-nas" ]]; then
  echo "error: LEZI_NAS_PACKAGE_DIR basename must be lezi-sync-${version}-nas" >&2
  exit 1
fi
case "${out_root}" in
  /|"${REPO_ROOT}"|"${SYNC_ROOT}"|"${SCRIPT_DIR}")
    echo "error: refusing a broad LEZI_NAS_PACKAGE_DIR target" >&2
    exit 1
    ;;
esac
platform="${LEZI_SYNC_PLATFORM:-linux-amd64}"
if [[ "${platform}" != "linux-amd64" ]]; then
  echo "error: LEZI_SYNC_PLATFORM must be linux-amd64 for NAS release packages" >&2
  exit 1
fi
tar_name="lezi-sync-${version}-${platform}.tar"
build_image="${LEZI_PACKAGE_BUILD_IMAGE:-0}"

# Self-hosted app update inputs (fail-closed).
# Override with LEZI_RELEASE_APK / LEZI_APP_UPDATE_JSON when packaging a different build.
release_apk="${LEZI_RELEASE_APK:-${REPO_ROOT}/app/build/outputs/apk/release/app-release.apk}"
app_update_json="${LEZI_APP_UPDATE_JSON:-${SCRIPT_DIR}/app-update.json}"
local_data_contract_json="${REPO_ROOT}/config/local-data-contracts.json"
release_signer_sha256_file="${REPO_ROOT}/config/release-apk-signer-sha256.txt"
apk_analyzer="${LEZI_APK_ANALYZER:-}"
if [[ -z "${apk_analyzer}" ]]; then
  sdk_dir="$(sed -n 's/^sdk.dir=//p' "${REPO_ROOT}/local.properties" 2>/dev/null | head -1)"
  if [[ -n "${sdk_dir}" && -x "${sdk_dir}/cmdline-tools/latest/bin/apkanalyzer" ]]; then
    apk_analyzer="${sdk_dir}/cmdline-tools/latest/bin/apkanalyzer"
  else
    apk_analyzer="$(command -v apkanalyzer || true)"
  fi
fi
apk_signer="${LEZI_APK_SIGNER:-}"
if [[ -z "${apk_signer}" ]]; then
  signer_sdk_dir="$(sed -n 's/^sdk.dir=//p' "${REPO_ROOT}/local.properties" 2>/dev/null | head -1)"
  if [[ -z "${signer_sdk_dir}" || ! -d "${signer_sdk_dir}/build-tools" ]]; then
    signer_sdk_dir="${ANDROID_SDK_ROOT:-}"
  fi
  if [[ -n "${signer_sdk_dir}" && -d "${signer_sdk_dir}/build-tools" ]]; then
    apk_signer="$(
      find "${signer_sdk_dir}/build-tools" -mindepth 2 -maxdepth 2 \
        -type f -name apksigner -perm -u+x -print 2>/dev/null \
        | sort -V \
        | tail -1 \
        || true
    )"
  fi
fi

echo "==> package lezi-sync ${version}"
echo "    image:   ${image}"
echo "    data:    ${data_host_path}"
echo "    tls host:${tls_host}"
echo "    apk LAN: ${lan_apk_download_origin}"
echo "    apk:     ${release_apk}"
echo "    update:  ${app_update_json}"
echo "    output:  ${out_root}"

require_app_update_inputs() {
  if [[ ! -f "${release_apk}" ]]; then
    echo "error: release APK missing: ${release_apk}" >&2
    echo "  build: ./gradlew :app:assembleRelease" >&2
    echo "  or set LEZI_RELEASE_APK to a signed com.lezi.babylog release APK" >&2
    exit 1
  fi
  if [[ ! -s "${release_apk}" ]]; then
    echo "error: release APK is empty: ${release_apk}" >&2
    exit 1
  fi
  if [[ ! -f "${app_update_json}" ]]; then
    echo "error: app-update metadata missing: ${app_update_json}" >&2
    echo "  provide tools/lezi-sync/deploy/app-update.json or set LEZI_APP_UPDATE_JSON" >&2
    echo "  required fields: package_name, version_code, version_name," >&2
    echo "    min_supported_version_code, sha256 (64 lowercase hex; must match APK)" >&2
    exit 1
  fi
  if [[ ! -f "${local_data_contract_json}" ]]; then
    echo "error: local-data contract ledger missing: ${local_data_contract_json}" >&2
    exit 1
  fi
  if [[ ! -f "${release_signer_sha256_file}" \
      || -L "${release_signer_sha256_file}" ]]; then
    echo "error: tracked release APK signer pin is missing or unsafe: ${release_signer_sha256_file}" >&2
    exit 1
  fi
  if [[ -z "${apk_analyzer}" || ! -x "${apk_analyzer}" ]]; then
    echo "error: apkanalyzer is required to verify APK local-data contract metadata" >&2
    exit 1
  fi
  if [[ -z "${apk_signer}" || ! -x "${apk_signer}" ]]; then
    echo "error: apksigner is required to verify the release APK signature" >&2
    exit 1
  fi
}

validate_and_stage_app_update() {
  local dest_dir="$1"
  mkdir -p "${dest_dir}"
  local signer_output
  local -a signer_sha256_values=()
  if ! signer_output="$(
    "${apk_signer}" verify --verbose --print-certs "${release_apk}" 2>&1
  )"; then
    echo "error: release APK signature verification failed: ${release_apk}" >&2
    exit 1
  fi
  mapfile -t signer_sha256_values < <(
    printf '%s\n' "${signer_output}" \
      | sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: ([0-9A-Fa-f]{64})$/\L\1/p'
  )
  if [[ "${#signer_sha256_values[@]}" -ne 1 \
      || "${signer_sha256_values[0]}" != "${expected_signer_sha256}" ]]; then
    echo "error: release APK signer certificate does not match the tracked Lezi release signer" >&2
    exit 1
  fi
  local manifest_file contract_values apk_identity apk_package apk_version_code apk_version_name
  manifest_file="$(mktemp "${TMPDIR:-/tmp}/lezi-apk-manifest.XXXXXX")"
  if ! "${apk_analyzer}" manifest print "${release_apk}" >"${manifest_file}"; then
    rm -f -- "${manifest_file}"
    echo "error: unable to read release APK manifest" >&2
    exit 1
  fi
  # Current-ledger local-data contract is a current-generation target invariant.
  # Attested rollback APKs (0.3.12/0.3.13) predate that metadata and must still
  # pack with current helpers; they keep signer, identity, and metadata-hash gates.
  if [[ "${version}" != "0.3.12" && "${version}" != "0.3.13" ]]; then
  if ! contract_values="$(
    python3 - "${local_data_contract_json}" "${manifest_file}" <<'PY'
import json, re, sys

ledger_path, manifest_path = sys.argv[1:]
with open(ledger_path, encoding="utf-8") as f:
    ledger = json.load(f)
with open(manifest_path, encoding="utf-8") as f:
    manifest = f.read()

current = ledger.get("current_contract")
baseline = ledger.get("permanent_baseline_contract")
minimum = ledger.get("minimum_migratable_contract")
contracts = ledger.get("contracts")
migrations = ledger.get("migrations")
if not isinstance(current, int) or not isinstance(baseline, int) or not isinstance(minimum, int):
    raise SystemExit("local-data contract ledger current/baseline/minimum must be integers")
if baseline != 1 or minimum != baseline:
    raise SystemExit("permanent local-data compatibility baseline must remain contract 1")
if not isinstance(contracts, list) or not isinstance(migrations, list):
    raise SystemExit("local-data contract ledger lists are missing")
versions = [entry.get("contract_version") for entry in contracts]
if versions != list(range(1, current + 1)):
    raise SystemExit("local-data contract ledger must be append-only and contiguous")
if not contracts or contracts[0].get("introduced_in_version_code") != 6:
    raise SystemExit("local-data contract 1 must remain anchored to Android versionCode 6")
required_pairs = [[version, version + 1] for version in range(minimum, current)]
actual_pairs = [
    [entry.get("from_contract"), entry.get("to_contract")]
    for entry in migrations
]
if actual_pairs != required_pairs:
    raise SystemExit("local-data contract ledger migration chain is incomplete")

def manifest_int(name):
    tag_pattern = re.compile(r"<meta-data\b[^>]*>")
    name_pattern = re.compile(r'android:name=["\']' + re.escape(name) + r'["\']')
    value_pattern = re.compile(r'android:value=["\'](\d+)["\']')
    for tag in tag_pattern.findall(manifest):
        if name_pattern.search(tag):
            value = value_pattern.search(tag)
            if value:
                return int(value.group(1))
    raise SystemExit(f"APK local-data contract metadata missing: {name}")

apk_current = manifest_int("com.lezi.babylog.LOCAL_DATA_CONTRACT_VERSION")
apk_minimum = manifest_int(
    "com.lezi.babylog.MINIMUM_MIGRATABLE_LOCAL_DATA_CONTRACT_VERSION"
)
if [apk_current, apk_minimum] != [current, minimum]:
    raise SystemExit(
        "APK local-data contract does not match ledger "
        f"(apk={apk_minimum}..{apk_current}, ledger={minimum}..{current})"
    )
print(apk_current)
print(apk_minimum)
PY
  )"; then
    rm -f -- "${manifest_file}"
    exit 1
  fi
  else
    case "${version}" in
      0.3.12|0.3.13) ;;
      *)
        echo "error: local-data contract skip is only for attested 0.3.12/0.3.13 rollback APKs" >&2
        rm -f -- "${manifest_file}"
        exit 1
        ;;
    esac
    contract_values=$'rollback\nsource'
  fi
  if ! apk_identity="$(
    python3 - "${manifest_file}" <<'PY'
import sys
import xml.etree.ElementTree as ET

manifest_path = sys.argv[1]
try:
    root = ET.parse(manifest_path).getroot()
except (ET.ParseError, OSError) as error:
    raise SystemExit(f"unable to parse APK manifest XML: {error}")
package_name = root.attrib.get("package")
if not package_name:
    raise SystemExit("APK manifest package/applicationId is missing")
version_code = root.attrib.get(
    "{http://schemas.android.com/apk/res/android}versionCode"
)
if not version_code or not version_code.isdigit():
    raise SystemExit("APK manifest versionCode is missing or invalid")
version_name = root.attrib.get(
    "{http://schemas.android.com/apk/res/android}versionName"
)
if not version_name:
    raise SystemExit("APK manifest versionName is missing")
print(package_name)
print(int(version_code))
print(version_name)
PY
  )"; then
    rm -f -- "${manifest_file}"
    exit 1
  fi
  apk_package="$(printf '%s\n' "${apk_identity}" | sed -n '1p')"
  apk_version_code="$(printf '%s\n' "${apk_identity}" | sed -n '2p')"
  apk_version_name="$(printf '%s\n' "${apk_identity}" | sed -n '3p')"
  rm -f -- "${manifest_file}"
  local apk_sha
  apk_sha="$(sha256sum "${release_apk}" | awk '{print $1}')"
  local meta_sha package_name version_code version_name min_supported
  meta_sha="$(
    python3 - "${app_update_json}" <<'PY'
import json, sys
path = sys.argv[1]
with open(path, encoding="utf-8") as f:
    data = json.load(f)
required = [
    "package_name",
    "version_code",
    "version_name",
    "min_supported_version_code",
    "sha256",
]
for key in required:
    if key not in data:
        raise SystemExit(f"missing field: {key}")
if data["package_name"] != "com.lezi.babylog":
    raise SystemExit("package_name must be com.lezi.babylog")
for key in ("version_code", "min_supported_version_code"):
    value = data[key]
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise SystemExit(f"{key} must be a non-negative integer")
# Align with lezi-sync normalize_app_update_metadata:
# - version_code is a positive 32-bit integer (1..=i32::MAX)
# - min_supported is 0..=i32::MAX and must not exceed version_code
I32_MAX = 2**31 - 1
if data["version_code"] < 1 or data["version_code"] > I32_MAX:
    raise SystemExit(
        f"version_code must be a positive 32-bit integer (1..={I32_MAX})"
    )
if data["min_supported_version_code"] > I32_MAX:
    raise SystemExit(
        f"min_supported_version_code is out of range (max {I32_MAX})"
    )
if data["min_supported_version_code"] > data["version_code"]:
    raise SystemExit(
        "min_supported_version_code must not exceed version_code "
        f"({data['min_supported_version_code']} > {data['version_code']})"
    )
if not isinstance(data["version_name"], str) or not data["version_name"].strip():
    raise SystemExit("version_name must be a non-empty string")
sha = data["sha256"]
if not isinstance(sha, str) or len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha):
    raise SystemExit("sha256 must be 64 lowercase hex characters")
print(sha)
print(data["package_name"])
print(data["version_code"])
print(data["version_name"])
print(data["min_supported_version_code"])
PY
  )"
  package_name="$(printf '%s\n' "${meta_sha}" | sed -n '2p')"
  version_code="$(printf '%s\n' "${meta_sha}" | sed -n '3p')"
  version_name="$(printf '%s\n' "${meta_sha}" | sed -n '4p')"
  min_supported="$(printf '%s\n' "${meta_sha}" | sed -n '5p')"
  meta_sha="$(printf '%s\n' "${meta_sha}" | sed -n '1p')"
  if [[ "${apk_package}" != "${package_name}" ]]; then
    echo "error: APK package/applicationId does not match app-update.json" >&2
    echo "  metadata: ${package_name}" >&2
    echo "  apk:      ${apk_package}" >&2
    exit 1
  fi
  if [[ "${apk_version_code}" != "${version_code}" ]]; then
    echo "error: APK versionCode does not match app-update.json" >&2
    echo "  metadata: ${version_code}" >&2
    echo "  apk:      ${apk_version_code}" >&2
    exit 1
  fi
  if [[ "${apk_version_name}" != "${version_name}" ]]; then
    echo "error: APK versionName does not match app-update.json" >&2
    echo "  metadata: ${version_name}" >&2
    echo "  apk:      ${apk_version_name}" >&2
    exit 1
  fi
  if [[ "${version_name}" != "${version}" ]]; then
    echo "error: app-update version_name must match the package version ${version}" >&2
    echo "  metadata: ${version_name}" >&2
    exit 1
  fi
  if [[ "${meta_sha}" != "${apk_sha}" ]]; then
    echo "error: app-update.json sha256 does not match release APK" >&2
    echo "  metadata: ${meta_sha}" >&2
    echo "  apk:      ${apk_sha}" >&2
    echo "  apk path: ${release_apk}" >&2
    exit 1
  fi
  install -m 0644 -- "${release_apk}" "${dest_dir}/app-release.apk"
  install -m 0644 -- "${app_update_json}" "${dest_dir}/app-update.json"
  if [[ "$(stat -c '%a' "${dest_dir}/app-release.apk")" != "644" \
      || "$(stat -c '%a' "${dest_dir}/app-update.json")" != "644" ]]; then
    echo "error: staged app-update artifacts must use mode 0644" >&2
    exit 1
  fi
  echo "==> staged app-update ${package_name} v${version_name} (${version_code})"
  echo "    min_supported=${min_supported} sha256=${apk_sha}"
  echo "    local_data_contract=$(printf '%s' "${contract_values}" | tr '\n' '.')"
}

require_app_update_inputs
mapfile -t expected_signer_sha256_values <"${release_signer_sha256_file}"
if [[ "${#expected_signer_sha256_values[@]}" -ne 1 \
    || ! "${expected_signer_sha256_values[0]}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "error: release APK signer pin must contain exactly one lowercase SHA-256 digest" >&2
  exit 1
fi
expected_signer_sha256="${expected_signer_sha256_values[0]}"

# Lightweight gate for CI / local smoke without docker save (see test-package-nas-app-update.sh).
if [[ "${LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY:-0}" == "1" ]]; then
  check_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-app-update-check.XXXXXX")"
  cleanup_check_root() {
    rm -rf -- "${check_root}"
  }
  trap cleanup_check_root EXIT HUP INT TERM
  echo "==> app-update check only (no docker package)"
  validate_and_stage_app_update "${check_root}/app-update"
  test -s "${check_root}/app-update/app-release.apk"
  test -s "${check_root}/app-update/app-update.json"
  echo "app-update inputs OK"
  exit 0
fi

if ! docker image inspect "${image}" >/dev/null 2>&1; then
  if [[ "${build_image}" == "1" ]]; then
    echo "==> image missing; building via build-image.sh"
    LEZI_SYNC_VERSION="${version}" LEZI_SYNC_IMAGE="${image}" \
      "${SYNC_ROOT}/build-image.sh"
  else
    echo "error: image ${image} not found locally." >&2
    echo "  run: LEZI_SYNC_VERSION=${version} ${SYNC_ROOT}/build-image.sh" >&2
    echo "  or:  LEZI_PACKAGE_BUILD_IMAGE=1 $0" >&2
    echo "  an existing tar is not sufficient because its image identity cannot be re-attested" >&2
    exit 1
  fi
fi

image_id="$("${SCRIPT_DIR}/image-config-digest.sh" "${image}")"
image_os="$(docker image inspect "${image}" --format '{{.Os}}')"
image_architecture="$(docker image inspect "${image}" --format '{{.Architecture}}')"
if [[ "${image_os}" != "linux" || "${image_architecture}" != "amd64" ]]; then
  echo "error: NAS release image must be linux/amd64, got ${image_os}/${image_architecture}" >&2
  exit 1
fi

rm -rf "${out_root}"
mkdir -p "${out_root}"

echo "==> docker save ${image}"
docker save "${image}" -o "${out_root}/${tar_name}"

echo "==> stage app-update artifacts (fail-closed)"
validate_and_stage_app_update "${out_root}/app-update"

echo "==> render docker-compose.yml"
sed \
  -e "s|__LEZI_SYNC_VERSION__|${version}|g" \
  -e "s|__LEZI_DATA_HOST_PATH__|${data_host_path}|g" \
  -e "s|__LEZI_LAN_APK_DOWNLOAD_ORIGIN__|${lan_apk_download_origin}|g" \
  "${SCRIPT_DIR}/docker-compose.nas.yml.tpl" \
  > "${out_root}/docker-compose.yml"

cp -a "${SCRIPT_DIR}/.env.example" "${out_root}/.env.example"
cp -a "${SCRIPT_DIR}/credential-deploy-lock.sh" "${out_root}/credential-deploy-lock.sh"
cp -a "${SCRIPT_DIR}/docker-compose.nas.yml.tpl" "${out_root}/docker-compose.nas.yml.tpl"
cp -a "${SCRIPT_DIR}/remote-deploy.sh" "${out_root}/remote-deploy.sh"
cp -a "${SCRIPT_DIR}/schema-cutover.sh" "${out_root}/schema-cutover.sh"
cp -a "${SCRIPT_DIR}/schema-cutover-steps.sh" "${out_root}/schema-cutover-steps.sh"
cp -a "${SCRIPT_DIR}/export-nas-credentials.sh" "${out_root}/export-nas-credentials.sh"
cp -a "${SCRIPT_DIR}/init-tls.sh" "${out_root}/init-tls.sh"
cp -a "${SCRIPT_DIR}/promote-nas-package.sh" "${out_root}/promote-nas-package.sh"
cp -a "${SCRIPT_DIR}/tls-certificate-sha256.sh" "${out_root}/tls-certificate-sha256.sh"
cp -a "${SCRIPT_DIR}/tls-spki.sh" "${out_root}/tls-spki.sh"
cp -a "${SCRIPT_DIR}/validate-nas-package.sh" "${out_root}/validate-nas-package.sh"
cp -a "${SCRIPT_DIR}/DEPLOY.md" "${out_root}/DEPLOY.md"
chmod +x "${out_root}/remote-deploy.sh"
chmod +x "${out_root}/schema-cutover.sh"
chmod +x "${out_root}/schema-cutover-steps.sh"
chmod +x "${out_root}/credential-deploy-lock.sh"
chmod +x "${out_root}/export-nas-credentials.sh"
chmod +x "${out_root}/init-tls.sh"
chmod +x "${out_root}/promote-nas-package.sh"
chmod +x "${out_root}/tls-certificate-sha256.sh"
chmod +x "${out_root}/tls-spki.sh"
chmod +x "${out_root}/validate-nas-package.sh"
git_sha="$(git -C "${REPO_ROOT}" rev-parse --short HEAD 2>/dev/null || echo unknown)"
created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
apk_sha="$(sha256sum "${out_root}/app-update/app-release.apk" | awk '{print $1}')"
package_android_version_code="$({ sed -nE 's/^[[:space:]]*"version_code"[[:space:]]*:[[:space:]]*([0-9]+)[[:space:]]*,?[[:space:]]*$/\1/p' "${out_root}/app-update/app-update.json"; } | head -1)"
package_min_supported_version_code="$({ sed -nE 's/^[[:space:]]*"min_supported_version_code"[[:space:]]*:[[:space:]]*([0-9]+)[[:space:]]*,?[[:space:]]*$/\1/p' "${out_root}/app-update/app-update.json"; } | head -1)"
attested_cutover_source() {
  case "$1:$2" in
    0.3.12:11|0.3.13:12) return 0 ;;
    *) return 1 ;;
  esac
}

case "${version}" in
  0.5.4)
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-13}"
    rollback_source_version="${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION:-0.5.3}"
    rollback_source_server_schema="${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA:-13}"
    if [[ "${package_server_schema}" != "13" \
        || "${rollback_source_version}" != "0.5.3" \
        || "${rollback_source_server_schema}" != "13" ]]; then
      echo "error: 0.5.4 requires schema 13 with 0.5.3/schema 13 rollback source" >&2
      exit 1
    fi
    ;;
  0.5.3)
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-13}"
    rollback_source_version="${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION:-0.5.2}"
    rollback_source_server_schema="${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA:-13}"
    if [[ "${package_server_schema}" != "13" \
        || "${rollback_source_version}" != "0.5.2" \
        || "${rollback_source_server_schema}" != "13" ]]; then
      echo "error: 0.5.3 requires schema 13 with 0.5.2/schema 13 rollback source" >&2
      exit 1
    fi
    ;;
  0.5.2)
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-13}"
    rollback_source_version="${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION:-0.5.1}"
    rollback_source_server_schema="${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA:-13}"
    if [[ "${package_server_schema}" != "13" \
        || "${rollback_source_version}" != "0.5.1" \
        || "${rollback_source_server_schema}" != "13" ]]; then
      echo "error: 0.5.2 requires schema 13 with 0.5.1/schema 13 rollback source" >&2
      exit 1
    fi
    ;;
  0.5.1)
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-13}"
    rollback_source_version="${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION:-0.5.0}"
    rollback_source_server_schema="${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA:-13}"
    if [[ "${package_server_schema}" != "13" \
        || "${rollback_source_version}" != "0.5.0" \
        || "${rollback_source_server_schema}" != "13" ]]; then
      echo "error: 0.5.1 requires schema 13 with 0.5.0/schema 13 rollback source" >&2
      exit 1
    fi
    ;;
  0.5.0)
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-13}"
    rollback_source_version="${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION:-0.4.8}"
    rollback_source_server_schema="${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA:-13}"
    if [[ "${package_server_schema}" != "13" \
        || "${rollback_source_version}" != "0.4.8" \
        || "${rollback_source_server_schema}" != "13" ]]; then
      echo "error: 0.5.0 requires schema 13 with 0.4.8/schema 13 rollback source" >&2
      exit 1
    fi
    ;;
  0.4.8)
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-13}"
    rollback_source_version="${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION:-0.4.7}"
    rollback_source_server_schema="${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA:-13}"
    if [[ "${package_server_schema}" != "13" \
        || "${rollback_source_version}" != "0.4.7" \
        || "${rollback_source_server_schema}" != "13" ]]; then
      echo "error: 0.4.8 requires schema 13 with 0.4.7/schema 13 rollback source" >&2
      exit 1
    fi
    ;;
  0.4.7)
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-13}"
    rollback_source_version="${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION:-0.4.6}"
    rollback_source_server_schema="${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA:-13}"
    if [[ "${rollback_source_version}" != "0.4.6" \
        || "${rollback_source_server_schema}" != "13" ]]; then
      echo "error: 0.4.7 rollback source must be 0.4.6/schema 13" >&2
      exit 1
    fi
    ;;
  0.4.6)
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-13}"
    rollback_source_version="${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION:-0.4.5}"
    rollback_source_server_schema="${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA:-13}"
    if [[ "${rollback_source_version}" != "0.4.5" \
        || "${rollback_source_server_schema}" != "13" ]]; then
      echo "error: 0.4.6 rollback source must be 0.4.5/schema 13" >&2
      exit 1
    fi
    ;;
  0.4.5)
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-13}"
    rollback_source_version="${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION:-0.4.4}"
    rollback_source_server_schema="${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA:-13}"
    if [[ "${rollback_source_version}" != "0.4.4" \
        || "${rollback_source_server_schema}" != "13" ]]; then
      echo "error: 0.4.5 rollback source must be 0.4.4/schema 13" >&2
      exit 1
    fi
    ;;
  0.4.4)
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-13}"
    rollback_source_version="${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION:-0.4.3}"
    rollback_source_server_schema="${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA:-13}"
    if [[ "${rollback_source_version}" != "0.4.3" \
        || "${rollback_source_server_schema}" != "13" ]]; then
      echo "error: 0.4.4 rollback source must be 0.4.3/schema 13" >&2
      exit 1
    fi
    ;;
  0.4.3)
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-13}"
    rollback_source_version="${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION:-0.4.2}"
    rollback_source_server_schema="${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA:-13}"
    if [[ "${rollback_source_version}" != "0.4.2" \
        || "${rollback_source_server_schema}" != "13" ]]; then
      echo "error: 0.4.3 rollback source must be 0.4.2/schema 13" >&2
      exit 1
    fi
    ;;
  0.4.2)
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-13}"
    rollback_source_version="${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION:-0.4.1}"
    rollback_source_server_schema="${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA:-13}"
    if [[ "${rollback_source_version}" != "0.4.1" \
        || "${rollback_source_server_schema}" != "13" ]]; then
      echo "error: 0.4.2 rollback source must be 0.4.1/schema 13" >&2
      exit 1
    fi
    ;;

  0.4.1)
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-13}"
    rollback_source_version="${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION:-0.4.0}"
    rollback_source_server_schema="${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA:-13}"
    if [[ "${rollback_source_version}" != "0.4.0" \
        || "${rollback_source_server_schema}" != "13" ]]; then
      echo "error: 0.4.1 rollback source must be 0.4.0/schema 13" >&2
      exit 1
    fi
    ;;
  0.4.0)
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-13}"
    rollback_source_version="${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION:-0.3.13}"
    rollback_source_server_schema="${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA:-12}"
    attested_cutover_source "${rollback_source_version}" "${rollback_source_server_schema}" \
      || {
        echo "error: 0.4.0 rollback source must be 0.3.12/schema 11 or 0.3.13/schema 12" >&2
        exit 1
      }
    ;;
  0.3.13)
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-12}"
    rollback_source_version="0.3.13"
    rollback_source_server_schema="12"
    ;;
  0.3.12)
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA:-11}"
    rollback_source_version="0.3.12"
    rollback_source_server_schema="11"
    ;;
  *)
    [[ -n "${LEZI_PACKAGE_SERVER_SCHEMA:-}" \
        && -n "${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION:-}" \
        && -n "${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA:-}" ]] || {
      echo "error: unknown releases must explicitly declare server schema and rollback source identity" >&2
      exit 1
    }
    package_server_schema="${LEZI_PACKAGE_SERVER_SCHEMA}"
    rollback_source_version="${LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION}"
    rollback_source_server_schema="${LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA}"
    ;;
esac

cat > "${out_root}/MANIFEST.json" <<EOF
{
  "name": "lezi-sync",
  "version": "${version}",
  "image": "${image}",
  "image_id": "${image_id}",
  "platform": "${platform}",
  "os": "${image_os}",
  "architecture": "${image_architecture}",
  "server_schema": "${package_server_schema}",
  "android_version_code": "${package_android_version_code}",
  "minimum_supported_version_code": "${package_min_supported_version_code}",
  "rollback_source_version": "${rollback_source_version}",
  "rollback_source_server_schema": "${rollback_source_server_schema}",
  "apk_signer_certificate_sha256": "${expected_signer_sha256}",
  "tar": "${tar_name}",
  "data_host_path": "${data_host_path}",
  "tls_host": "${tls_host}",
  "lan_apk_download_origin": "${lan_apk_download_origin}",
  "git_sha": "${git_sha}",
  "created_at": "${created_at}",
  "compose_engine": "zdocker-bundled-docker-compose-v2",
  "app_update": {
    "apk": "app-update/app-release.apk",
    "metadata": "app-update/app-update.json",
    "sha256": "${apk_sha}"
  }
}
EOF

(
  cd "${out_root}"
  sha256sum .env.example DEPLOY.md MANIFEST.json \
    app-update/app-release.apk app-update/app-update.json \
    credential-deploy-lock.sh docker-compose.nas.yml.tpl docker-compose.yml \
    export-nas-credentials.sh init-tls.sh \
    "${tar_name}" promote-nas-package.sh remote-deploy.sh \
    schema-cutover.sh schema-cutover-steps.sh \
    tls-certificate-sha256.sh tls-spki.sh \
    validate-nas-package.sh \
    > SHA256SUMS
)

"${SCRIPT_DIR}/validate-nas-package.sh" "${out_root}" "${version}" >/dev/null

echo "==> package ready: ${out_root}"
ls -la "${out_root}"
ls -la "${out_root}/app-update"
