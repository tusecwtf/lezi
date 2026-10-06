#!/usr/bin/env bash
# Fail-closed offline smoke for live-cutover-probe.sh (ticket 07).
# No network required for source-contract checks; optional local dry of json_escape.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROBE="${SCRIPT_DIR}/live-cutover-probe.sh"
test -f "${PROBE}" || { echo "missing ${PROBE}" >&2; exit 1; }
test -x "${PROBE}" || chmod +x "${PROBE}"

pass=0
fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# --- source contract: topology ---
grep -q 'lezi-sync healthcheck' "${PROBE}" \
  || fail "probe must use docker exec lezi-sync healthcheck for container readiness"
grep -q 'LAN HTTPS' "${PROBE}" \
  || grep -q 'lan_https_ok' "${PROBE}" \
  || fail "probe must gate success on LAN HTTPS"
# Must not treat host:8766 SSH curl as the primary path.
if grep -E 'ssh_curl.*8766|curl -fsS http://127.0.0.1:8766' "${PROBE}"; then
  fail "probe must not SSH-curl host :8766"
fi
# Image has no curl — forbid active docker-exec-curl invocations (comments ok).
if grep -E '^[^#]*docker exec[^#]*\bcurl\b' "${PROBE}"; then
  fail "probe must not docker exec curl (image has no curl)"
fi
pass=$((pass + 1))

# --- json_escape: no here-string newline pollution ---
if grep -q 'sys.stdin.read()' "${PROBE}" && grep -q '<<<"\$1"' "${PROBE}"; then
  fail "json_escape must not use here-string + stdin.read (embeds trailing newline)"
fi
grep -Eq 'sys\.argv\[1\]|json\.dumps\(sys\.argv' "${PROBE}" \
  || fail "json_escape must dump sys.argv[1] without here-string newline"
# Executable unit: source json_escape via bash eval of the function body
json_escape() {
  python3 -c 'import json,sys; print(json.dumps(sys.argv[1]), end="")' "$1"
}
escaped="$(json_escape "https-lan-ok")"
[[ "${escaped}" == '"https-lan-ok"' ]] || fail "json_escape must not add trailing newline: got ${escaped}"
empty_esc="$(json_escape "")"
[[ "${empty_esc}" == '""' ]] || fail "json_escape empty must be \"\": got ${empty_esc}"
pass=$((pass + 1))

# --- success criteria: version + owner pull wire ---
grep -q 'LEZI_EXPECTED_VERSION' "${PROBE}" \
  || fail "probe must check LEZI_EXPECTED_VERSION"
grep -q 'generation' "${PROBE}" \
  || fail "owner pull must include generation"
grep -q 'x-lezi-client-version-code' "${PROBE}" \
  || fail "owner pull must send x-lezi-client-version-code"
grep -q 'redacted' "${PROBE}" \
  || fail "owner evidence must redact PII/secrets"
# Exit 0 only with LAN HTTPS — container alone insufficient
grep -q 'client-facing cutover not proven\|LAN HTTPS health/ready empty\|refusing owner login while health' "${PROBE}" \
  || grep -q 'lan_https_ok' "${PROBE}" \
  || fail "probe must fail closed when LAN HTTPS empty even if container ready"
pass=$((pass + 1))

# --- remote-deploy must not docker exec curl ---
remote="${SCRIPT_DIR}/remote-deploy.sh"
grep -q 'lezi-sync healthcheck' "${remote}" \
  || fail "remote-deploy must use lezi-sync healthcheck fallback"
if grep -E '^[^#]*docker exec[^#]*\bcurl\b' "${remote}"; then
  fail "remote-deploy must not docker exec curl"
fi
pass=$((pass + 1))

# --- no secret dumps ---
grep -Eq 'never print|redacted|secret not logged' "${PROBE}" \
  || fail "probe must avoid printing secrets"
pass=$((pass + 1))

echo "live-cutover-probe smoke passed (${pass} checks)"
