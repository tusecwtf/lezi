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

image="${LEZI_SYNC_IMAGE:-lezi-sync:${version}}"
data_host_path="${LEZI_DATA_HOST_PATH:-/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data}"
tls_host="${LEZI_TLS_HOST:-192.168.50.4}"
if [[ ! "${tls_host}" =~ ^[A-Za-z0-9.:-]+$ ]] || [[ "${#tls_host}" -gt 253 ]]; then
  echo "error: LEZI_TLS_HOST must be a plain DNS name or IP address" >&2
  exit 1
fi
out_root="${LEZI_NAS_PACKAGE_DIR:-${REPO_ROOT}/dist/lezi-sync-${version}-nas}"
platform="${LEZI_SYNC_PLATFORM:-linux-amd64}"
tar_name="lezi-sync-${version}-${platform}.tar"
build_image="${LEZI_PACKAGE_BUILD_IMAGE:-0}"

# Self-hosted app update inputs (fail-closed).
# Override with LEZI_RELEASE_APK / LEZI_APP_UPDATE_JSON when packaging a different build.
release_apk="${LEZI_RELEASE_APK:-${REPO_ROOT}/app/build/outputs/apk/release/app-release.apk}"
app_update_json="${LEZI_APP_UPDATE_JSON:-${SCRIPT_DIR}/app-update.json}"

echo "==> package lezi-sync ${version}"
echo "    image:   ${image}"
echo "    data:    ${data_host_path}"
echo "    tls host:${tls_host}"
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
}

validate_and_stage_app_update() {
  local dest_dir="$1"
  mkdir -p "${dest_dir}"
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
  if [[ "${meta_sha}" != "${apk_sha}" ]]; then
    echo "error: app-update.json sha256 does not match release APK" >&2
    echo "  metadata: ${meta_sha}" >&2
    echo "  apk:      ${apk_sha}" >&2
    echo "  apk path: ${release_apk}" >&2
    exit 1
  fi
  cp -a "${release_apk}" "${dest_dir}/app-release.apk"
  cp -a "${app_update_json}" "${dest_dir}/app-update.json"
  echo "==> staged app-update ${package_name} v${version_name} (${version_code})"
  echo "    min_supported=${min_supported} sha256=${apk_sha}"
}

require_app_update_inputs

if ! docker image inspect "${image}" >/dev/null 2>&1; then
  if [[ "${build_image}" == "1" ]]; then
    echo "==> image missing; building via build-image.sh"
    LEZI_SYNC_VERSION="${version}" LEZI_SYNC_IMAGE="${image}" \
      "${SYNC_ROOT}/build-image.sh"
  else
    echo "error: image ${image} not found locally." >&2
    echo "  run: LEZI_SYNC_VERSION=${version} ${SYNC_ROOT}/build-image.sh" >&2
    echo "  or:  LEZI_PACKAGE_BUILD_IMAGE=1 $0" >&2
    # Reuse existing dist tar if present (no retag).
    existing_tar="${REPO_ROOT}/dist/${tar_name}"
    if [[ -f "${existing_tar}" ]]; then
      echo "  note: found ${existing_tar}; will copy without docker save" >&2
    else
      exit 1
    fi
  fi
fi

rm -rf "${out_root}"
mkdir -p "${out_root}"

if docker image inspect "${image}" >/dev/null 2>&1; then
  echo "==> docker save ${image}"
  docker save "${image}" -o "${out_root}/${tar_name}"
else
  cp -a "${REPO_ROOT}/dist/${tar_name}" "${out_root}/${tar_name}"
fi

echo "==> stage app-update artifacts (fail-closed)"
validate_and_stage_app_update "${out_root}/app-update"

echo "==> render docker-compose.yml"
sed \
  -e "s|__LEZI_SYNC_VERSION__|${version}|g" \
  -e "s|__LEZI_DATA_HOST_PATH__|${data_host_path}|g" \
  "${SCRIPT_DIR}/docker-compose.nas.yml.tpl" \
  > "${out_root}/docker-compose.yml"

cp -a "${SCRIPT_DIR}/.env.example" "${out_root}/.env.example"
cp -a "${SCRIPT_DIR}/remote-deploy.sh" "${out_root}/remote-deploy.sh"
cp -a "${SCRIPT_DIR}/init-tls.sh" "${out_root}/init-tls.sh"
chmod +x "${out_root}/remote-deploy.sh"
chmod +x "${out_root}/init-tls.sh"

image_id=""
if docker image inspect "${image}" >/dev/null 2>&1; then
  image_id="$(docker image inspect "${image}" --format '{{.Id}}')"
fi
git_sha="$(git -C "${REPO_ROOT}" rev-parse --short HEAD 2>/dev/null || echo unknown)"
created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
apk_sha="$(sha256sum "${out_root}/app-update/app-release.apk" | awk '{print $1}')"

cat > "${out_root}/MANIFEST.json" <<EOF
{
  "name": "lezi-sync",
  "version": "${version}",
  "image": "${image}",
  "image_id": "${image_id}",
  "platform": "${platform}",
  "tar": "${tar_name}",
  "data_host_path": "${data_host_path}",
  "tls_host": "${tls_host}",
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
  sha256sum "${tar_name}" docker-compose.yml MANIFEST.json remote-deploy.sh init-tls.sh \
    app-update/app-release.apk app-update/app-update.json \
    > SHA256SUMS
)

cp -a "${SCRIPT_DIR}/DEPLOY.md" "${out_root}/DEPLOY.md" 2>/dev/null || true

echo "==> package ready: ${out_root}"
ls -la "${out_root}"
ls -la "${out_root}/app-update"
