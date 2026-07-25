#!/usr/bin/env bash
# Build (and optionally load/push) the lezi-sync Docker image.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

IMAGE_NAME="${LEZI_SYNC_IMAGE_NAME:-lezi-sync}"
VERSION="${LEZI_SYNC_VERSION:-0.1.0-dev}"
IMAGE_TAG="${IMAGE_NAME}:${VERSION}"
IMAGE_LATEST="${IMAGE_NAME}:latest"

# docker | podman (auto-detect)
if [[ -n "${CONTAINER_ENGINE:-}" ]]; then
  ENGINE="$CONTAINER_ENGINE"
elif command -v docker >/dev/null 2>&1; then
  ENGINE=docker
elif command -v podman >/dev/null 2>&1; then
  ENGINE=podman
else
  echo "error: neither docker nor podman found" >&2
  exit 1
fi

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Build the lezi family sync server image from this directory.

Options:
  -t, --tag TAG       Image version tag (default: ${VERSION})
  -n, --name NAME     Image repository name (default: ${IMAGE_NAME})
  --no-latest         Do not also tag as :latest
  --load              After build, no-op for docker (kept for symmetry)
  --print-only        Print image names and exit
  -h, --help          Show this help

Environment:
  LEZI_SYNC_IMAGE_NAME   default image name
  LEZI_SYNC_VERSION      default version tag
  CONTAINER_ENGINE       docker | podman

Examples:
  ./build-image.sh
  LEZI_SYNC_VERSION=0.2.0 ./build-image.sh
  ./build-image.sh -t 0.2.0 -n lezi-sync
  docker compose up -d --build
EOF
}

TAG_LATEST=1
PRINT_ONLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -t|--tag)
      VERSION="$2"
      IMAGE_TAG="${IMAGE_NAME}:${VERSION}"
      shift 2
      ;;
    -n|--name)
      IMAGE_NAME="$2"
      IMAGE_TAG="${IMAGE_NAME}:${VERSION}"
      IMAGE_LATEST="${IMAGE_NAME}:latest"
      shift 2
      ;;
    --no-latest)
      TAG_LATEST=0
      shift
      ;;
    --load)
      shift
      ;;
    --print-only)
      PRINT_ONLY=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

IMAGE_TAG="${IMAGE_NAME}:${VERSION}"
IMAGE_LATEST="${IMAGE_NAME}:latest"

if [[ "$PRINT_ONLY" -eq 1 ]]; then
  echo "$IMAGE_TAG"
  [[ "$TAG_LATEST" -eq 1 ]] && echo "$IMAGE_LATEST"
  exit 0
fi

echo "==> engine:  $ENGINE"
echo "==> context: $ROOT"
echo "==> image:   $IMAGE_TAG"

BUILD_ARGS=(
  -f Dockerfile
  -t "$IMAGE_TAG"
  --build-arg "LEZI_SYNC_VERSION=${VERSION}"
)

if [[ "$TAG_LATEST" -eq 1 ]]; then
  BUILD_ARGS+=(-t "$IMAGE_LATEST")
fi

# Bake version into runtime env via label rewrite in Dockerfile ENV default;
# pass as build-arg only if Dockerfile supports it — we set at run/compose time.
# Rebuild with --pull for fresher base when requested.
if [[ "${LEZI_SYNC_PULL_BASE:-0}" == "1" ]]; then
  BUILD_ARGS+=(--pull)
fi

"$ENGINE" build "${BUILD_ARGS[@]}" .

echo "==> built: $IMAGE_TAG"
if [[ "$TAG_LATEST" -eq 1 ]]; then
  echo "==> tagged: $IMAGE_LATEST"
fi
echo
echo "Run:"
echo "  docker compose up -d"
echo "  # or: $ENGINE run --rm -p 8765:8765 -v \"\$PWD/data:/data\" $IMAGE_TAG"
echo "Health:"
echo "  curl -s http://127.0.0.1:8765/health"
