#!/bin/sh
set -eu

version="${LEZI_SYNC_VERSION:-0.3.5}"
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
  --build-arg "LEZI_SYNC_VERSION=${version}" \
  --tag "${image}" \
  --tag lezi-sync:latest \
  "${build_context}"

printf 'Built %s and lezi-sync:latest\n' "${image}"
