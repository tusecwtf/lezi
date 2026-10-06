#!/usr/bin/env bash
# Shared NAS-side lease helper for the developer-machine push workflow.
# The token is a non-credential ownership nonce; credential material never
# enters this file, argv, or output.
set -euo pipefail

action="${1:-}"
lock_dir="${2:-}"
owner_token="${3:-}"

case "${action}" in
  acquire|validate|release)
    ;;
  *)
    echo "error: credential-deploy-lock action must be acquire, validate, or release" >&2
    exit 1
    ;;
esac
if [[ "${lock_dir}" != /* \
    || ! "${lock_dir}" =~ ^/[A-Za-z0-9._/-]+$ \
    || "${lock_dir}" == *'//'* \
    || "${lock_dir}" == */./* \
    || "${lock_dir}" == */../* \
    || "${lock_dir}" == */. \
    || "${lock_dir}" == */.. ]]; then
  echo "error: credential/deploy lock must be a normalized absolute NAS path" >&2
  exit 1
fi
if [[ ! "${owner_token}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "error: credential/deploy lock token must be 64 lowercase hexadecimal characters" >&2
  exit 1
fi

lock_parent="$(dirname -- "${lock_dir}")"
owner_file="${lock_dir}/owner-token"

validate_parent() {
  if [[ -L "${lock_parent}" || ! -d "${lock_parent}" ]]; then
    echo "error: credential/deploy lock parent must be a regular non-symlink directory" >&2
    exit 1
  fi
  if [[ "$(stat -c '%a' "${lock_parent}")" != "700" ]]; then
    echo "error: credential/deploy lock parent must have mode 700" >&2
    exit 1
  fi
}

validate_lease() {
  local -a owner_lines=()
  validate_parent
  if [[ -L "${lock_dir}" || ! -d "${lock_dir}" ]]; then
    echo "error: credential/deploy lease directory is missing or unsafe" >&2
    exit 1
  fi
  if [[ "$(stat -c '%a' "${lock_dir}")" != "700" ]]; then
    echo "error: credential/deploy lease directory must have mode 700" >&2
    exit 1
  fi
  if [[ -L "${owner_file}" || ! -f "${owner_file}" ]]; then
    echo "error: credential/deploy lease owner file is missing or unsafe" >&2
    exit 1
  fi
  if [[ "$(stat -c '%a' "${owner_file}")" != "600" ]]; then
    echo "error: credential/deploy lease owner file must have mode 600" >&2
    exit 1
  fi
  mapfile -t owner_lines <"${owner_file}"
  if [[ "${#owner_lines[@]}" -ne 1 \
      || ! "${owner_lines[0]}" =~ ^[0-9a-f]{64}$ \
      || "${owner_lines[0]}" != "${owner_token}" ]]; then
    echo "error: credential/deploy lease is owned by another deployment" >&2
    exit 4
  fi
}

case "${action}" in
  acquire)
    if [[ -L "${lock_parent}" ]]; then
      echo "error: credential/deploy lock parent must not be a symlink" >&2
      exit 1
    fi
    if [[ ! -e "${lock_parent}" ]]; then
      install -d -m 700 "${lock_parent}"
    fi
    validate_parent
    if ! mkdir -m 700 -- "${lock_dir}" 2>/dev/null; then
      echo "error: credential export or deployment is already in progress" >&2
      echo "  if no process is active, inspect and remove the stale lease deliberately: ${lock_dir}" >&2
      exit 4
    fi
    acquire_incomplete=1
    cleanup_incomplete_acquire() {
      if [[ "${acquire_incomplete}" == "1" ]]; then
        rm -f -- "${owner_file}"
        rmdir -- "${lock_dir}" 2>/dev/null || true
      fi
    }
    trap cleanup_incomplete_acquire EXIT HUP INT TERM
    umask 077
    printf '%s\n' "${owner_token}" >"${owner_file}"
    chmod 600 "${owner_file}"
    validate_lease
    acquire_incomplete=0
    trap - EXIT HUP INT TERM
    ;;
  validate)
    validate_lease
    ;;
  release)
    validate_lease
    rm -f -- "${owner_file}"
    if ! rmdir -- "${lock_dir}"; then
      echo "error: credential/deploy lease contains unexpected entries; refusing broad cleanup" >&2
      exit 1
    fi
    ;;
esac
