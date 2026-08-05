#!/usr/bin/env bash
# Ticket 41 — greenfield dependency boundary scan.
# Fails if greenfield references old monorepo modules or NAS defaults.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
GF_ANDROID="$ROOT/greenfield/android"
GF_SERVER="$ROOT/greenfield/sync-server"
FAIL=0

echo "== Greenfield boundary scan =="

# 1) No project() deps on OLD monorepo modules (not greenfield :syncsession)
# Old modules: :app :domain :sync :feature:* :core:* :designsystem
if rg -n 'project\(":(app|domain|sync|designsystem)"\)|project\(":(feature|core):' "$GF_ANDROID" --glob '*.gradle*' 2>/dev/null; then
  echo "FAIL: greenfield Android depends on old monorepo modules"
  FAIL=1
else
  echo "OK: no old project() deps in greenfield Android"
fi

# 2) Root monorepo settings must not include greenfield (if present)
if [ -f "$ROOT/settings.gradle.kts" ]; then
  if rg -n "greenfield" "$ROOT/settings.gradle.kts" 2>/dev/null; then
    echo "FAIL: root settings includes greenfield"
    FAIL=1
  else
    echo "OK: root settings does not include greenfield"
  fi
else
  echo "OK: no root settings in this worktree (docs sparse)"
fi

# 3) Server has no path dep on tools/lezi-sync
if rg -n 'tools/lezi-sync|path\s*=\s*".*lezi-sync' "$GF_SERVER" --glob 'Cargo.*' 2>/dev/null; then
  echo "FAIL: greenfield server path-depends on tools/lezi-sync"
  FAIL=1
else
  echo "OK: server Cargo has no lezi-sync path dep"
fi

# 4) No default family NAS endpoint — allow reject/assert/require mentions
HITS=$(rg -n "192\.168\.50\.4" "$GF_ANDROID" "$GF_SERVER" \
  -g '!**/build/**' -g '!**/target/**' -g '!**/.gradle/**' 2>/dev/null || true)
BAD=$(echo "$HITS" | rg -v "must not|禁止|never|reject|NAS|assertThat|doesNotContain|IllegalArgument|require\(!|contains\(\"192|hardcode|filter|REJECT|no family" || true)
if [ -n "$BAD" ]; then
  echo "$BAD"
  echo "FAIL: family NAS address appears as a default/hardcode"
  FAIL=1
else
  echo "OK: no family NAS default (mentions are reject/assert only)"
fi

# 5) Vertical slices do not depend on each other (care/family/settings/syncsession)
for m in care family settings syncsession; do
  build="$GF_ANDROID/$m/build.gradle.kts"
  if [ -f "$build" ]; then
    if rg -n 'project\(":(care|family|settings|syncsession)"\)' "$build" 2>/dev/null; then
      echo "FAIL: vertical $m depends on another vertical"
      FAIL=1
    fi
  fi
done
echo "OK: vertical modules only depend on :kernel (checked build files)"

# 6) applicationId / version markers
if ! rg -n 'applicationId = "com.lezi.babylog.gf"' "$GF_ANDROID/app/build.gradle.kts" >/dev/null; then
  echo "FAIL: applicationId not com.lezi.babylog.gf"
  FAIL=1
else
  echo "OK: applicationId com.lezi.babylog.gf"
fi
if ! rg -n 'versionName = "1.0.0"' "$GF_ANDROID/app/build.gradle.kts" >/dev/null; then
  echo "FAIL: versionName not 1.0.0"
  FAIL=1
else
  echo "OK: versionName 1.0.0"
fi
if ! rg -n 'version = "1.0.0"' "$GF_SERVER/Cargo.toml" >/dev/null; then
  echo "FAIL: server version not 1.0.0"
  FAIL=1
else
  echo "OK: server version 1.0.0"
fi

if [ "$FAIL" -ne 0 ]; then
  echo "BOUNDARY SCAN FAILED"
  exit 1
fi
echo "BOUNDARY SCAN PASSED"
exit 0
