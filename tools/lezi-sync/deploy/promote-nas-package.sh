#!/usr/bin/env bash
# Preflight or atomically promote one validated, random-suffixed NAS package
# staging directory to its stable versioned path.
set -euo pipefail

action="${1:-}"
staging_dir="${2:-}"
stable_dir="${3:-}"
version="${4:-}"

fail() {
  echo "error: NAS package promotion: $*" >&2
  exit 1
}

if [[ "${action}" != "preflight" && "${action}" != "promote" ]]; then
  fail "action must be preflight or promote"
fi
if [[ ! "${version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$ ]]; then
  fail "version is outside the release-version contract"
fi
for candidate in "${staging_dir}" "${stable_dir}"; do
  if [[ "${candidate}" != /* \
      || ! "${candidate}" =~ ^/[A-Za-z0-9._/-]+$ \
      || "${candidate}" == *'//'* \
      || "${candidate}" == */./* \
      || "${candidate}" == */../* \
      || "${candidate}" == */. \
      || "${candidate}" == */.. ]]; then
    fail "package paths must be normalized portable absolute paths"
  fi
done
if [[ "$(basename -- "${stable_dir}")" != "lezi-sync-${version}-nas" ]]; then
  fail "stable path basename must be lezi-sync-${version}-nas"
fi
incoming_prefix="${stable_dir}.incoming-"
if [[ "${staging_dir}" != "${incoming_prefix}"* ]]; then
  fail "staging path must be a nonce-suffixed sibling of the stable path"
fi
token="${staging_dir#"${incoming_prefix}"}"
if [[ ! "${token}" =~ ^[0-9a-f]{64}$ ]]; then
  fail "staging nonce must be 64 lowercase hexadecimal characters"
fi
if [[ "$(dirname -- "${staging_dir}")" != "$(dirname -- "${stable_dir}")" ]]; then
  fail "staging and stable paths must share one parent"
fi

parent_dir="$(dirname -- "${stable_dir}")"
if [[ -L "${parent_dir}" || ! -d "${parent_dir}" \
    || "$(realpath -e -- "${parent_dir}")" != "${parent_dir}" \
    || "$(stat -c '%u' "${parent_dir}")" != "$(id -u)" \
    || "$(stat -c '%a' "${parent_dir}")" != "700" ]]; then
  fail "package parent must be a canonical mode-700 directory owned by the current user"
fi
if [[ -L "${staging_dir}" || ! -d "${staging_dir}" \
    || "$(stat -c '%u' "${staging_dir}")" != "$(id -u)" \
    || "$(stat -c '%a' "${staging_dir}")" != "700" ]]; then
  fail "staging package must be a real mode-700 directory owned by the current user"
fi
validator="${staging_dir}/validate-nas-package.sh"
if [[ ! -x "${validator}" || -L "${validator}" ]]; then
  fail "staging package validator is missing or unsafe"
fi
"${validator}" "${staging_dir}" "${version}" >/dev/null

previous_dir="${stable_dir}.previous-${token}"
failed_dir="${stable_dir}.failed-${token}"
if [[ -e "${previous_dir}" || -L "${previous_dir}" \
    || -e "${failed_dir}" || -L "${failed_dir}" ]]; then
  fail "promotion recovery target already exists"
fi

had_stable=0
if [[ -e "${stable_dir}" || -L "${stable_dir}" ]]; then
  if [[ -L "${stable_dir}" || ! -d "${stable_dir}" \
      || "$(stat -c '%u' "${stable_dir}")" != "$(id -u)" \
      || "$(stat -c '%a' "${stable_dir}")" != "700" ]]; then
    fail "existing stable package is not a safe mode-700 directory"
  fi
  "${validator}" "${stable_dir}" "${version}" >/dev/null
  had_stable=1
fi

if [[ "${action}" == "preflight" ]]; then
  echo "NAS package promotion preflight passed"
  exit 0
fi

if [[ "${had_stable}" == "1" ]]; then
  mv -- "${stable_dir}" "${previous_dir}"
fi
if ! mv -- "${staging_dir}" "${stable_dir}"; then
  if [[ "${had_stable}" == "1" ]]; then
    mv -- "${previous_dir}" "${stable_dir}"
  fi
  fail "could not promote staging package; prior stable package restored"
fi

stable_validator="${stable_dir}/validate-nas-package.sh"
if ! "${stable_validator}" "${stable_dir}" "${version}" >/dev/null; then
  if [[ "${had_stable}" == "1" ]]; then
    mv -- "${stable_dir}" "${failed_dir}"
    mv -- "${previous_dir}" "${stable_dir}"
    fail "promoted package failed final validation; prior stable package restored and failed candidate retained"
  fi
  mv -- "${stable_dir}" "${staging_dir}"
  fail "promoted package failed final validation; staging path restored"
fi

if [[ "${had_stable}" == "1" ]]; then
  # Delete only a prior package that the newly promoted validator still proves
  # has the exact closed inventory for this version. `find -delete` does not
  # follow symlinks, and the validator has already rejected every special file.
  "${stable_validator}" "${previous_dir}" "${version}" >/dev/null
  find "${previous_dir}" -depth -delete
  if [[ -e "${previous_dir}" || -L "${previous_dir}" ]]; then
    fail "validated prior package could not be removed completely"
  fi
fi

echo "NAS package promoted: ${stable_dir}"
