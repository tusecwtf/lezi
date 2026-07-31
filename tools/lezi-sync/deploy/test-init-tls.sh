#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-tls-test.XXXXXX")"
cleanup() {
  rm -rf -- "${test_root}"
}
trap cleanup EXIT HUP INT TERM

LEZI_TLS_USE_HOST_OPENSSL=1 \
  "${SCRIPT_DIR}/init-tls.sh" "${test_root}" ignored localhost

certificate="${test_root}/tls/server.crt"
private_key="${test_root}/tls/server.key"
test -s "${certificate}"
test -s "${private_key}"
test "$(stat -c '%a' "${private_key}")" = "600"

first_certificate="$(sha256sum "${certificate}")"
first_key="$(sha256sum "${private_key}")"
LEZI_TLS_USE_HOST_OPENSSL=1 \
  "${SCRIPT_DIR}/init-tls.sh" "${test_root}" ignored localhost
test "$(sha256sum "${certificate}")" = "${first_certificate}"
test "$(sha256sum "${private_key}")" = "${first_key}"

mv "${certificate}" "${certificate}.missing"
if LEZI_TLS_USE_HOST_OPENSSL=1 \
  "${SCRIPT_DIR}/init-tls.sh" "${test_root}" ignored localhost; then
  echo "error: partial TLS identity was silently replaced" >&2
  exit 1
fi
test "$(sha256sum "${private_key}")" = "${first_key}"

echo "TLS identity persistence smoke passed"
