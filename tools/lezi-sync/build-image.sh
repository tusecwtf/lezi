#!/bin/sh
set -eu

version="${LEZI_SYNC_VERSION:-0.1.0}"
image="${LEZI_SYNC_IMAGE:-lezi-sync:${version}}"

docker build \
  --build-arg "LEZI_SYNC_VERSION=${version}" \
  --tag "${image}" \
  --tag lezi-sync:latest \
  .

printf 'Built %s and lezi-sync:latest\n' "${image}"
