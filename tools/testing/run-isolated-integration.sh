#!/usr/bin/env bash
# Build and pin this checkout's server before required loopback-only proof.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
for tool in cargo git openssl curl sqlite3 sha256sum; do
  command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }
done
cd "$ROOT"
# Always choose this checkout, ignoring an inherited target or external binary.
export CARGO_TARGET_DIR="$ROOT/tools/lezi-sync/target/isolated-integration"
cargo build --locked --manifest-path "$ROOT/tools/lezi-sync/Cargo.toml" -p lezi-sync
export LEZI_SYNC_BIN="$CARGO_TARGET_DIR/debug/lezi-sync"
[[ -x "$LEZI_SYNC_BIN" ]] || { echo 'Current server artifact missing' >&2; exit 1; }
export LEZI_SYNC_BIN_SHA256="$(sha256sum "$LEZI_SYNC_BIN" | cut -d ' ' -f1)"
export LEZI_SYNC_SOURCE_REVISION="$(git rev-parse HEAD)"
printf 'Isolated server: %s\nSHA256: %s\nRevision: %s\n' "$LEZI_SYNC_BIN" "$LEZI_SYNC_BIN_SHA256" "$LEZI_SYNC_SOURCE_REVISION"
./gradlew :sync:testDebugUnitTest --tests '*RealServer*' --no-daemon "$@"
./gradlew :domain:testDebugUnitTest --tests '*CareLogRealServerSeam*' --no-daemon "$@"
