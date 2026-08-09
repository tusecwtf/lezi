#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-remote-tls-inspect-test.XXXXXX")"
cleanup() {
  find "${test_root}" -path '*/tls/server.crt' -exec chmod 600 {} + 2>/dev/null || true
  rm -rf -- "${test_root}"
}
trap cleanup EXIT HUP INT TERM

snapshot_tree() {
  local root="$1"
  find -P "${root}" -printf '%P|%y|%i|%m|%T@\n' | LC_ALL=C sort
  while IFS= read -r file; do
    if [[ -r "${file}" ]]; then
      printf 'SHA256|%s|%s\n' \
        "${file#"${root}"/}" "$(sha256sum "${file}" | awk '{print $1}')"
    else
      printf 'SHA256|%s|UNREADABLE\n' "${file#"${root}"/}"
    fi
  done < <(find -P "${root}" -type f -print | LC_ALL=C sort)
}

make_package() {
  local package_dir="$1" data_root="$2"
  mkdir -p "${package_dir}/app-update"
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
    volumes:
      - ${data_root}:/data
EOF
  chmod +x "${package_dir}/remote-deploy.sh" "${package_dir}/init-tls.sh" \
    "${package_dir}/tls-certificate-sha256.sh" "${package_dir}/tls-spki.sh" \
    "${package_dir}/validate-nas-package.sh" \
    "${package_dir}/credential-deploy-lock.sh"
}

fake_bin="${test_root}/bin"
mkdir -p "${fake_bin}"
cat >"${fake_bin}/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${LEZI_TEST_DOCKER_LOG:?}"
case "${1:-}" in
  ps)
    printf 'lezi-sync\n'
    ;;
  inspect)
    if [[ " $* " == *".State.Running"* ]]; then
      printf 'true\n'
    elif [[ " $* " == *".Config.Env"* ]]; then
      printf 'LEZI_BOOTSTRAP_SECRET=test-bootstrap-secret-1234\n'
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
  load)
    ;;
  stop|rm)
    echo "error: container replacement started before TLS inspection failed" >&2
    exit 97
    ;;
esac
EOF
chmod +x "${fake_bin}/docker"

run_failure_case() {
  local case_name="$1"
  local case_root="${test_root}/cases/${case_name}"
  local data_root="${case_root}/data"
  local package_dir="${case_root}/package"
  local config_dir="${case_root}/config"
  local docker_log="${case_root}/docker.log"
  local deploy_log="${case_root}/deploy.log"
  local before after
  mkdir -p "${data_root}" "${config_dir}"
  chmod 700 "${config_dir}"
  printf 'LEZI_BOOTSTRAP_SECRET=test-bootstrap-secret-1234\n' \
    >"${config_dir}/lezi-sync.env"
  chmod 600 "${config_dir}/lezi-sync.env"
  make_package "${package_dir}" "${data_root}"

  case "${case_name}" in
    absent)
      ;;
    partial)
      mkdir -p "${data_root}/tls"
      cp "${test_root}/valid/tls/server.key" "${data_root}/tls/server.key"
      ;;
    invalid)
      mkdir -p "${data_root}/tls"
      printf 'not a certificate\n' >"${data_root}/tls/server.crt"
      printf 'not a private key\n' >"${data_root}/tls/server.key"
      ;;
    read-failure)
      cp -a "${test_root}/valid/tls" "${data_root}/tls"
      chmod 000 "${data_root}/tls/server.crt"
      ;;
    *)
      echo "error: unknown TLS fixture ${case_name}" >&2
      exit 2
      ;;
  esac

  before="$(snapshot_tree "${data_root}")"
  if PATH="${fake_bin}:${PATH}" \
      LEZI_TEST_DOCKER_LOG="${docker_log}" \
      LEZI_TLS_USE_HOST_OPENSSL=1 \
      LEZI_DEPLOY_LOCK_TOKEN="$(printf 'a%.0s' {1..64})" \
      ZDOCKER_COMPOSE="${test_root}/missing-compose" \
      "${package_dir}/remote-deploy.sh" >"${deploy_log}" 2>&1; then
    echo "error: ordinary remote deploy accepted ${case_name} TLS state" >&2
    cat "${deploy_log}" >&2
    exit 1
  fi
  after="$(snapshot_tree "${data_root}")"
  if [[ "${after}" != "${before}" ]]; then
    echo "error: ordinary remote deploy wrote the ${case_name} data bind" >&2
    diff -u <(printf '%s\n' "${before}") <(printf '%s\n' "${after}") >&2 || true
    exit 1
  fi
  if grep -Eq '(^| )(stop|rm)( |$)' "${docker_log}"; then
    echo "error: ordinary remote deploy reached stop/rm for ${case_name} TLS state" >&2
    cat "${docker_log}" >&2
    exit 1
  fi
}

mkdir -p "${test_root}/valid"
LEZI_ALLOW_TLS_BOOTSTRAP=1 LEZI_TLS_USE_HOST_OPENSSL=1 \
  "${SCRIPT_DIR}/init-tls.sh" "${test_root}/valid" ignored localhost >/dev/null

run_failure_case absent
run_failure_case partial
run_failure_case invalid
run_failure_case read-failure

echo "remote-deploy read-only TLS failure smoke passed"
