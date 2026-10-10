#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
printf '[package]\nname = "lezi-sync"\nversion = "0.5.5"\n\n[dependencies]\n' >"$work/Cargo.toml"
sh "$HERE/validate-version.sh" "$work/Cargo.toml" 0.5.5
for bad in '' 0.5.4 9.0.0; do
  if sh "$HERE/validate-version.sh" "$work/Cargo.toml" "$bad"; then echo 'Invalid version accepted' >&2; exit 1; fi
done
grep -Fq 'COPY docker/validate-version.sh /tmp/validate-version.sh' "$HERE/../Dockerfile"
grep -Fq 'RUN sh /tmp/validate-version.sh Cargo.toml "${LEZI_SYNC_VERSION}"' "$HERE/../Dockerfile"
# Build contexts must include the exact helper referenced by Dockerfile.
grep -Fq '"${SCRIPT_DIR}/validate-version.sh"' "$HERE/prepare-build-context.sh"
printf 'Docker explicit version contract fixtures passed\n'
