#!/usr/bin/env bash
# Create a git worktree with shared Cargo target (+ notes for Gradle caches).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PATH_WT="${1:?usage: worktree-add.sh <path> <branch-or-start-point>}"
START="${2:?usage: worktree-add.sh <path> <branch-or-start-point>}"
SHARED_TARGET="${CARGO_TARGET_DIR:-$HOME/.cache/cargo-target}"
SHARED_GRADLE="${GRADLE_USER_HOME:-$HOME/.cache/gradle-user-home}"

mkdir -p "$SHARED_TARGET" "$SHARED_GRADLE"
git -C "$ROOT" worktree add "$PATH_WT" "$START"
mkdir -p "$PATH_WT/.cargo" "$PATH_WT/tools/lezi-sync/.cargo"
cat > "$PATH_WT/.cargo/config.toml" <<CFG
[build]
target-dir = "$SHARED_TARGET"
CFG
cp "$PATH_WT/.cargo/config.toml" "$PATH_WT/tools/lezi-sync/.cargo/config.toml"
echo "worktree=$PATH_WT"
echo "CARGO_TARGET_DIR=$SHARED_TARGET"
echo "GRADLE_USER_HOME=$SHARED_GRADLE  # export when running gradle"
echo "Note: Android app/build stays per-worktree; only Cargo target is unified."
