#!/usr/bin/env bash
# Build a NAS deploy package: image tar + zdocker-friendly compose + checksums.
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
out_root="${LEZI_NAS_PACKAGE_DIR:-${REPO_ROOT}/dist/lezi-sync-${version}-nas}"
platform="${LEZI_SYNC_PLATFORM:-linux-amd64}"
tar_name="lezi-sync-${version}-${platform}.tar"
build_image="${LEZI_PACKAGE_BUILD_IMAGE:-0}"

echo "==> package lezi-sync ${version}"
echo "    image:   ${image}"
echo "    data:    ${data_host_path}"
echo "    output:  ${out_root}"

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

echo "==> render docker-compose.yml"
sed \
  -e "s|__LEZI_SYNC_VERSION__|${version}|g" \
  -e "s|__LEZI_DATA_HOST_PATH__|${data_host_path}|g" \
  "${SCRIPT_DIR}/docker-compose.nas.yml.tpl" \
  > "${out_root}/docker-compose.yml"

cp -a "${SCRIPT_DIR}/.env.example" "${out_root}/.env.example"
cp -a "${SCRIPT_DIR}/remote-deploy.sh" "${out_root}/remote-deploy.sh"
chmod +x "${out_root}/remote-deploy.sh"

image_id=""
if docker image inspect "${image}" >/dev/null 2>&1; then
  image_id="$(docker image inspect "${image}" --format '{{.Id}}')"
fi
git_sha="$(git -C "${REPO_ROOT}" rev-parse --short HEAD 2>/dev/null || echo unknown)"
created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

cat > "${out_root}/MANIFEST.json" <<EOF
{
  "name": "lezi-sync",
  "version": "${version}",
  "image": "${image}",
  "image_id": "${image_id}",
  "platform": "${platform}",
  "tar": "${tar_name}",
  "data_host_path": "${data_host_path}",
  "git_sha": "${git_sha}",
  "created_at": "${created_at}",
  "compose_engine": "zdocker-bundled-docker-compose-v2"
}
EOF

(
  cd "${out_root}"
  sha256sum "${tar_name}" docker-compose.yml MANIFEST.json remote-deploy.sh \
    > SHA256SUMS
)

cp -a "${SCRIPT_DIR}/DEPLOY.md" "${out_root}/DEPLOY.md" 2>/dev/null || true

echo "==> package ready: ${out_root}"
ls -la "${out_root}"
