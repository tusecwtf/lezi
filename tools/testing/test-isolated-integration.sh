#!/usr/bin/env bash
# Exercise launcher wiring only, never a NAS or real server.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/repo/tools/testing" "$work/repo/tools/lezi-sync" "$work/bin" "$work/external"
cp "$SCRIPT_DIR/run-isolated-integration.sh" "$work/repo/tools/testing/"
printf '[package]\nname="lezi-sync"\nversion="0.5.5"\n' >"$work/repo/tools/lezi-sync/Cargo.toml"
printf '#!/bin/sh\nexit 99\n' >"$work/external/lezi-sync"
chmod +x "$work/external/lezi-sync"
touch -t 205001010000 "$work/external/lezi-sync"
cat >"$work/bin/cargo" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$TEST_ROOT/build.log"
common="build --locked --manifest-path $TEST_ROOT/repo/tools/lezi-sync/Cargo.toml"
if [[ "$*" == "$common -p lezi-sync" ]]; then
  artifact="$CARGO_TARGET_DIR/debug/lezi-sync"
  mode="${TEST_ARTIFACT:-valid}"
elif [[ "$*" == "$common --example receipt_clock_server" ]]; then
  artifact="$CARGO_TARGET_DIR/debug/examples/receipt_clock_server"
  mode="${TEST_CLOCK_ARTIFACT:-valid}"
else
  echo "Unexpected build invocation" >&2; exit 1
fi
[[ "$CARGO_TARGET_DIR" == "$TEST_ROOT/repo/tools/lezi-sync/target/isolated-integration" ]]
[[ ${TEST_BUILD_FAIL:-0} == 0 ]] || exit 73
mkdir -p "$(dirname "$artifact")"
rm -f "$artifact"
[[ "$mode" != missing ]] || exit 0
printf '#!/bin/sh\nexit 0\n' >"$artifact"
[[ "$mode" == nonexecutable ]] || chmod +x "$artifact"
SH
cat >"$work/bin/git" <<'SH'
#!/bin/sh
[ "$*" = 'rev-parse HEAD' ] || exit 1
printf '0123456789012345678901234567890123456789\n'
SH
for tool in openssl curl sqlite3; do printf '#!/bin/sh\nexit 0\n' >"$work/bin/$tool"; done
cat >"$work/repo/gradlew" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ "$LEZI_SYNC_BIN" == "$TEST_ROOT/repo/tools/lezi-sync/target/isolated-integration/debug/lezi-sync" ]]
[[ "$(sha256sum "$LEZI_SYNC_BIN" | cut -d ' ' -f1)" == "$LEZI_SYNC_BIN_SHA256" ]]
[[ "$LEZI_SYNC_SOURCE_REVISION" == 0123456789012345678901234567890123456789 ]]
[[ "$LEZI_SYNC_CLOCK_BIN" == "$TEST_ROOT/repo/tools/lezi-sync/target/isolated-integration/debug/examples/receipt_clock_server" ]]
[[ "$(sha256sum "$LEZI_SYNC_CLOCK_BIN" | cut -d ' ' -f1)" == "$LEZI_SYNC_CLOCK_BIN_SHA256" ]]
printf '%s\n' "$*" >> "$TEST_ROOT/gradle.log"
[[ ${TEST_GRADLE_FAIL:-0} == 0 ]] || exit 74
SH
chmod +x "$work/bin/"* "$work/repo/gradlew"
export TEST_ROOT="$work" PATH="$work/bin:$PATH"
export LEZI_EXPECTED_REVISION=0123456789012345678901234567890123456789
CARGO_TARGET_DIR="$work/external" LEZI_SYNC_BIN="$work/external/lezi-sync" \
  bash "$work/repo/tools/testing/run-isolated-integration.sh" --stacktrace
[[ $(wc -l <"$work/gradle.log") == 2 ]]
grep -Fq ':sync:testDebugUnitTest --tests *RealServer* --no-daemon -PleziFastUnitTests=false --stacktrace' "$work/gradle.log"
grep -Fq ':domain:testDebugUnitTest --tests *CareLogRealServerSeam* --no-daemon -PleziFastUnitTests=false --stacktrace' "$work/gradle.log"
rm "$work/gradle.log" "$work/build.log"
if LEZI_EXPECTED_REVISION=incorrect bash "$work/repo/tools/testing/run-isolated-integration.sh" >"$work/revision.log" 2>&1; then
  echo "Unexpected checkout revision accepted" >&2; exit 1
fi
grep -q "checkout revision differs" "$work/revision.log"
[[ ! -e "$work/gradle.log" && ! -e "$work/build.log" ]]
if bash "$work/repo/tools/testing/run-isolated-integration.sh" -PleziFastUnitTests=true >"$work/fast.log" 2>&1; then
  echo "Required integration allowed fast-unit exclusion" >&2; exit 1
fi
grep -q "cannot be overridden" "$work/fast.log"
[[ ! -e "$work/gradle.log" ]]
if TEST_BUILD_FAIL=1 bash "$work/repo/tools/testing/run-isolated-integration.sh"; then echo 'Build failure swallowed' >&2; exit 1; fi
[[ ! -e "$work/gradle.log" ]]
if TEST_GRADLE_FAIL=1 bash "$work/repo/tools/testing/run-isolated-integration.sh"; then echo 'Test failure swallowed' >&2; exit 1; fi
[[ $(wc -l <"$work/gradle.log") == 1 ]]
for mode in missing nonexecutable; do
  rm -f "$work/gradle.log"
  if TEST_ARTIFACT="$mode" bash "$work/repo/tools/testing/run-isolated-integration.sh" >"$work/$mode.log" 2>&1; then
    echo "Invalid current artifact accepted: $mode" >&2; exit 1
  fi
  grep -q 'Current server artifact missing' "$work/$mode.log"
  [[ ! -e "$work/gradle.log" ]]
done
for mode in missing nonexecutable; do
  rm -f "$work/gradle.log"
  if TEST_CLOCK_ARTIFACT="$mode" bash "$work/repo/tools/testing/run-isolated-integration.sh" >"$work/clock-$mode.log" 2>&1; then
    echo "Invalid receipt-clock artifact accepted: $mode" >&2; exit 1
  fi
  grep -q 'Current receipt-clock artifact missing' "$work/clock-$mode.log"
  [[ ! -e "$work/gradle.log" ]]
done
# An absent prerequisite must fail before building/testing, not turn the seam
# into skipped tests. Use a minimal PATH so a machine-local sqlite cannot mask it.
mkdir "$work/no-sqlite"
for command in bash dirname cargo git openssl curl sha256sum; do
  ln -s "$(command -v "$command")" "$work/no-sqlite/$command"
done
if PATH="$work/no-sqlite" "$work/no-sqlite/bash" "$work/repo/tools/testing/run-isolated-integration.sh" >"$work/missing-tool.log" 2>&1; then
  echo 'Missing required prerequisite accepted' >&2; exit 1
fi
grep -q 'Missing required tool: sqlite3' "$work/missing-tool.log"
[[ ! -e "$work/gradle.log" ]]
printf 'isolated integration launcher fixtures passed\n'
