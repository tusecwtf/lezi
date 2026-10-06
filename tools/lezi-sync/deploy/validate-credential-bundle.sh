#!/usr/bin/env bash
# Validate a plaintext credential bundle entirely through pipes/process memory,
# then forward it byte-for-byte to stdout. No local plaintext file is created.
set -euo pipefail
export LC_ALL=C

fail() {
  echo "error: credential bundle validation failed: $*" >&2
  exit 1
}

mapfile -t bundle_lines
if [[ "${#bundle_lines[@]}" -eq 0 ]]; then
  exit 1
fi
if [[ "${#bundle_lines[@]}" -ne 6 \
    || "${bundle_lines[0]}" != 'LEZI_CREDENTIAL_BUNDLE_V1' \
    || "${bundle_lines[1]}" != bootstrap_secret_b64=* \
    || "${bundle_lines[2]}" != server_crt_b64=* \
    || "${bundle_lines[3]}" != server_key_b64=* \
    || ! "${bundle_lines[4]}" =~ ^certificate_sha256=[0-9a-f]{64}$ \
    || ! "${bundle_lines[5]}" =~ ^spki_sha256=[0-9a-f]{64}$ ]]; then
  fail "invalid or unsupported six-line schema"
fi

secret_b64="${bundle_lines[1]#bootstrap_secret_b64=}"
certificate_b64="${bundle_lines[2]#server_crt_b64=}"
private_key_b64="${bundle_lines[3]#server_key_b64=}"
expected_certificate_sha256="${bundle_lines[4]#certificate_sha256=}"
expected_spki_sha256="${bundle_lines[5]#spki_sha256=}"
base64_pattern='^[A-Za-z0-9+/]+={0,2}$'
if [[ ! "${secret_b64}" =~ ${base64_pattern} \
    || ! "${certificate_b64}" =~ ${base64_pattern} \
    || ! "${private_key_b64}" =~ ${base64_pattern} ]]; then
  fail "invalid base64 field"
fi
if ! printf '%s' "${secret_b64}" | base64 -d >/dev/null 2>&1 \
    || ! printf '%s' "${certificate_b64}" | base64 -d >/dev/null 2>&1 \
    || ! printf '%s' "${private_key_b64}" | base64 -d >/dev/null 2>&1; then
  fail "undecodable base64 field"
fi

bootstrap_secret="$(printf '%s' "${secret_b64}" | base64 -d)"
bootstrap_secret_bytes="$(
  printf '%s' "${secret_b64}" | base64 -d | wc -c | tr -d '[:space:]'
)"
if [[ -z "${bootstrap_secret}" || "${#bootstrap_secret}" -lt 16 \
    || "${bootstrap_secret_bytes}" -ne "${#bootstrap_secret}" \
    || "${bootstrap_secret}" == *$'\n'* \
    || "${bootstrap_secret}" == *$'\r'* ]]; then
  fail "invalid bootstrap secret"
fi

certificate="$(printf '%s' "${certificate_b64}" | base64 -d)"
private_key="$(printf '%s' "${private_key_b64}" | base64 -d)"
if ! openssl x509 -in <(printf '%s\n' "${certificate}") \
      -checkend 86400 -noout >/dev/null 2>&1 \
    || ! openssl pkey -in <(printf '%s\n' "${private_key}") \
      -check -noout >/dev/null 2>&1; then
  fail "invalid or near-expiry TLS material"
fi
certificate_public_key="$(
  openssl x509 -in <(printf '%s\n' "${certificate}") -pubkey -noout
)"
private_public_key="$(
  openssl pkey -in <(printf '%s\n' "${private_key}") -pubout
)"
if [[ "${certificate_public_key}" != "${private_public_key}" ]]; then
  fail "TLS certificate and private key do not match"
fi

actual_certificate_sha256="$(
  printf '%s' "${certificate_b64}" \
    | base64 -d \
    | sha256sum \
    | awk '{print $1}'
)"
actual_spki_sha256="$(
  printf '%s\n' "${certificate_public_key}" \
    | openssl pkey -pubin -outform DER \
    | sha256sum \
    | awk '{print $1}'
)"
if [[ "${actual_certificate_sha256}" != "${expected_certificate_sha256}" \
    || "${actual_spki_sha256}" != "${expected_spki_sha256}" ]]; then
  fail "TLS digest mismatch"
fi

printf '%s\n' "${bundle_lines[@]}"
