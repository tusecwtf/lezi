#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# Version identity is exact equality, not a hard-coded release allowlist.
# These synthetic manifests do not assert old/new wire or schema compatibility.
for version in 0.5.4 0.5.5 9.0.0; do
  printf '[package]\nname = "lezi-sync"\nversion = "%s"\n\n[dependencies]\n' "$version" >"$work/Cargo.toml"
  sh "$HERE/validate-version.sh" "$work/Cargo.toml" "$version"
  for bad in '' 0.3.13 0.5.4 0.5.5 9.0.0; do
    [[ "$bad" == "$version" ]] && continue
    if sh "$HERE/validate-version.sh" "$work/Cargo.toml" "$bad"; then echo 'Invalid version accepted' >&2; exit 1; fi
  done
done
# The current checkout must obey the same contract as the synthetic releases.
current=$(sed -n '/^\[package\]/,/^\[/s/^version[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$HERE/../Cargo.toml")
sh "$HERE/validate-version.sh" "$HERE/../Cargo.toml" "$current"
grep -Fq 'COPY docker/validate-version.sh /tmp/validate-version.sh' "$HERE/../Dockerfile"
grep -Fq 'RUN sh /tmp/validate-version.sh Cargo.toml "${LEZI_SYNC_VERSION}"' "$HERE/../Dockerfile"
# Build contexts must include the exact helper referenced by Dockerfile.
grep -Fq '"${SCRIPT_DIR}/validate-version.sh"' "$HERE/prepare-build-context.sh"
# Configuration guard only; actual Compose interpolation/build/health needs Docker.
grep -Fq 'image: ${LEZI_SYNC_IMAGE:-lezi-sync:${LEZI_SYNC_VERSION:?set LEZI_SYNC_VERSION to the Cargo.toml package version}}' "$HERE/../docker-compose.yml"
grep -Fq 'LEZI_SYNC_VERSION: ${LEZI_SYNC_VERSION:?set LEZI_SYNC_VERSION to the Cargo.toml package version}' "$HERE/../docker-compose.yml"
printf 'Docker version helper/config fixtures passed (no container build or health probe)\n'
