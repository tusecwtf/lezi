#!/bin/sh
# Compile lezi-sync inside the builder image.
# Vendored contexts stay offline. Online contexts fetch with retries so a
# single registry timeout does not discard crates already in the BuildKit cache.
set -eu

build_dir="${LEZI_CARGO_BUILD_DIR:-/build}"
cd "${build_dir}"

max_fetch_attempts="${LEZI_CARGO_FETCH_ATTEMPTS:-5}"
offline="${LEZI_CARGO_OFFLINE:-0}"

if [ "${offline}" = "1" ]; then
  cargo build --locked --release --offline
else
  attempt=1
  while [ "${attempt}" -le "${max_fetch_attempts}" ]; do
    if cargo fetch --locked; then
      break
    fi
    if [ "${attempt}" -eq "${max_fetch_attempts}" ]; then
      echo "error: cargo fetch failed after ${max_fetch_attempts} attempts" >&2
      exit 1
    fi
    echo "warning: cargo fetch attempt ${attempt}/${max_fetch_attempts} failed; retrying" >&2
    attempt=$((attempt + 1))
    sleep "${LEZI_CARGO_RETRY_SLEEP:-$((attempt * 2))}"
  done
  cargo build --locked --release --offline
fi

if [ ! -x "${build_dir}/target/release/lezi-sync" ]; then
  echo "error: release binary missing after cargo build" >&2
  exit 1
fi
# target/ is a cache mount and does not persist into the image layer.
cp "${build_dir}/target/release/lezi-sync" "${build_dir}/lezi-sync"
