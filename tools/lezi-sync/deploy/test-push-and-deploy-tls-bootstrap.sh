#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-push-tls-test.XXXXXX")"
cleanup() {
  rm -rf -- "${test_root}"
}
trap cleanup EXIT HUP INT TERM

mkdir -p "${test_root}/bin" "${test_root}/package"
touch "${test_root}/package/remote-deploy.sh"

cat >"${test_root}/bin/ssh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"${LEZI_TEST_SSH_LOG:?}"
EOF
cat >"${test_root}/bin/scp" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "${test_root}/bin/ssh" "${test_root}/bin/scp"

ssh_log="${test_root}/ssh.log"
PATH="${test_root}/bin:${PATH}" \
  LEZI_TEST_SSH_LOG="${ssh_log}" \
  LEZI_SYNC_VERSION=0.3.3 \
  LEZI_NAS_PACKAGE_DIR="${test_root}/package" \
  LEZI_SKIP_PACKAGE=1 \
  LEZI_ALLOW_TLS_BOOTSTRAP=1 \
  NAS_SSH=test@example.invalid \
  NAS_SSH_PORT=10000 \
  NAS_REMOTE_DIR=/tmp/lezi-test-release \
  "${SCRIPT_DIR}/push-and-deploy.sh"

if ! grep -q 'LEZI_ALLOW_TLS_BOOTSTRAP=1 ./remote-deploy.sh' "${ssh_log}"; then
  echo "error: explicit TLS bootstrap authorization was not forwarded to remote-deploy" >&2
  cat "${ssh_log}" >&2
  exit 1
fi

: >"${ssh_log}"
PATH="${test_root}/bin:${PATH}" \
  LEZI_TEST_SSH_LOG="${ssh_log}" \
  LEZI_SYNC_VERSION=0.3.3 \
  LEZI_NAS_PACKAGE_DIR="${test_root}/package" \
  LEZI_SKIP_PACKAGE=1 \
  NAS_SSH=test@example.invalid \
  NAS_SSH_PORT=10000 \
  NAS_REMOTE_DIR=/tmp/lezi-test-release \
  "${SCRIPT_DIR}/push-and-deploy.sh" >/dev/null
if grep -q 'LEZI_ALLOW_TLS_BOOTSTRAP=1' "${ssh_log}"; then
  echo "error: ordinary CD forwarded TLS bootstrap authorization" >&2
  cat "${ssh_log}" >&2
  exit 1
fi

: >"${ssh_log}"
PATH="${test_root}/bin:${PATH}" \
  LEZI_TEST_SSH_LOG="${ssh_log}" \
  LEZI_SYNC_VERSION=0.3.3 \
  LEZI_NAS_PACKAGE_DIR="${test_root}/package" \
  LEZI_SKIP_PACKAGE=1 \
  LEZI_ALLOW_TLS_BOOTSTRAP=1 \
  LEZI_BOOTSTRAP_SECRET=test-bootstrap-secret-1234 \
  LEZI_FORWARD_BOOTSTRAP_SECRET=1 \
  NAS_SSH=test@example.invalid \
  NAS_SSH_PORT=10000 \
  NAS_REMOTE_DIR=/tmp/lezi-test-release \
  "${SCRIPT_DIR}/push-and-deploy.sh" >/dev/null
if ! grep -q 'LEZI_ALLOW_TLS_BOOTSTRAP=1 LEZI_BOOTSTRAP_SECRET=test-bootstrap-secret-1234 ./remote-deploy.sh' \
    "${ssh_log}"; then
  echo "error: TLS bootstrap and bootstrap secret were not forwarded together" >&2
  cat "${ssh_log}" >&2
  exit 1
fi

if PATH="${test_root}/bin:${PATH}" \
    LEZI_TEST_SSH_LOG="${ssh_log}" \
    LEZI_SYNC_VERSION=0.3.3 \
    LEZI_NAS_PACKAGE_DIR="${test_root}/package" \
    LEZI_SKIP_PACKAGE=1 \
    LEZI_ALLOW_TLS_BOOTSTRAP=2 \
    NAS_SSH=test@example.invalid \
    "${SCRIPT_DIR}/push-and-deploy.sh" >/dev/null 2>&1; then
  echo "error: invalid TLS bootstrap authorization value was accepted" >&2
  exit 1
fi

echo "push-and-deploy TLS bootstrap forwarding smoke passed"
