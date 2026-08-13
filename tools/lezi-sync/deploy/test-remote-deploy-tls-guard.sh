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
mkdir -p "${test_root}/config"
chmod 700 "${test_root}/config"
printf 'LEZI_BOOTSTRAP_SECRET=test-bootstrap-secret-1234\n' \
  >"${test_root}/config/lezi-sync.env"
chmod 600 "${test_root}/config/lezi-sync.env"

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
cat >"${package_dir}/validate-nas-package.sh" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
cat >"${package_dir}/credential-deploy-lock.sh" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
touch "${package_dir}/lezi-sync-0.3.3-linux-amd64.tar"
printf 'test apk\n' >"${package_dir}/app-update/app-release.apk"
printf '{}\n' >"${package_dir}/app-update/app-update.json"
cat >"${package_dir}/MANIFEST.json" <<'EOF'
{
  "version": "0.3.3",
  "image": "lezi-sync:0.3.3",
  "image_id": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "os": "linux",
  "architecture": "amd64",
  "tar": "lezi-sync-0.3.3-linux-amd64.tar",
  "tls_host": "localhost",
  "lan_apk_download_origin": "http://localhost:8767"
}
EOF
cat >"${package_dir}/docker-compose.yml" <<EOF
services:
  lezi-sync:
    environment:
      LEZI_LAN_APK_DOWNLOAD_ORIGIN: "http://localhost:8767"
    volumes:
      - ${data_root}:/data
    ports:
      - "0.0.0.0:8765:8765"
      - "0.0.0.0:8767:8767"
EOF

cat >"${test_root}/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${LEZI_TEST_DOCKER_LOG:?}"
case "${1:-}" in
  inspect)
    if [[ " $* " == *".State.Running"* ]]; then
      printf 'true\n'
    elif [[ " $* " == *".Config.Env"* ]]; then
      printf 'LEZI_BOOTSTRAP_SECRET=test-bootstrap-secret-1234\n'
    elif [[ " $* " == *".Image"* ]]; then
      printf 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n'
    fi
    ;;
  image)
    if [[ " $* " == *".Id"* ]]; then
      printf 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n'
    elif [[ " $* " == *".Os"* ]]; then
      printf 'linux\n'
    elif [[ " $* " == *".Architecture"* ]]; then
      printf 'amd64\n'
    fi
    ;;
  load|stop|rm)
    ;;
  run)
    if [[ " $* " == *" -d "* ]]; then
      printf 'fake-container-id\n'
    fi
    ;;
  ps)
    if [[ " $* " == *" -a "* ]]; then
      printf 'lezi-sync\n'
    else
      printf 'lezi-sync lezi-sync:0.3.3 Up 1 second 0.0.0.0:8765->8765/tcp\n'
    fi
    ;;
esac
EOF
cat >"${test_root}/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${LEZI_TEST_CURL_LOG:?}"
if [[ "${LEZI_TEST_DISABLE_TLS_DRIFT:-0}" != "1" \
    && ! -f "${LEZI_TEST_TLS_DRIFT_MARKER:?}" ]]; then
  cp "${LEZI_TEST_REPLACEMENT_CERT:?}" "${LEZI_TEST_LIVE_CERT:?}"
  touch "${LEZI_TEST_TLS_DRIFT_MARKER}"
fi
if [[ " $* " == *"/download/lezi.apk"* ]]; then
  printf 'test apk\n'
  exit 0
fi
if [[ " $* " == *"/ready"* ]]; then
  printf '{"ok":true,"status":"ready","version":"0.3.3"}\n'
else
  printf '{"ok":true,"version":"0.3.3"}\n'
fi
EOF
chmod +x "${package_dir}/remote-deploy.sh" "${package_dir}/init-tls.sh" \
  "${package_dir}/tls-certificate-sha256.sh" "${package_dir}/tls-spki.sh" \
  "${package_dir}/validate-nas-package.sh" \
  "${package_dir}/credential-deploy-lock.sh" \
  "${test_root}/bin/docker" "${test_root}/bin/curl"

deploy_log="${test_root}/deploy.log"
docker_log="${test_root}/docker.log"
curl_log="${test_root}/curl.log"
if PATH="${test_root}/bin:${PATH}" \
    LEZI_TEST_DOCKER_LOG="${docker_log}" \
    LEZI_TEST_CURL_LOG="${curl_log}" \
    LEZI_TLS_USE_HOST_OPENSSL=1 \
    LEZI_TEST_LIVE_CERT="${data_root}/tls/server.crt" \
    LEZI_TEST_REPLACEMENT_CERT="${replacement_root}/tls/server.crt" \
    LEZI_TEST_TLS_DRIFT_MARKER="${test_root}/tls-drifted" \
    LEZI_DEPLOY_LOCK_TOKEN="$(printf 'a%.0s' {1..64})" \
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
    LEZI_TEST_DOCKER_LOG="${docker_log}" \
    LEZI_TEST_CURL_LOG="${curl_log}" \
    LEZI_TLS_USE_HOST_OPENSSL=1 \
    LEZI_TEST_LIVE_CERT="${data_root}/tls/server.crt" \
    LEZI_TEST_REPLACEMENT_CERT="${test_root}/same-key-data/tls/server.crt" \
    LEZI_TEST_TLS_DRIFT_MARKER="${test_root}/tls-drifted" \
    LEZI_DEPLOY_LOCK_TOKEN="$(printf 'a%.0s' {1..64})" \
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
: >"${docker_log}"
: >"${curl_log}"
if ! PATH="${test_root}/bin:${PATH}" \
    LEZI_TEST_DOCKER_LOG="${docker_log}" \
    LEZI_TEST_CURL_LOG="${curl_log}" \
    LEZI_TLS_USE_HOST_OPENSSL=1 \
    LEZI_TEST_DISABLE_TLS_DRIFT=1 \
    LEZI_TEST_LIVE_CERT="${data_root}/tls/server.crt" \
    LEZI_TEST_REPLACEMENT_CERT="${replacement_root}/tls/server.crt" \
    LEZI_TEST_TLS_DRIFT_MARKER="${test_root}/tls-drifted" \
    LEZI_DEPLOY_LOCK_TOKEN="$(printf 'a%.0s' {1..64})" \
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
if ! grep -Fq -- '-e LEZI_LAN_APK_DOWNLOAD_ORIGIN=http://localhost:8767' "${docker_log}"; then
  echo "error: docker-run fallback omitted the LAN APK download origin" >&2
  cat "${docker_log}" >&2
  exit 1
fi
if ! grep -Fq -- '-p 0.0.0.0:8767:8767' "${docker_log}"; then
  echo "error: docker-run fallback did not publish LAN APK download port 8767" >&2
  cat "${docker_log}" >&2
  exit 1
fi
if grep -Fq -- '-p 0.0.0.0:8766:8766' "${docker_log}"; then
  echo "error: docker-run fallback published internal readiness port 8766" >&2
  cat "${docker_log}" >&2
  exit 1
fi
if grep -Eq '8767/(health|ready)' "${curl_log}"; then
  echo "error: remote deployment treated LAN APK port 8767 as readiness" >&2
  cat "${curl_log}" >&2
  exit 1
fi

echo "remote-deploy TLS certificate and SPKI drift guard smoke passed"
