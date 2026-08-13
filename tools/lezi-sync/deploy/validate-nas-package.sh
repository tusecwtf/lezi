#!/usr/bin/env bash
# Validate an unpacked NAS package before any image load or container change.
set -euo pipefail

package_dir="${1:-.}"
requested_version="${2:-}"

fail() {
  echo "error: NAS package integrity: $*" >&2
  exit 1
}

if [[ ! -d "${package_dir}" || -L "${package_dir}" ]]; then
  fail "package path must be a real directory"
fi
package_dir="$(cd "${package_dir}" && pwd)"
cd "${package_dir}"

manifest_string() {
  local key="$1"
  local -a values=()
  mapfile -t values < <(
    sed -nE \
      "s/^[[:space:]]*\"${key}\"[[:space:]]*:[[:space:]]*\"([^\"]*)\"[[:space:]]*,?[[:space:]]*$/\\1/p" \
      MANIFEST.json
  )
  if [[ "${#values[@]}" -ne 1 || -z "${values[0]}" ]]; then
    fail "MANIFEST.json must contain exactly one non-empty ${key} string"
  fi
  printf '%s' "${values[0]}"
}

fixed_files=(
  .env.example
  DEPLOY.md
  MANIFEST.json
  SHA256SUMS
  app-update/app-release.apk
  app-update/app-update.json
  credential-deploy-lock.sh
  docker-compose.nas.yml.tpl
  docker-compose.yml
  export-nas-credentials.sh
  init-tls.sh
  promote-nas-package.sh
  remote-deploy.sh
  schema-cutover.sh
  schema-cutover-steps.sh
  tls-certificate-sha256.sh
  tls-spki.sh
  validate-nas-package.sh
)

for file in "${fixed_files[@]}"; do
  if [[ ! -f "${file}" || -L "${file}" ]]; then
    fail "required regular file is missing or is a symlink: ${file}"
  fi
done

version="$(manifest_string version)"
image="$(manifest_string image)"
image_id="$(manifest_string image_id)"
platform="$(manifest_string platform)"
image_os="$(manifest_string os)"
image_architecture="$(manifest_string architecture)"
apk_signer_certificate_sha256="$(manifest_string apk_signer_certificate_sha256)"
archive="$(manifest_string tar)"
data_host_path="$(manifest_string data_host_path)"
tls_host="$(manifest_string tls_host)"
lan_apk_download_origin="$(manifest_string lan_apk_download_origin)"
server_schema="$(manifest_string server_schema)"
android_version_code="$(manifest_string android_version_code)"
minimum_supported_version_code="$(manifest_string minimum_supported_version_code)"
rollback_source_version="$(manifest_string rollback_source_version)"
rollback_source_server_schema="$(manifest_string rollback_source_server_schema)"

if [[ ! "${version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$ ]]; then
  fail "MANIFEST.json version is outside the release-version contract"
fi
if [[ -n "${requested_version}" && "${requested_version}" != "${version}" ]]; then
  fail "requested version ${requested_version} does not match manifest version ${version}"
fi
if [[ "${platform}" != "linux-amd64" ]]; then
  fail "manifest platform must be linux-amd64"
fi
if [[ "${image_os}" != "linux" || "${image_architecture}" != "amd64" ]]; then
  fail "manifest image OS/architecture must be linux/amd64"
fi
if [[ ! "${apk_signer_certificate_sha256}" =~ ^[0-9a-f]{64}$ ]]; then
  fail "manifest APK signer certificate must be a lowercase SHA-256 digest"
fi
if [[ "${data_host_path}" != /* \
    || "${data_host_path}" == "/" \
    || ! "${data_host_path}" =~ ^/[A-Za-z0-9._/-]+$ \
    || "${data_host_path}" == *'//'* \
    || "${data_host_path}" == */./* \
    || "${data_host_path}" == */../* \
    || "${data_host_path}" == */. \
    || "${data_host_path}" == */.. ]]; then
  fail "manifest data_host_path must be a safe normalized absolute path"
fi
case "${data_host_path}" in
  /etc|/usr|/var|/home|/root|/tmp|/opt|/srv)
    fail "manifest data_host_path must not be a broad/system directory"
    ;;
esac
if [[ ! "${tls_host}" =~ ^[A-Za-z0-9.-]+$ || "${#tls_host}" -gt 253 ]]; then
  fail "manifest tls_host must be a plain IPv4 or DNS host"
fi
if [[ "${lan_apk_download_origin}" != "http://${tls_host}:8767" ]]; then
  fail "manifest LAN APK origin must be http://<tls_host>:8767"
fi
if [[ "${image}" != "lezi-sync:${version}" ]]; then
  fail "manifest image must be lezi-sync:<manifest version>"
fi
attested_cutover_source() {
  case "$1:$2" in
    0.3.12:11|0.3.13:12) return 0 ;;
    *) return 1 ;;
  esac
}
if [[ "${version}" == "0.4.1" ]] \
    && { [[ "${server_schema}" != "13" \
      || "${android_version_code}" != "22" \
      || "${minimum_supported_version_code}" != "21" \
      || "${rollback_source_version}" != "0.4.0" \
      || "${rollback_source_server_schema}" != "13" ]]; }; then
  fail "0.4.1 release identity must be 0.4.1/code22/floor21/schema13 with 0.4.0/13 rollback source"
fi
if [[ "${version}" == "0.4.0" ]] \
    && { [[ "${server_schema}" != "13" \
      || "${android_version_code}" != "21" \
      || "${minimum_supported_version_code}" != "21" ]] \
      || ! attested_cutover_source "${rollback_source_version}" "${rollback_source_server_schema}"; }; then
  fail "schema-cutover release identity must be 0.4.0/code21/floor21/schema13 with attested 0.3.12/11 or 0.3.13/12 rollback source"
fi
if [[ "${version}" == "0.3.12" && "${server_schema}" != "11" ]]; then
  fail "0.3.12 rollback packages must declare server schema 11"
fi
if [[ "${version}" == "0.3.13" && "${server_schema}" != "12" ]]; then
  fail "0.3.13 rollback packages must declare server schema 12"
fi
if [[ ! "${server_schema}" =~ ^[0-9]+$ \
    || ! "${android_version_code}" =~ ^[0-9]+$ \
    || ! "${minimum_supported_version_code}" =~ ^[0-9]+$ \
    || ! "${rollback_source_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ \
    || ! "${rollback_source_server_schema}" =~ ^[0-9]+$ ]]; then
  fail "manifest release/schema/Android rollback identity is malformed"
fi
if [[ "${archive}" != "lezi-sync-${version}-linux-amd64.tar" ]]; then
  fail "manifest tar must be the exact archive for its version and platform"
fi
if [[ ! "${image_id}" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  fail "manifest image_id must be a complete lowercase sha256 image id"
fi
if [[ ! -f "${archive}" || -L "${archive}" ]]; then
  fail "manifest-selected image archive is missing or is a symlink"
fi

expected_files=("${fixed_files[@]}" "${archive}")
expected_inventory="$(printf '%s\n' "${expected_files[@]}" | LC_ALL=C sort)"
actual_inventory="$(
  find . -mindepth 1 ! -type d -print \
    | sed 's|^\./||' \
    | LC_ALL=C sort
)"
if [[ "${actual_inventory}" != "${expected_inventory}" ]]; then
  fail "package contains missing, extra, symlinked, or special-file artifacts"
fi
expected_directories='app-update'
actual_directories="$(
  find . -mindepth 1 -type d -print \
    | sed 's|^\./||' \
    | LC_ALL=C sort
)"
if [[ "${actual_directories}" != "${expected_directories}" ]]; then
  fail "package contains missing or extra directories"
fi

checksum_files=()
while IFS= read -r line; do
  if [[ ! "${line}" =~ ^[0-9a-f]{64}[[:space:]][[:space:]]([^[:space:]]+)$ ]]; then
    fail "SHA256SUMS contains a malformed or unsafe entry"
  fi
  checksum_files+=("${BASH_REMATCH[1]}")
done <SHA256SUMS

expected_checksum_files=()
for file in "${expected_files[@]}"; do
  if [[ "${file}" != "SHA256SUMS" ]]; then
    expected_checksum_files+=("${file}")
  fi
done
expected_checksums="$(printf '%s\n' "${expected_checksum_files[@]}" | LC_ALL=C sort)"
actual_checksums="$(printf '%s\n' "${checksum_files[@]}" | LC_ALL=C sort)"
if [[ "${actual_checksums}" != "${expected_checksums}" ]]; then
  fail "SHA256SUMS must cover every and only the expected package artifact"
fi
if ! sha256sum -c SHA256SUMS >/dev/null; then
  fail "SHA256SUMS verification failed"
fi

rendered_compose="$(mktemp "${TMPDIR:-/tmp}/lezi-compose-validate.XXXXXX")"
cleanup_rendered_compose() {
  rm -f -- "${rendered_compose}"
}
trap cleanup_rendered_compose EXIT HUP INT TERM
sed \
  -e "s|__LEZI_SYNC_VERSION__|${version}|g" \
  -e "s|__LEZI_DATA_HOST_PATH__|${data_host_path}|g" \
  -e "s|__LEZI_LAN_APK_DOWNLOAD_ORIGIN__|${lan_apk_download_origin}|g" \
  docker-compose.nas.yml.tpl >"${rendered_compose}"
if ! cmp -s "${rendered_compose}" docker-compose.yml; then
  fail "docker-compose.yml does not exactly match the attested template and manifest inputs"
fi

printf 'NAS package validated: version=%s image=%s archive=%s\n' \
  "${version}" "${image}" "${archive}"
