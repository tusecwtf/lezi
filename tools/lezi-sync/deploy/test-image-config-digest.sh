#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-image-config-digest-test.XXXXXX")"
cleanup() {
  rm -rf -- "${test_root}"
}
trap cleanup EXIT HUP INT TERM

mock_bin="${test_root}/bin"
mkdir -p "${mock_bin}"
cat >"${mock_bin}/docker" <<'MOCK_DOCKER'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == "image" && "${2:-}" == "inspect" && "$#" -eq 3 ]] || exit 64
printf '%s\n' "${LEZI_TEST_IMAGE_INSPECT_JSON:?}"
MOCK_DOCKER
chmod +x "${mock_bin}/docker"

config_digest="sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
other_digest="sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"

run_helper() {
  LEZI_TEST_IMAGE_INSPECT_JSON="${LEZI_TEST_IMAGE_INSPECT_JSON:?}" \
    PATH="${mock_bin}:${PATH}" \
    "${SCRIPT_DIR}/image-config-digest.sh" lezi-sync:0.3.13
}

LEZI_TEST_IMAGE_INSPECT_JSON="[{\"Id\":\"${config_digest}\",\"Descriptor\":{\"Annotations\":{\"config.digest\":\"${config_digest}\"}}}]"
[[ "$(run_helper)" == "${config_digest}" ]]

# Docker's vfs/legacy image store omits Descriptor; .Id remains the config digest.
LEZI_TEST_IMAGE_INSPECT_JSON="[{\"Id\":\"${config_digest}\"}]"
[[ "$(run_helper)" == "${config_digest}" ]]

# Two available authorities must never disagree silently.
LEZI_TEST_IMAGE_INSPECT_JSON="[{\"Id\":\"${config_digest}\",\"Descriptor\":{\"Annotations\":{\"config.digest\":\"${other_digest}\"}}}]"
if run_helper >/dev/null 2>&1; then
  echo "error: mismatched image config digests were accepted" >&2
  exit 1
fi

LEZI_TEST_IMAGE_INSPECT_JSON='[{"Id":"not-a-digest"}]'
if run_helper >/dev/null 2>&1; then
  echo "error: malformed image config digest was accepted" >&2
  exit 1
fi

echo "image config digest compatibility smoke passed"
