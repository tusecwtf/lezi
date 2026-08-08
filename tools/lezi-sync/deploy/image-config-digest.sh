#!/usr/bin/env bash
set -euo pipefail

image="${1:-}"
if [[ -z "${image}" || "$#" -ne 1 ]]; then
  echo "usage: $0 <image>" >&2
  exit 64
fi
if ! command -v docker >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
  echo "error: docker and jq are required to inspect the image config digest" >&2
  exit 1
fi

inspect_json="$(docker image inspect "${image}")" || {
  echo "error: unable to inspect image ${image}" >&2
  exit 1
}
if [[ "$(jq -r 'length' <<<"${inspect_json}")" != "1" ]]; then
  echo "error: docker returned an ambiguous inspect result for ${image}" >&2
  exit 1
fi

descriptor_digest="$(
  jq -r '.[0].Descriptor.Annotations["config.digest"]? // empty' <<<"${inspect_json}"
)"
image_id="$(jq -r '.[0].Id? // empty' <<<"${inspect_json}")"

if [[ -n "${descriptor_digest}" && -n "${image_id}" \
    && "${descriptor_digest}" != "${image_id}" ]]; then
  echo "error: Docker descriptor config digest disagrees with image Id for ${image}" >&2
  exit 1
fi
config_digest="${descriptor_digest:-${image_id}}"
if [[ ! "${config_digest}" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo "error: docker returned an invalid or incomplete config digest for ${image}" >&2
  exit 1
fi

printf '%s\n' "${config_digest}"
