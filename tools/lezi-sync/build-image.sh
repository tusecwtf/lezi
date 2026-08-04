#!/bin/sh
set -eu

version="${LEZI_SYNC_VERSION:-}"
if [ -z "${version}" ]; then
  version="$(sed -n 's/^version = "\([^"]*\)"/\1/p' Cargo.toml | head -1)"
fi
if [ -z "${version}" ]; then
  echo "error: could not determine LEZI_SYNC_VERSION" >&2
  exit 1
fi
image="${LEZI_SYNC_IMAGE:-lezi-sync:${version}}"
build_context="$(mktemp -d "${TMPDIR:-/tmp}/lezi-sync-build.XXXXXX")"

cleanup() {
  rm -rf -- "${build_context}"
}
trap cleanup EXIT HUP INT TERM

# Compose bind mounts may be owned by the container uid and unreadable to the
# host user. Stage only the immutable image inputs so restricted Docker
# builders never traverse runtime data directories.
mkdir "${build_context}/src"
cp Cargo.toml Cargo.lock Dockerfile "${build_context}/"
cp -R src/. "${build_context}/src/"

docker build \
  --platform linux/amd64 \
  --provenance=false \
  --build-arg "LEZI_SYNC_VERSION=${version}" \
  --tag "${image}" \
  --tag lezi-sync:latest \
  "${build_context}"

image_os="$(docker image inspect "${image}" --format '{{.Os}}')"
image_architecture="$(docker image inspect "${image}" --format '{{.Architecture}}')"
if [ "${image_os}" != linux ] || [ "${image_architecture}" != amd64 ]; then
  echo "error: built image must be linux/amd64, got ${image_os}/${image_architecture}" >&2
  exit 1
fi

printf 'Built %s and lezi-sync:latest (verified linux/amd64)\n' "${image}"
