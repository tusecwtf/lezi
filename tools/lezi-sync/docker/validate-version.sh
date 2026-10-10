#!/bin/sh
# No default version: a caller must attest the current Cargo package identity.
set -eu
manifest=${1:?usage: validate-version.sh Cargo.toml version}
actual=${2:-}
expected=$(sed -n '/^\[package\]/,/^\[/s/^version[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$manifest")
if [ -z "$expected" ] || [ -z "$actual" ] || [ "$actual" != "$expected" ]; then
  echo 'error: explicit LEZI_SYNC_VERSION must match Cargo package version' >&2
  exit 1
fi
