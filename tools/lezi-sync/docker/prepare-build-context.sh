#!/bin/sh
# Stage an isolated docker build context for lezi-sync.
#
# The context contains only image inputs (manifest, lock, sources, Dockerfile,
# cargo helpers). It never walks host data-bind directories.
#
# Default: vendor crates from the host cargo cache so the builder never contacts
# crates.io. Missing crates are fetched through LEZI_CARGO_INDEX_URL (rsproxy
# sparse by default) and then vendored. Set LEZI_CARGO_VENDOR=0 to skip vendoring
# and let the Dockerfile fetch via the same index.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
SYNC_ROOT=$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)
dest=${1:-}
if [ -z "${dest}" ]; then
  echo "usage: prepare-build-context.sh <dest-dir>" >&2
  exit 2
fi

DEFAULT_INDEX_URL="sparse+https://rsproxy.cn/index/"
index_url="${LEZI_CARGO_INDEX_URL:-${DEFAULT_INDEX_URL}}"
vendor_enabled="${LEZI_CARGO_VENDOR:-1}"
max_fetch_attempts="${LEZI_CARGO_FETCH_ATTEMPTS:-5}"

mkdir -p "${dest}/src" "${dest}/docker/empty-vendor"

cp "${SYNC_ROOT}/Cargo.toml" "${SYNC_ROOT}/Cargo.lock" "${SYNC_ROOT}/Dockerfile" "${dest}/"
cp -R "${SYNC_ROOT}/src/." "${dest}/src/"
cp "${SCRIPT_DIR}/cargo-config.toml" \
  "${SCRIPT_DIR}/project-cargo-config.toml" \
  "${SCRIPT_DIR}/fetch-and-build.sh" \
  "${SCRIPT_DIR}/validate-version.sh" \
  "${dest}/docker/"
cp "${SCRIPT_DIR}/empty-vendor/.keep" "${dest}/docker/empty-vendor/"

# Refuse to ship a developer .cargo/config.toml (it often has a host target-dir).
if [ -e "${dest}/.cargo" ]; then
  echo "error: staged context must not contain .cargo/" >&2
  exit 1
fi

write_mode() {
  printf '%s\n' \
    "LEZI_CARGO_OFFLINE=$1" \
    "VENDOR_SRC=$2" \
    "PROJECT_CARGO_CONFIG=$3" \
    "LEZI_CARGO_BUILD_MODE=$4" \
    "CARGO_INDEX_URL=${index_url}" \
    >"${dest}/docker/build-mode.sh"
}

write_vendor_config() {
  cat >"${dest}/docker/cargo-config.vendor.toml" <<'EOF'
[source.crates-io]
replace-with = "vendored-sources"

[source.vendored-sources]
directory = "vendor"
EOF
}

use_mirror_replacement() {
  if [ "${LEZI_CARGO_MIRROR:-}" = "off" ] || [ "${LEZI_CARGO_MIRROR:-}" = "crates-io" ]; then
    return 1
  fi
  case "${index_url}" in
    crates-io | off | '' | sparse+https://index.crates.io | sparse+https://index.crates.io/ | https://github.com/rust-lang/crates.io-index | https://github.com/rust-lang/crates.io-index.git)
      return 1
      ;;
  esac
  return 0
}

run_cargo() {
  # Cargo 1.95+ rejects `--manifest-path` before the subcommand. Run from the
  # crate root instead of passing a pre-command global.
  (
    CDPATH= cd -- "${SYNC_ROOT}"
    env -u CARGO_TARGET_DIR \
      CARGO_NET_RETRY=10 \
      CARGO_HTTP_TIMEOUT="${CARGO_HTTP_TIMEOUT:-120}" \
      CARGO_HTTP_MULTIPLEXING=false \
      CARGO_REGISTRIES_CRATES_IO_PROTOCOL=sparse \
      cargo "$@"
  )
}

run_cargo_maybe_mirror() {
  # Crates fetched through a replacement registry land in that registry's
  # cache directory. Later vendor/fetch must use the same replacement.
  if use_mirror_replacement; then
    run_cargo \
      --config "source.crates-io.replace-with=\"lezi-mirror\"" \
      --config "source.lezi-mirror.registry=\"${index_url}\"" \
      --config "net.retry=10" \
      "$@"
  else
    run_cargo --config "net.retry=10" "$@"
  fi
}

fetch_with_retry() {
  attempt=1
  while [ "${attempt}" -le "${max_fetch_attempts}" ]; do
    if run_cargo_maybe_mirror fetch --locked; then
      return 0
    fi
    if [ "${attempt}" -eq "${max_fetch_attempts}" ]; then
      echo "error: cargo fetch failed after ${max_fetch_attempts} attempts" >&2
      return 1
    fi
    echo "warning: cargo fetch attempt ${attempt}/${max_fetch_attempts} failed; retrying" >&2
    attempt=$((attempt + 1))
    sleep "${LEZI_CARGO_RETRY_SLEEP:-$((attempt * 2))}"
  done
}

vendor_from_crates_io_cache() {
  run_cargo vendor --locked --offline --versioned-dirs "${dest}/vendor" >/dev/null
}

vendor_from_fetched_cache() {
  if run_cargo_maybe_mirror vendor --locked --offline --versioned-dirs "${dest}/vendor" >/dev/null 2>/dev/null; then
    return 0
  fi
  run_cargo_maybe_mirror vendor --locked --versioned-dirs "${dest}/vendor"
}

if [ ! -f "${SYNC_ROOT}/Cargo.lock" ]; then
  echo "error: Cargo.lock is required for a locked image build" >&2
  exit 1
fi

if [ "${vendor_enabled}" = "1" ]; then
  if vendor_from_crates_io_cache 2>/dev/null; then
    write_vendor_config
    write_mode 1 vendor docker/cargo-config.vendor.toml vendor-offline
    echo "cargo vendor: reused host crates.io cache (docker build will be --offline)"
  else
    echo "cargo vendor: host crates.io cache incomplete; fetching via ${index_url}"
    if fetch_with_retry && vendor_from_fetched_cache; then
      write_vendor_config
      write_mode 1 vendor docker/cargo-config.vendor.toml vendor-after-fetch
      echo "cargo vendor: fetched missing crates via ${index_url} (docker build will be --offline)"
    else
      echo "warning: could not vendor crates; Dockerfile will fetch via ${index_url}" >&2
      rm -rf "${dest}/vendor"
      write_mode 0 docker/empty-vendor docker/project-cargo-config.toml mirror-only
    fi
  fi
else
  write_mode 0 docker/empty-vendor docker/project-cargo-config.toml mirror-only
  echo "cargo vendor: skipped (LEZI_CARGO_VENDOR=${vendor_enabled})"
fi
