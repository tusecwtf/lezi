#!/usr/bin/env bash
# Fail-closed smoke for docker image cargo staging (no docker build, no crates.io).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SYNC_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-docker-cargo-test.XXXXXX")"
cleanup() {
  rm -rf -- "${test_root}"
}
trap cleanup EXIT HUP INT TERM

mock_bin="${test_root}/bin"
mkdir -p "${mock_bin}"
log="${test_root}/cargo.log"

cat >"${mock_bin}/cargo" <<'MOCK_CARGO'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${LEZI_TEST_CARGO_LOG:?}"

has() {
  local needle=$1
  shift
  local arg
  for arg in "$@"; do
    if [[ "${arg}" == "${needle}" ]]; then
      return 0
    fi
  done
  return 1
}

dest=""
prev=""
for arg in "$@"; do
  if [[ "${prev}" == "--versioned-dirs" ]]; then
    dest="${arg}"
  fi
  prev="${arg}"
done

if has vendor "$@"; then
  if has --offline "$@" && [[ "${LEZI_TEST_VENDOR_OFFLINE:-ok}" == "fail" ]]; then
    echo "mock cargo vendor --offline failed" >&2
    exit 1
  fi
  if has --offline "$@" && [[ "${LEZI_TEST_VENDOR_OFFLINE:-ok}" == "fail-once" ]]; then
    if [[ ! -f "${LEZI_TEST_STATE_DIR:?}/vendor-offline-failed-once" ]]; then
      mkdir -p "${LEZI_TEST_STATE_DIR}"
      touch "${LEZI_TEST_STATE_DIR}/vendor-offline-failed-once"
      echo "mock cargo vendor --offline fail-once" >&2
      exit 1
    fi
  fi
  if [[ -z "${dest}" ]]; then
    echo "mock cargo vendor missing --versioned-dirs dest" >&2
    exit 64
  fi
  mkdir -p "${dest}/axum-0.0.0-test"
  printf 'vendored\n' >"${dest}/axum-0.0.0-test/crate"
  exit 0
fi

if has fetch "$@"; then
  if [[ "${LEZI_TEST_FETCH:-ok}" == "fail" ]]; then
    echo "mock cargo fetch failed" >&2
    exit 1
  fi
  if [[ "${LEZI_TEST_FETCH:-ok}" == "fail-once" ]]; then
    if [[ ! -f "${LEZI_TEST_STATE_DIR:?}/fetch-failed-once" ]]; then
      mkdir -p "${LEZI_TEST_STATE_DIR}"
      touch "${LEZI_TEST_STATE_DIR}/fetch-failed-once"
      echo "mock cargo fetch fail-once" >&2
      exit 1
    fi
  fi
  exit 0
fi

if has build "$@"; then
  if [[ "${LEZI_TEST_BUILD:-ok}" == "fail" ]]; then
    echo "mock cargo build failed" >&2
    exit 1
  fi
  mkdir -p "${LEZI_CARGO_BUILD_DIR:?}/target/release"
  printf '#!/bin/sh\necho mock-lezi-sync\n' >"${LEZI_CARGO_BUILD_DIR}/target/release/lezi-sync"
  chmod +x "${LEZI_CARGO_BUILD_DIR}/target/release/lezi-sync"
  exit 0
fi

echo "mock cargo: unexpected invocation: $*" >&2
exit 64
MOCK_CARGO
chmod +x "${mock_bin}/cargo"

run_prepare() {
  local dest=$1
  : >"${log}"
  PATH="${mock_bin}:${PATH}" \
    LEZI_TEST_CARGO_LOG="${log}" \
    LEZI_CARGO_RETRY_SLEEP=0 \
    "${SCRIPT_DIR}/prepare-build-context.sh" "${dest}"
}

assert_mode() {
  local dest=$1
  local expected=$2
  # shellcheck disable=SC1091
  source "${dest}/docker/build-mode.sh"
  if [[ "${LEZI_CARGO_BUILD_MODE}" != "${expected}" ]]; then
    echo "error: expected mode ${expected}, got ${LEZI_CARGO_BUILD_MODE}" >&2
    exit 1
  fi
}

dest="${test_root}/ctx-offline"
LEZI_TEST_VENDOR_OFFLINE=ok LEZI_TEST_FETCH=fail run_prepare "${dest}"
assert_mode "${dest}" vendor-offline
[[ -f "${dest}/Cargo.toml" ]]
[[ -f "${dest}/Dockerfile" ]]
[[ -d "${dest}/src" ]]
[[ -f "${dest}/docker/fetch-and-build.sh" ]]
[[ -f "${dest}/docker/empty-vendor/.keep" ]]
[[ -d "${dest}/vendor/axum-0.0.0-test" ]]
grep -q 'directory = "vendor"' "${dest}/docker/cargo-config.vendor.toml"
grep -q 'replace-with = "vendored-sources"' "${dest}/docker/cargo-config.vendor.toml"
if grep -q "${dest}" "${dest}/docker/cargo-config.vendor.toml"; then
  echo "error: vendor cargo config must not embed the host dest path" >&2
  exit 1
fi
[[ ! -e "${dest}/.cargo" ]]
# shellcheck disable=SC1091
source "${dest}/docker/build-mode.sh"
[[ "${LEZI_CARGO_OFFLINE}" == "1" ]]
[[ "${VENDOR_SRC}" == "vendor" ]]
if grep -qw fetch "${log}"; then
  echo "error: vendor-offline path contacted fetch" >&2
  exit 1
fi

dest="${test_root}/ctx-after-fetch"
LEZI_TEST_VENDOR_OFFLINE=fail-once \
  LEZI_TEST_FETCH=ok \
  LEZI_TEST_STATE_DIR="${test_root}/vendor-after-fetch-state" \
  run_prepare "${dest}"
assert_mode "${dest}" vendor-after-fetch
[[ -d "${dest}/vendor/axum-0.0.0-test" ]]
grep -qw fetch "${log}"
grep -q 'lezi-mirror' "${log}"
if ! grep vendor "${log}" | grep -q 'lezi-mirror'; then
  echo "error: post-fetch vendor must reuse the same registry replacement" >&2
  cat "${log}" >&2
  exit 1
fi

dest="${test_root}/ctx-mirror-fallback"
LEZI_TEST_VENDOR_OFFLINE=fail LEZI_TEST_FETCH=fail LEZI_CARGO_FETCH_ATTEMPTS=2 \
  run_prepare "${dest}"
assert_mode "${dest}" mirror-only
[[ ! -e "${dest}/vendor" ]]
# shellcheck disable=SC1091
source "${dest}/docker/build-mode.sh"
[[ "${LEZI_CARGO_OFFLINE}" == "0" ]]
[[ "${VENDOR_SRC}" == "docker/empty-vendor" ]]

dest="${test_root}/ctx-skip-vendor"
LEZI_CARGO_VENDOR=0 run_prepare "${dest}"
assert_mode "${dest}" mirror-only
[[ ! -e "${dest}/vendor" ]]
if grep -Eqw 'vendor|fetch' "${log}"; then
  echo "error: LEZI_CARGO_VENDOR=0 still invoked cargo vendor/fetch" >&2
  cat "${log}" >&2
  exit 1
fi

dest="${test_root}/ctx-official-index"
LEZI_TEST_VENDOR_OFFLINE=fail-once \
  LEZI_TEST_FETCH=ok \
  LEZI_TEST_STATE_DIR="${test_root}/official-index-state" \
  LEZI_CARGO_INDEX_URL=sparse+https://index.crates.io/ \
  run_prepare "${dest}"
assert_mode "${dest}" vendor-after-fetch
if grep -q 'lezi-mirror' "${log}"; then
  echo "error: official crates.io index still used a source replacement" >&2
  cat "${log}" >&2
  exit 1
fi

build_dir="${test_root}/builder"
mkdir -p "${build_dir}"
state_dir="${test_root}/fetch-state"
: >"${log}"
PATH="${mock_bin}:${PATH}" \
  LEZI_TEST_CARGO_LOG="${log}" \
  LEZI_TEST_STATE_DIR="${state_dir}" \
  LEZI_TEST_FETCH=fail-once \
  LEZI_CARGO_BUILD_DIR="${build_dir}" \
  LEZI_CARGO_FETCH_ATTEMPTS=3 \
  LEZI_CARGO_RETRY_SLEEP=0 \
  LEZI_CARGO_OFFLINE=0 \
  "${SCRIPT_DIR}/fetch-and-build.sh"
[[ -x "${build_dir}/lezi-sync" ]]
fetch_count="$(grep -cw fetch "${log}" || true)"
if [[ "${fetch_count}" -lt 2 ]]; then
  echo "error: expected cargo fetch to retry, log=${log}" >&2
  cat "${log}" >&2
  exit 1
fi

build_dir="${test_root}/builder-offline"
mkdir -p "${build_dir}"
: >"${log}"
PATH="${mock_bin}:${PATH}" \
  LEZI_TEST_CARGO_LOG="${log}" \
  LEZI_TEST_FETCH=fail \
  LEZI_CARGO_BUILD_DIR="${build_dir}" \
  LEZI_CARGO_OFFLINE=1 \
  "${SCRIPT_DIR}/fetch-and-build.sh"
[[ -x "${build_dir}/lezi-sync" ]]
if grep -qw fetch "${log}"; then
  echo "error: offline fetch-and-build contacted cargo fetch" >&2
  cat "${log}" >&2
  exit 1
fi

default_index="sparse+https://rsproxy.cn/index/"
grep -Fq "${default_index}" "${SCRIPT_DIR}/cargo-config.toml"
grep -Fq "CARGO_INDEX_URL=${default_index}" "${SYNC_ROOT}/Dockerfile"
grep -Fq 'm.daocloud.io/docker.io/library/rust:1.95-bookworm' "${SYNC_ROOT}/build-image.sh"
grep -Fq 'm.daocloud.io/docker.io/library/debian:bookworm-slim' "${SYNC_ROOT}/build-image.sh"
grep -Fq 'm.daocloud.io/docker.io/docker/dockerfile:1.7' "${SYNC_ROOT}/build-image.sh"
grep -Fq 'docker image inspect' "${SYNC_ROOT}/build-image.sh"


echo "docker cargo build-context smoke passed"
