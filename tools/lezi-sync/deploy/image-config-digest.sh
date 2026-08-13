#!/usr/bin/env bash
# Print the image *config* digest: the identity docker 20 reports as
# container.Image after `docker load`. Docker 29's `.Id` is often the OCI
# manifest digest instead; do not use that for NAS rollback/cutover pins.
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
has_descriptor="$(jq -r 'if .[0].Descriptor == null then "0" else "1" end' <<<"${inspect_json}")"

config_digest_from_save() {
  local tmp tar_path digest
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/lezi-image-config.XXXXXX")"
  tar_path="${tmp}/image.tar"
  docker save "${image}" -o "${tar_path}" || {
    rm -rf -- "${tmp}"
    return 1
  }
  digest="$(
    python3 - "${tar_path}" <<'PY'
import hashlib
import json
import sys
import tarfile

path = sys.argv[1]
with tarfile.open(path) as archive:
    manifest_member = archive.extractfile("manifest.json")
    if manifest_member is None:
        raise SystemExit("docker save tar is missing manifest.json")
    manifest = json.load(manifest_member)
    if not isinstance(manifest, list) or len(manifest) != 1:
        raise SystemExit("docker save tar must contain exactly one image manifest")
    config_name = manifest[0].get("Config")
    if not isinstance(config_name, str) or not config_name:
        raise SystemExit("docker save manifest is missing Config")
    config_member = archive.extractfile(config_name)
    if config_member is None:
        raise SystemExit(f"docker save tar is missing config blob {config_name}")
    digest = hashlib.sha256(config_member.read()).hexdigest()
print(f"sha256:{digest}")
PY
  )"
  rm -rf -- "${tmp}"
  printf '%s\n' "${digest}"
}

if [[ -n "${descriptor_digest}" ]]; then
  if [[ -n "${image_id}" && "${descriptor_digest}" != "${image_id}" ]]; then
    echo "error: Docker descriptor config digest disagrees with image Id for ${image}" >&2
    exit 1
  fi
  config_digest="${descriptor_digest}"
elif [[ "${has_descriptor}" == "0" ]]; then
  # Docker 20 / vfs: .Id is the config digest the running container reports.
  config_digest="${image_id}"
else
  # Docker 29 / containerd: .Id is the OCI manifest digest. Measure the
  # config blob that NAS docker 20 will report after load.
  config_digest="$(config_digest_from_save)"
fi

if [[ ! "${config_digest}" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo "error: docker returned an invalid or incomplete config digest for ${image}" >&2
  exit 1
fi

printf '%s\n' "${config_digest}"
