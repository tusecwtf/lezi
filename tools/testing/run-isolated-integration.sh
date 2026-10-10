#!/usr/bin/env bash
# Build and pin this checkout's server before required loopback-only proof.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
for tool in cargo git openssl curl sqlite3 sha256sum; do
  command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }
done
cd "$ROOT"
# The launcher cannot silently turn required seams into the fast-unit subset.
for argument in "$@"; do
  [[ "$argument" != -PleziFastUnitTests* && "$argument" != leziFastUnitTests* ]] || {
    echo "leziFastUnitTests cannot be overridden for required integration" >&2; exit 1;
  }
done
export LEZI_SYNC_SOURCE_REVISION="$(git rev-parse HEAD)"
if [[ -n "${LEZI_EXPECTED_REVISION:-}" && "$LEZI_SYNC_SOURCE_REVISION" != "$LEZI_EXPECTED_REVISION" ]]; then
  echo "Integration checkout revision differs from the requested commit" >&2; exit 1
fi
# Always choose this checkout, ignoring an inherited target or external binary.
export CARGO_TARGET_DIR="$ROOT/tools/lezi-sync/target/isolated-integration"
cargo build --locked --manifest-path "$ROOT/tools/lezi-sync/Cargo.toml" -p lezi-sync
export LEZI_SYNC_BIN="$CARGO_TARGET_DIR/debug/lezi-sync"
[[ -x "$LEZI_SYNC_BIN" ]] || { echo 'Current server artifact missing' >&2; exit 1; }
export LEZI_SYNC_BIN_SHA256="$(sha256sum "$LEZI_SYNC_BIN" | cut -d ' ' -f1)"
cargo build --locked --manifest-path "$ROOT/tools/lezi-sync/Cargo.toml" --example receipt_clock_server
export LEZI_SYNC_CLOCK_BIN="$CARGO_TARGET_DIR/debug/examples/receipt_clock_server"
[[ -x "$LEZI_SYNC_CLOCK_BIN" ]] || { echo 'Current receipt-clock artifact missing' >&2; exit 1; }
export LEZI_SYNC_CLOCK_BIN_SHA256="$(sha256sum "$LEZI_SYNC_CLOCK_BIN" | cut -d ' ' -f1)"
printf 'Isolated server: %s\nSHA256: %s\nRevision: %s\n' "$LEZI_SYNC_BIN" "$LEZI_SYNC_BIN_SHA256" "$LEZI_SYNC_SOURCE_REVISION"
printf 'Receipt-clock server: %s\nSHA256: %s\nRevision: %s\n' "$LEZI_SYNC_CLOCK_BIN" "$LEZI_SYNC_CLOCK_BIN_SHA256" "$LEZI_SYNC_SOURCE_REVISION"
./gradlew :sync:testDebugUnitTest --tests '*RealServer*' --no-daemon -PleziFastUnitTests=false "$@"
./gradlew :domain:testDebugUnitTest --tests '*CareLogRealServerSeam*' --no-daemon -PleziFastUnitTests=false "$@"
