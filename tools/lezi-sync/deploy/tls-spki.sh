#!/usr/bin/env bash
# Prints the TLS certificate SPKI SHA-256, or verifies it against an expected value.
set -euo pipefail

data_root="${1:?usage: tls-spki.sh DATA_ROOT IMAGE [EXPECTED_SHA256]}"
image="${2:?usage: tls-spki.sh DATA_ROOT IMAGE [EXPECTED_SHA256]}"
expected="${3:-}"
host_openssl="${LEZI_TLS_USE_HOST_OPENSSL:-0}"

if [[ -n "${expected}" && ! "${expected}" =~ ^[0-9A-Fa-f]{64}$ ]]; then
  echo "error: expected TLS SPKI must be 64 hexadecimal characters" >&2
  exit 1
fi

if [[ "${host_openssl}" == "1" ]]; then
  certificate="${data_root}/tls/server.crt"
  if [[ ! -f "${certificate}" ]]; then
    echo "error: TLS certificate is absent: ${certificate}" >&2
    exit 1
  fi
  digest="$({
    openssl x509 -in "${certificate}" -pubkey -noout \
      | openssl pkey -pubin -outform DER \
      | openssl dgst -sha256
  } | sed -E 's/^.*= //; s/[[:space:]]//g' | tr '[:lower:]' '[:upper:]')"
else
  docker image inspect "${image}" >/dev/null 2>&1 || {
    echo "error: TLS SPKI helper image ${image} is unavailable" >&2
    exit 1
  }
  digest="$(
    docker run --rm \
      --user 10001:10001 \
      -v "${data_root}:/data:ro" \
      --entrypoint /bin/sh \
      "${image}" \
      -ec 'openssl x509 -in /data/tls/server.crt -pubkey -noout >/tmp/tls-spki.pem
openssl pkey -pubin -in /tmp/tls-spki.pem -outform DER >/tmp/tls-spki.der
openssl dgst -sha256 /tmp/tls-spki.der' \
      | sed -E 's/^.*= //; s/[[:space:]]//g' \
      | tr '[:lower:]' '[:upper:]'
  )"
fi

if [[ ! "${digest}" =~ ^[0-9A-F]{64}$ ]]; then
  echo "error: TLS SPKI calculation did not produce 64 hexadecimal characters" >&2
  exit 1
fi

if [[ -n "${expected}" && "${digest}" != "${expected^^}" ]]; then
  echo "error: TLS SPKI mismatch: expected ${expected^^}, actual ${digest}" >&2
  exit 1
fi

printf '%s\n' "${digest}"
