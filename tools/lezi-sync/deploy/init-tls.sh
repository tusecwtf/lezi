#!/usr/bin/env bash
# Creates one persistent self-signed TLS identity under the NAS data bind.
# Existing complete identities are validated and reused; partial identities fail closed.
set -euo pipefail

data_root="${1:?usage: init-tls.sh DATA_ROOT IMAGE TLS_HOST}"
image="${2:?usage: init-tls.sh DATA_ROOT IMAGE TLS_HOST}"
tls_host="${3:?usage: init-tls.sh DATA_ROOT IMAGE TLS_HOST}"

if [[ ! "${tls_host}" =~ ^[A-Za-z0-9.:-]+$ ]] || [[ "${#tls_host}" -gt 253 ]]; then
  echo "error: TLS host must be a plain DNS name or IP address" >&2
  exit 1
fi

tls_directory="${data_root}/tls"
certificate="${tls_directory}/server.crt"
private_key="${tls_directory}/server.key"
host_openssl="${LEZI_TLS_USE_HOST_OPENSSL:-0}"
allow_tls_bootstrap="${LEZI_ALLOW_TLS_BOOTSTRAP:-0}"
inspect_only="${LEZI_TLS_INSPECT_ONLY:-0}"
write_enabled=0

if [[ "${allow_tls_bootstrap}" != "0" && "${allow_tls_bootstrap}" != "1" ]]; then
  echo "error: LEZI_ALLOW_TLS_BOOTSTRAP must be 0 or 1" >&2
  exit 1
fi
if [[ "${inspect_only}" != "0" && "${inspect_only}" != "1" ]]; then
  echo "error: LEZI_TLS_INSPECT_ONLY must be 0 or 1" >&2
  exit 1
fi
if [[ "${inspect_only}" == "1" && "${allow_tls_bootstrap}" == "1" ]]; then
  echo "error: TLS inspection and TLS bootstrap are separate capabilities" >&2
  exit 1
fi

if [[ "${host_openssl}" == "1" ]]; then
  command -v openssl >/dev/null
  certificate_arg="${certificate}"
  private_key_arg="${private_key}"
else
  docker image inspect "${image}" >/dev/null 2>&1 || {
    echo "error: TLS initializer image ${image} is unavailable" >&2
    exit 1
  }
  certificate_arg="/data/tls/server.crt"
  private_key_arg="/data/tls/server.key"
fi

container_mount() {
  local mount="type=bind,source=${data_root},target=/data"
  if [[ "${write_enabled}" != "1" ]]; then
    mount="${mount},readonly"
  fi
  printf '%s\n' "${mount}"
}

run_openssl() {
  if [[ "${host_openssl}" == "1" ]]; then
    openssl "$@"
  else
    docker run --rm -i \
      --user 10001:10001 \
      --mount "$(container_mount)" \
      --entrypoint /usr/bin/openssl \
      "${image}" "$@"
  fi
}

run_file_tool() {
  local tool="$1"
  shift
  if [[ "${host_openssl}" == "1" ]]; then
    "${tool}" "$@"
  else
    docker run --rm \
      --user 10001:10001 \
      --mount "$(container_mount)" \
      --entrypoint "/bin/${tool}" \
      "${image}" "$@"
  fi
}

# One classifier program is the production seam for both host and uid-10001
# adapters. Tests exercise these exact branches through the host adapter; the
# container adapter only supplies the same program with /data paths.
identity_classifier='# LEZI_TLS_IDENTITY_CLASSIFIER_V1
data_root="$1"
tls_directory="$2"
certificate="$3"
private_key="$4"
if [ -L "${data_root}" ] || [ ! -d "${data_root}" ]; then
  echo "error: TLS data root must be an existing non-symlink directory" >&2
  exit 1
fi
if [ ! -r "${data_root}" ] || [ ! -x "${data_root}" ]; then
  echo "error: TLS data root is not inspectable" >&2
  exit 1
fi
if [ -L "${tls_directory}" ]; then
  echo "error: TLS directory must be a regular non-symlink directory" >&2
  exit 1
fi
if [ ! -e "${tls_directory}" ]; then
  printf "absent\n"
  exit 0
fi
if [ ! -d "${tls_directory}" ]; then
  echo "error: TLS directory must be a regular non-symlink directory" >&2
  exit 1
fi
if [ ! -r "${tls_directory}" ] || [ ! -x "${tls_directory}" ]; then
  echo "error: TLS directory is not inspectable" >&2
  exit 1
fi
certificate_state=absent
private_key_state=absent
if [ -L "${certificate}" ] || { [ -e "${certificate}" ] && [ ! -f "${certificate}" ]; }; then
  echo "error: TLS identity paths must be regular non-symlink files" >&2
  exit 1
elif [ -f "${certificate}" ]; then
  if [ ! -r "${certificate}" ]; then
    echo "error: TLS certificate is not readable" >&2
    exit 1
  fi
  certificate_state=present
fi
if [ -L "${private_key}" ] || { [ -e "${private_key}" ] && [ ! -f "${private_key}" ]; }; then
  echo "error: TLS identity paths must be regular non-symlink files" >&2
  exit 1
elif [ -f "${private_key}" ]; then
  if [ ! -r "${private_key}" ]; then
    echo "error: TLS private key is not readable" >&2
    exit 1
  fi
  private_key_state=present
fi
if [ "${certificate_state}" != "${private_key_state}" ]; then
  echo "error: partial TLS identity found; refusing automatic replacement" >&2
  exit 1
fi
printf "%s\n" "${certificate_state}"'

inspect_identity() {
  if [[ ! -e "${data_root}" && ! -L "${data_root}" \
      && "${host_openssl}" == "1" \
      && "${allow_tls_bootstrap}" == "1" \
      && "${inspect_only}" == "0" ]]; then
    printf 'absent\n'
    return
  fi
  if [[ "${host_openssl}" == "1" ]]; then
    /bin/sh -ec "${identity_classifier}" sh \
      "${data_root}" "${tls_directory}" "${certificate}" "${private_key}"
  else
    docker run --rm \
      --user 10001:10001 \
      --mount "$(container_mount)" \
      --entrypoint /bin/sh \
      "${image}" \
      -ec "${identity_classifier}" sh \
      /data /data/tls /data/tls/server.crt /data/tls/server.key
  fi
}

validate_identity() {
  run_openssl x509 -in "${certificate_arg}" -checkend 86400 -noout >/dev/null
  run_openssl pkey -in "${private_key_arg}" -check -noout >/dev/null
  local certificate_public_key
  local private_public_key
  certificate_public_key="$(run_openssl x509 -in "${certificate_arg}" -pubkey -noout)"
  private_public_key="$(run_openssl pkey -in "${private_key_arg}" -pubout)"
  if [[ "${certificate_public_key}" != "${private_public_key}" ]]; then
    echo "error: TLS certificate and private key do not match" >&2
    exit 1
  fi
}

print_spki_fingerprint() {
  local certificate_public_key
  local digest
  local fingerprint
  certificate_public_key="$(run_openssl x509 -in "${certificate_arg}" -pubkey -noout)"
  digest="$(
    printf '%s\n' "${certificate_public_key}" \
      | run_openssl pkey -pubin -outform DER \
      | run_openssl dgst -sha256 \
      | sed -E 's/^.*= //; s/[[:space:]]//g' \
      | tr '[:lower:]' '[:upper:]'
  )"
  fingerprint="$(printf '%s' "${digest}" | sed -E 's/(..)/\1:/g; s/:$//')"
  echo "==> TLS SPKI SHA-256: ${fingerprint}"
}

identity_state="$(inspect_identity)"

if [[ "${inspect_only}" == "1" ]]; then
  printf '%s\n' "${identity_state}"
  exit 0
fi

if [[ "${identity_state}" == "present" ]]; then
  validate_identity
  echo "==> reusing persistent TLS identity"
  print_spki_fingerprint
  exit 0
fi

if [[ "${allow_tls_bootstrap}" != "1" ]]; then
  echo "error: TLS identity is absent; ordinary CD refuses to generate a replacement" >&2
  echo "  set LEZI_ALLOW_TLS_BOOTSTRAP=1 only for a verified fresh data root" >&2
  exit 1
fi

# Bootstrap starts only after read-only inspection proved both files absent and
# the operator explicitly authorized a separately verified fresh data root.
write_enabled=1
if [[ "${host_openssl}" == "1" ]]; then
  mkdir -p "${tls_directory}"
else
  docker run --rm \
    --user 10001:10001 \
    --mount "$(container_mount)" \
    --entrypoint /bin/mkdir \
    "${image}" -p /data/tls
fi

if [[ "${tls_host}" == *:* ]] || [[ "${tls_host}" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  subject_alt_name="DNS:localhost,IP:127.0.0.1,IP:${tls_host}"
else
  subject_alt_name="DNS:localhost,IP:127.0.0.1,DNS:${tls_host}"
fi
temporary_certificate="${certificate_arg}.tmp"
temporary_private_key="${private_key_arg}.tmp"

cleanup_temporary_identity() {
  run_file_tool rm -f "${temporary_certificate}" "${temporary_private_key}" >/dev/null 2>&1 || true
}
trap cleanup_temporary_identity EXIT HUP INT TERM

run_openssl req \
  -x509 \
  -newkey rsa:3072 \
  -sha256 \
  -days 3650 \
  -nodes \
  -subj "/CN=${tls_host}" \
  -addext "subjectAltName=${subject_alt_name}" \
  -keyout "${temporary_private_key}" \
  -out "${temporary_certificate}" \
  >/dev/null 2>&1
run_file_tool chmod 600 "${temporary_private_key}"
run_file_tool chmod 644 "${temporary_certificate}"
run_file_tool mv "${temporary_private_key}" "${private_key_arg}"
run_file_tool mv "${temporary_certificate}" "${certificate_arg}"
trap - EXIT HUP INT TERM

validate_identity
echo "==> created persistent self-signed TLS identity"
print_spki_fingerprint
