#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-tls-test.XXXXXX")"
cleanup() {
  if [[ -n "${LEZI_TLS_TEST_IMAGE:-}" ]] && command -v docker >/dev/null 2>&1; then
    docker run --rm \
      --user 0:0 \
      -v "${test_root}:/fixture" \
      --entrypoint /bin/rm \
      "${LEZI_TLS_TEST_IMAGE}" -rf /fixture/container-data >/dev/null 2>&1 || true
  fi
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

if [[ -n "${LEZI_TLS_TEST_IMAGE:-}" ]]; then
  container_data="${test_root}/container-data"
  mkdir -p "${container_data}"
  docker run --rm \
    --user 0:0 \
    -v "${test_root}:/fixture" \
    --entrypoint /bin/sh \
    "${LEZI_TLS_TEST_IMAGE}" \
    -ec 'chown 10001:10001 /fixture/container-data && chmod 700 /fixture/container-data'

  "${SCRIPT_DIR}/init-tls.sh" "${container_data}" "${LEZI_TLS_TEST_IMAGE}" localhost
  if [[ -f "${container_data}/tls/server.crt" ]]; then
    echo "error: host unexpectedly sees the uid-10001 TLS identity" >&2
    exit 1
  fi

  container_hashes() {
    docker run --rm \
      --user 10001:10001 \
      -v "${container_data}:/data:ro" \
      --entrypoint /usr/bin/sha256sum \
      "${LEZI_TLS_TEST_IMAGE}" /data/tls/server.crt /data/tls/server.key
  }

  container_key_hash() {
    docker run --rm \
      --user 10001:10001 \
      -v "${container_data}:/data:ro" \
      --entrypoint /usr/bin/sha256sum \
      "${LEZI_TLS_TEST_IMAGE}" /data/tls/server.key
  }

  first_container_identity="$(container_hashes)"
  first_container_key="$(container_key_hash)"
  "${SCRIPT_DIR}/init-tls.sh" "${container_data}" "${LEZI_TLS_TEST_IMAGE}" localhost
  test "$(container_hashes)" = "${first_container_identity}"

  docker run --rm \
    --user 10001:10001 \
    -v "${container_data}:/data" \
    --entrypoint /bin/mv \
    "${LEZI_TLS_TEST_IMAGE}" /data/tls/server.crt /data/tls/server.crt.missing
  if "${SCRIPT_DIR}/init-tls.sh" "${container_data}" "${LEZI_TLS_TEST_IMAGE}" localhost; then
    echo "error: uid-10001 partial TLS identity was silently replaced" >&2
    exit 1
  fi
  test "$(container_key_hash)" = "${first_container_key}"
fi

echo "TLS identity persistence smoke passed"
