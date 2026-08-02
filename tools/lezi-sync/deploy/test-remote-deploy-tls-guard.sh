#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-remote-tls-test.XXXXXX")"
cleanup() {
  rm -rf -- "${test_root}"
}
trap cleanup EXIT HUP INT TERM

package_dir="${test_root}/package"
data_root="${test_root}/live-data"
initial_root="${test_root}/initial-data"
replacement_root="${test_root}/replacement-data"
mkdir -p "${package_dir}/app-update" "${test_root}/bin"

LEZI_ALLOW_TLS_BOOTSTRAP=1 LEZI_TLS_USE_HOST_OPENSSL=1 \
  "${SCRIPT_DIR}/init-tls.sh" "${data_root}" ignored localhost >/dev/null
LEZI_ALLOW_TLS_BOOTSTRAP=1 LEZI_TLS_USE_HOST_OPENSSL=1 \
  "${SCRIPT_DIR}/init-tls.sh" "${replacement_root}" ignored localhost >/dev/null
mkdir -p "${initial_root}/tls" "${test_root}/same-key-data/tls"
cp "${data_root}/tls/server.crt" "${initial_root}/tls/server.crt"
cp "${data_root}/tls/server.key" "${initial_root}/tls/server.key"
cp "${data_root}/tls/server.key" "${test_root}/same-key-data/tls/server.key"
openssl req -x509 -new -sha256 -days 3650 \
  -key "${data_root}/tls/server.key" \
  -subj /CN=reissued.localhost \
  -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
  -out "${test_root}/same-key-data/tls/server.crt" >/dev/null 2>&1

cp "${SCRIPT_DIR}/remote-deploy.sh" "${SCRIPT_DIR}/init-tls.sh" \
  "${SCRIPT_DIR}/tls-certificate-sha256.sh" "${SCRIPT_DIR}/tls-spki.sh" \
  "${package_dir}/"
touch "${package_dir}/lezi-sync-0.3.3-linux-amd64.tar"
printf 'test apk\n' >"${package_dir}/app-update/app-release.apk"
printf '{}\n' >"${package_dir}/app-update/app-update.json"
cat >"${package_dir}/MANIFEST.json" <<'EOF'
{
  "version": "0.3.3",
  "image": "lezi-sync:0.3.3",
  "tls_host": "localhost"
}
EOF
cat >"${package_dir}/docker-compose.yml" <<EOF
services:
  lezi-sync:
    volumes:
      - ${data_root}:/data
EOF

cat >"${test_root}/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  inspect)
    if [[ " $* " == *" --format "* ]]; then
      printf 'LEZI_BOOTSTRAP_SECRET=test-bootstrap-secret-1234\n'
    fi
    ;;
  run)
    if [[ " $* " == *" -d "* ]]; then
      printf 'fake-container-id\n'
    fi
    ;;
  ps)
    printf 'lezi-sync lezi-sync:0.3.3 Up 1 second 0.0.0.0:8765->8765/tcp\n'
    ;;
esac
EOF
cat >"${test_root}/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${LEZI_TEST_DISABLE_TLS_DRIFT:-0}" != "1" \
    && ! -f "${LEZI_TEST_TLS_DRIFT_MARKER:?}" ]]; then
  cp "${LEZI_TEST_REPLACEMENT_CERT:?}" "${LEZI_TEST_LIVE_CERT:?}"
  touch "${LEZI_TEST_TLS_DRIFT_MARKER}"
fi
if [[ " $* " == *"/ready"* ]]; then
  printf '{"ok":true,"status":"ready","version":"0.3.3"}\n'
else
  printf '{"ok":true,"version":"0.3.3"}\n'
fi
EOF
chmod +x "${package_dir}/remote-deploy.sh" "${package_dir}/init-tls.sh" \
  "${package_dir}/tls-certificate-sha256.sh" "${package_dir}/tls-spki.sh" \
  "${test_root}/bin/docker" "${test_root}/bin/curl"

deploy_log="${test_root}/deploy.log"
if PATH="${test_root}/bin:${PATH}" \
    LEZI_TLS_USE_HOST_OPENSSL=1 \
    LEZI_TEST_LIVE_CERT="${data_root}/tls/server.crt" \
    LEZI_TEST_REPLACEMENT_CERT="${replacement_root}/tls/server.crt" \
    LEZI_TEST_TLS_DRIFT_MARKER="${test_root}/tls-drifted" \
    ZDOCKER_COMPOSE="${test_root}/missing-compose" \
    "${package_dir}/remote-deploy.sh" >"${deploy_log}" 2>&1; then
  echo "error: remote-deploy accepted a TLS SPKI change during container replacement" >&2
  cat "${deploy_log}" >&2
  exit 1
fi

if ! grep -q 'TLS SPKI mismatch' "${deploy_log}"; then
  echo "error: TLS drift failure did not explain the SPKI mismatch" >&2
  cat "${deploy_log}" >&2
  exit 1
fi

cp "${initial_root}/tls/server.crt" "${data_root}/tls/server.crt"
cp "${initial_root}/tls/server.key" "${data_root}/tls/server.key"
rm -f "${test_root}/tls-drifted"
same_key_log="${test_root}/same-key-deploy.log"
if PATH="${test_root}/bin:${PATH}" \
    LEZI_TLS_USE_HOST_OPENSSL=1 \
    LEZI_TEST_LIVE_CERT="${data_root}/tls/server.crt" \
    LEZI_TEST_REPLACEMENT_CERT="${test_root}/same-key-data/tls/server.crt" \
    LEZI_TEST_TLS_DRIFT_MARKER="${test_root}/tls-drifted" \
    ZDOCKER_COMPOSE="${test_root}/missing-compose" \
    "${package_dir}/remote-deploy.sh" >"${same_key_log}" 2>&1; then
  echo "error: remote-deploy accepted a replacement certificate with the same SPKI" >&2
  cat "${same_key_log}" >&2
  exit 1
fi
if ! grep -q 'TLS certificate SHA-256 mismatch' "${same_key_log}"; then
  echo "error: same-key certificate replacement did not explain the certificate mismatch" >&2
  cat "${same_key_log}" >&2
  exit 1
fi

cp "${initial_root}/tls/server.crt" "${data_root}/tls/server.crt"
cp "${initial_root}/tls/server.key" "${data_root}/tls/server.key"
stable_log="${test_root}/stable-deploy.log"
if ! PATH="${test_root}/bin:${PATH}" \
    LEZI_TLS_USE_HOST_OPENSSL=1 \
    LEZI_TEST_DISABLE_TLS_DRIFT=1 \
    LEZI_TEST_LIVE_CERT="${data_root}/tls/server.crt" \
    LEZI_TEST_REPLACEMENT_CERT="${replacement_root}/tls/server.crt" \
    LEZI_TEST_TLS_DRIFT_MARKER="${test_root}/tls-drifted" \
    ZDOCKER_COMPOSE="${test_root}/missing-compose" \
    "${package_dir}/remote-deploy.sh" >"${stable_log}" 2>&1; then
  echo "error: remote-deploy rejected a stable TLS SPKI" >&2
  cat "${stable_log}" >&2
  exit 1
fi
if ! grep -q 'post-replace TLS SPKI preserved' "${stable_log}"; then
  echo "error: stable deployment did not report post-replace SPKI preservation" >&2
  cat "${stable_log}" >&2
  exit 1
fi
if ! grep -q 'post-replace TLS certificate preserved' "${stable_log}"; then
  echo "error: stable deployment did not report exact certificate preservation" >&2
  cat "${stable_log}" >&2
  exit 1
fi

echo "remote-deploy TLS certificate and SPKI drift guard smoke passed"
