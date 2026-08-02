#!/usr/bin/env bash
# Prints the exact TLS certificate file SHA-256, or verifies it against an expected value.
set -euo pipefail

data_root="${1:?usage: tls-certificate-sha256.sh DATA_ROOT IMAGE [EXPECTED_SHA256]}"
image="${2:?usage: tls-certificate-sha256.sh DATA_ROOT IMAGE [EXPECTED_SHA256]}"
expected="${3:-}"
host_openssl="${LEZI_TLS_USE_HOST_OPENSSL:-0}"

if [[ -n "${expected}" && ! "${expected}" =~ ^[0-9A-Fa-f]{64}$ ]]; then
  echo "error: expected TLS certificate SHA-256 must be 64 hexadecimal characters" >&2
  exit 1
fi

if [[ "${host_openssl}" == "1" ]]; then
  certificate="${data_root}/tls/server.crt"
  if [[ ! -f "${certificate}" ]]; then
    echo "error: TLS certificate is absent: ${certificate}" >&2
    exit 1
  fi
  digest="$(sha256sum "${certificate}" | awk '{print toupper($1)}')"
else
  docker image inspect "${image}" >/dev/null 2>&1 || {
    echo "error: TLS certificate digest helper image ${image} is unavailable" >&2
    exit 1
  }
  digest="$(
    docker run --rm \
      --user 10001:10001 \
      -v "${data_root}:/data:ro" \
      --entrypoint /bin/sh \
      "${image}" \
      -ec 'sha256sum /data/tls/server.crt' \
      | awk '{print toupper($1)}'
  )"
fi

if [[ ! "${digest}" =~ ^[0-9A-F]{64}$ ]]; then
  echo "error: TLS certificate SHA-256 calculation did not produce 64 hexadecimal characters" >&2
  exit 1
fi

if [[ -n "${expected}" && "${digest}" != "${expected^^}" ]]; then
  echo "error: TLS certificate SHA-256 mismatch: expected ${expected^^}, actual ${digest}" >&2
  exit 1
fi

printf '%s\n' "${digest}"
