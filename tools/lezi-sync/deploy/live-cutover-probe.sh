#!/usr/bin/env bash
# Live cutover post-deploy probe (ticket 07).
#
# Probes **actual** protocol. Client-facing success requires LAN HTTPS
# health+ready (APK TOFU path). Container-internal readiness is optional
# corroboration via `docker exec lezi-sync lezi-sync healthcheck` (8766 is
# NOT published on the host; compose maps client HTTPS 8765 and invite-install HTTP 8767 only).
#
# Does **not** print LEZI_BOOTSTRAP_SECRET / docker inspect env.
# Does **not** claim APK smoke success (record that separately in apk-smoke.md).
#
# Env (explicit operator inputs; examples in cutover.rs are synthetic):
#   NAS_SSH / NAS_SSH_PORT
#   LEZI_LAN_HOST                 required explicit approved LAN host
#   LEZI_CONTAINER_NAME           default lezi-sync
#   LEZI_TLS_CACERT               optional path to data-bind tls/server.crt
#   LEZI_EVIDENCE_DIR             default <repo>/.scratch/nas-v3-offline-migrate/evidence/07
#   LEZI_EXPECTED_VERSION         default Cargo.toml package version — required in /health JSON
#   LEZI_PROBE_OWNER_LOGIN=1      optional: POST /v1/owner/login + pull
#   LEZI_BOOTSTRAP_SECRET         migration password for optional owner login only
#   LEZI_CLIENT_VERSION_CODE      default 6 (x-lezi-client-version-code for pull)
#   LEZI_PROBE_WRITE_EVIDENCE=1   default 1 — write health.json (+ owner-api-smoke.md if login)
#
# Exit: 0 only when LAN HTTPS health+ready green and version matches;
#       2 protocol drift; 1 other failure.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DEFAULT_EXPECTED_VERSION="$(
  sed -n 's/^version = "\([^"]*\)"/\1/p' "${ROOT}/tools/lezi-sync/Cargo.toml" | head -1
)"
if [[ -z "${DEFAULT_EXPECTED_VERSION}" ]]; then
  echo "error: could not determine lezi-sync version from Cargo.toml" >&2
  exit 1
fi
NAS_SSH="${NAS_SSH:?Set NAS_SSH to the explicitly approved user@NAS host}"
NAS_SSH_PORT="${NAS_SSH_PORT:?Set NAS_SSH_PORT to the explicitly approved NAS port}"
LEZI_LAN_HOST="${LEZI_LAN_HOST:?Set LEZI_LAN_HOST to the explicitly approved LAN host}"
LEZI_CONTAINER_NAME="${LEZI_CONTAINER_NAME:-lezi-sync}"
LEZI_EVIDENCE_DIR="${LEZI_EVIDENCE_DIR:-${ROOT}/.scratch/nas-v3-offline-migrate/evidence/07}"
LEZI_PROBE_WRITE_EVIDENCE="${LEZI_PROBE_WRITE_EVIDENCE:-1}"
LEZI_PROBE_OWNER_LOGIN="${LEZI_PROBE_OWNER_LOGIN:-0}"
LEZI_EXPECTED_VERSION="${LEZI_EXPECTED_VERSION:-${DEFAULT_EXPECTED_VERSION}}"
LEZI_CLIENT_VERSION_CODE="${LEZI_CLIENT_VERSION_CODE:-6}"

LAN_HTTPS_HEALTH="https://${LEZI_LAN_HOST}:8765/health"
LAN_HTTPS_READY="https://${LEZI_LAN_HOST}:8765/ready"
PRE_TLS_HTTP="http://${LEZI_LAN_HOST}:8765/health"
# Container-internal only (not published on host). Documented for evidence clarity.
CONTAINER_LOOPBACK_READY="http://127.0.0.1:8766/ready (container-internal)"

# JSON-escape a string without embedding a trailing newline from here-strings.
json_escape() {
  python3 -c 'import json,sys; print(json.dumps(sys.argv[1]), end="")' "$1"
}

# Extract .version from a health/ready JSON body; empty if unparseable.
json_version() {
  python3 -c 'import json,sys
try:
  d=json.loads(sys.argv[1])
  v=d.get("version")
  print(v if isinstance(v,str) else "")
except Exception:
  print("")
' "$1" 2>/dev/null || true
}

probe_https() {
  local url="$1"
  local args=(-fsS --connect-timeout 5)
  if [[ -n "${LEZI_TLS_CACERT:-}" && -f "${LEZI_TLS_CACERT}" ]]; then
    args+=(--cacert "${LEZI_TLS_CACERT}")
  else
    # TOFU path on LAN often uses -k for ops probe when cert not yet copied local.
    args+=(-k)
  fi
  curl "${args[@]}" "${url}" 2>/dev/null || true
}

# Optional corroboration: lezi-sync healthcheck hits container-local :8766 /ready.
# Image does not ship curl — do not docker exec curl.
container_healthcheck() {
  ssh -o BatchMode=yes -o ConnectTimeout=10 -p "${NAS_SSH_PORT}" "${NAS_SSH}" \
    "docker exec '${LEZI_CONTAINER_NAME}' lezi-sync healthcheck" >/dev/null 2>&1
}

echo "==> ticket 07 live-cutover probe (actual protocol)" >&2
echo "    expected_version=${LEZI_EXPECTED_VERSION}" >&2

container_ready="false"
if container_healthcheck; then
  container_ready="true"
  echo "container_healthcheck=ok (internal :8766 /ready)" >&2
else
  echo "container_healthcheck=miss (optional corroboration)" >&2
fi

https_health="$(probe_https "${LAN_HTTPS_HEALTH}")"
https_ready="$(probe_https "${LAN_HTTPS_READY}")"
http_drift="$(curl -fsS --connect-timeout 3 "${PRE_TLS_HTTP}" 2>/dev/null || true)"

health_ver="$(json_version "${https_health}")"
ready_ver="$(json_version "${https_ready}")"

protocol="unknown"
exit_code=1
lan_https_ok=0
if [[ -n "${https_health}" && -n "${https_ready}" ]]; then
  lan_https_ok=1
fi

if [[ "${lan_https_ok}" -eq 1 ]]; then
  if [[ "${health_ver}" != "${LEZI_EXPECTED_VERSION}" ]]; then
    protocol="version-mismatch"
    exit_code=1
    echo "error: /health version='${health_ver}' want '${LEZI_EXPECTED_VERSION}'" >&2
  elif [[ -n "${ready_ver}" && "${ready_ver}" != "${LEZI_EXPECTED_VERSION}" ]]; then
    protocol="version-mismatch"
    exit_code=1
    echo "error: /ready version='${ready_ver}' want '${LEZI_EXPECTED_VERSION}'" >&2
  else
    protocol="https-lan-ok"
    exit_code=0
    if [[ "${container_ready}" == "true" ]]; then
      protocol="https-lan-and-container-ready"
    fi
  fi
elif [[ -n "${http_drift}" ]]; then
  protocol="protocol-drift-http-still-on-8765"
  exit_code=2
  echo "error: protocol drift — plaintext HTTP still answers on 8765; HTTPS not ready" >&2
else
  protocol="unhealthy"
  echo "error: LAN HTTPS health/ready empty — client-facing cutover not proven" >&2
  if [[ "${container_ready}" == "true" ]]; then
    echo "note: container healthcheck ok but LAN HTTPS failed (insufficient for APK TOFU)" >&2
  fi
fi

echo "protocol=${protocol}" >&2
echo "https_health=${https_health:-<empty>}" >&2
echo "container_ready=${container_ready}" >&2
if [[ -n "${http_drift}" && "${protocol}" != "protocol-drift-http-still-on-8765" ]]; then
  echo "note: pre-TLS HTTP also answered (unexpected after TLS cutover)" >&2
fi

if [[ "${LEZI_PROBE_WRITE_EVIDENCE}" == "1" ]]; then
  mkdir -p "${LEZI_EVIDENCE_DIR}"
  cat >"${LEZI_EVIDENCE_DIR}/health.json" <<EOF
{
  "probed_at_utc": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "protocol": $(json_escape "${protocol}"),
  "expected_version": $(json_escape "${LEZI_EXPECTED_VERSION}"),
  "health_version": $(json_escape "${health_ver}"),
  "ready_version": $(json_escape "${ready_ver}"),
  "container_healthcheck": $(json_escape "${container_ready}"),
  "container_loopback_note": $(json_escape "${CONTAINER_LOOPBACK_READY}"),
  "lan_https_health_url": $(json_escape "${LAN_HTTPS_HEALTH}"),
  "lan_https_ready_url": $(json_escape "${LAN_HTTPS_READY}"),
  "pre_tls_http_probe_url": $(json_escape "${PRE_TLS_HTTP}"),
  "https_health_body": $(json_escape "${https_health}"),
  "https_ready_body": $(json_escape "${https_ready}"),
  "pre_tls_http_body": $(json_escape "${http_drift}"),
  "exit_code": ${exit_code}
}
EOF
  echo "wrote ${LEZI_EVIDENCE_DIR}/health.json" >&2
fi

if [[ "${LEZI_PROBE_OWNER_LOGIN}" == "1" ]]; then
  if [[ -z "${LEZI_BOOTSTRAP_SECRET:-}" || "${#LEZI_BOOTSTRAP_SECRET}" -lt 16 ]]; then
    echo "error: LEZI_PROBE_OWNER_LOGIN=1 requires LEZI_BOOTSTRAP_SECRET (≥16 chars)" >&2
    exit 1
  fi
  if [[ "${exit_code}" -ne 0 ]]; then
    echo "error: refusing owner login while health probe is not green" >&2
    exit 1
  fi
  login_url="https://${LEZI_LAN_HOST}:8765/v1/owner/login"
  # Unique 32–128 URL-safe request id (avoid re-run collisions).
  rid="$(python3 -c 'import secrets; print("t07"+secrets.token_hex(20))')"
  dname="cutover$(python3 -c 'import secrets; print(secrets.token_hex(4))')"
  # Write body to a temp file so the secret is not the only concern on argv longevity;
  # header still carries the secret (ops machine only; never printed).
  login_body_file="$(mktemp "${TMPDIR:-/tmp}/lezi-owner-login.XXXXXX")"
  login_err_file="$(mktemp "${TMPDIR:-/tmp}/lezi-owner-login-err.XXXXXX")"
  cleanup_login() {
    rm -f "${login_body_file}" "${login_err_file}"
  }
  trap cleanup_login EXIT

  curl_args=(-fsS --connect-timeout 10 -X POST "${login_url}"
    -H "content-type: application/json"
    -H "x-lezi-bootstrap-secret: ${LEZI_BOOTSTRAP_SECRET}"
    -d "{\"login_request_id\":\"${rid}\",\"device_name\":\"${dname}\"}"
    -o "${login_body_file}")
  if [[ -n "${LEZI_TLS_CACERT:-}" && -f "${LEZI_TLS_CACERT}" ]]; then
    curl_args+=(--cacert "${LEZI_TLS_CACERT}")
  else
    curl_args+=(-k)
  fi
  set +e
  curl "${curl_args[@]}" 2>"${login_err_file}"
  login_rc=$?
  set -e
  if [[ "${login_rc}" -ne 0 ]]; then
    echo "error: owner login failed (exit ${login_rc}); secret not logged" >&2
    if [[ "${LEZI_PROBE_WRITE_EVIDENCE}" == "1" ]]; then
      cat >"${LEZI_EVIDENCE_DIR}/owner-api-smoke.md" <<EOF
# Owner API smoke (ticket 07)

- probed_at_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)
- endpoint: ${login_url}
- result: **FAIL** (curl exit ${login_rc})
- secret: redacted (never printed)
- membership_id: redacted
- family_name: redacted
EOF
    fi
    exit 1
  fi

  # Parse generation + membership from login; never print tokens.
  eval "$(python3 -c '
import json,sys
d=json.load(open(sys.argv[1]))
def q(s):
  return "\""+str(s).replace("\\","\\\\").replace("\"","\\\"")+"\""
print("access="+q(d.get("access_token") or ""))
print("generation="+q(d.get("generation") or ""))
print("membership="+q(d.get("membership_id") or ""))
print("family="+q(d.get("family_name") or ""))
' "${login_body_file}")"

  if [[ -z "${access}" || -z "${generation}" ]]; then
    echo "error: owner login response missing access_token or generation" >&2
    exit 1
  fi

  pull_url="https://${LEZI_LAN_HOST}:8765/v1/pull?cursor=0&generation=${generation}"
  pull_body_file="$(mktemp "${TMPDIR:-/tmp}/lezi-pull.XXXXXX")"
  pull_args=(-fsS --connect-timeout 10
    -H "authorization: Bearer ${access}"
    -H "x-lezi-client-version-code: ${LEZI_CLIENT_VERSION_CODE}"
    -o "${pull_body_file}"
    "${pull_url}")
  if [[ -n "${LEZI_TLS_CACERT:-}" && -f "${LEZI_TLS_CACERT}" ]]; then
    pull_args+=(--cacert "${LEZI_TLS_CACERT}")
  else
    pull_args+=(-k)
  fi
  set +e
  curl "${pull_args[@]}" 2>/dev/null
  pull_rc=$?
  set -e
  if [[ "${pull_rc}" -ne 0 ]]; then
    echo "error: pull failed (exit ${pull_rc}); generation + client-version-code are required" >&2
    if [[ "${LEZI_PROBE_WRITE_EVIDENCE}" == "1" ]]; then
      cat >"${LEZI_EVIDENCE_DIR}/owner-api-smoke.md" <<EOF
# Owner API smoke (ticket 07)

- probed_at_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)
- endpoint: ${login_url}
- result: **FAIL** (pull exit ${pull_rc})
- secret: redacted (never printed)
- membership_id: redacted
- family_name: redacted
EOF
    fi
    exit 1
  fi

  pull_count="$(python3 -c '
import json,sys
d=json.load(open(sys.argv[1]))
ents=d.get("entities") or []
print(len(ents) if isinstance(ents,list) else "unknown")
' "${pull_body_file}")"

  if [[ "${LEZI_PROBE_WRITE_EVIDENCE}" == "1" ]]; then
    # Redact identifying family data in committed evidence.
    cat >"${LEZI_EVIDENCE_DIR}/owner-api-smoke.md" <<EOF
# Owner API smoke (ticket 07)

- probed_at_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)
- endpoint: ${login_url}
- result: **OK**
- membership_id: redacted
- family_name: redacted
- access_token: redacted
- generation: present (redacted)
- pull: cursor=0&generation=… + x-lezi-client-version-code=${LEZI_CLIENT_VERSION_CODE}
- pull entity count: ${pull_count}
- secret: redacted (never printed)
EOF
    echo "wrote ${LEZI_EVIDENCE_DIR}/owner-api-smoke.md" >&2
  fi
  echo "owner_login=ok pull_count=${pull_count}" >&2
  # Clear token from shell env ASAP.
  unset access generation membership family
  rm -f "${login_body_file}" "${pull_body_file}" "${login_err_file}"
  trap - EXIT
fi

exit "${exit_code}"
