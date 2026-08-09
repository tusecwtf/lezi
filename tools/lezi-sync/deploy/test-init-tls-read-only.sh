#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/lezi-tls-inspect-test.XXXXXX")"
cleanup() {
  chmod 600 "${test_root}/unreadable/tls/server.crt" 2>/dev/null || true
  rm -rf -- "${test_root}"
}
trap cleanup EXIT HUP INT TERM

snapshot_tree() {
  local root="$1"
  if [[ ! -e "${root}" && ! -L "${root}" ]]; then
    printf 'ROOT_ABSENT\n'
    return
  fi
  find -P "${root}" -printf '%P|%y|%i|%m|%T@\n' \
    | LC_ALL=C sort
  while IFS= read -r file; do
    if [[ -r "${file}" ]]; then
      printf 'SHA256|%s|%s\n' \
        "${file#"${root}"/}" "$(sha256sum "${file}" | awk '{print $1}')"
    else
      printf 'SHA256|%s|UNREADABLE\n' "${file#"${root}"/}"
    fi
  done < <(find -P "${root}" -type f -print | LC_ALL=C sort)
}

assert_unchanged() {
  local label="$1" before="$2" root="$3" after
  after="$(snapshot_tree "${root}")"
  if [[ "${after}" != "${before}" ]]; then
    echo "error: TLS inspection changed ${label} fixture" >&2
    diff -u <(printf '%s\n' "${before}") <(printf '%s\n' "${after}") >&2 || true
    exit 1
  fi
}

inspect_host() {
  LEZI_TLS_INSPECT_ONLY=1 LEZI_TLS_USE_HOST_OPENSSL=1 \
    "${SCRIPT_DIR}/init-tls.sh" "$1" ignored localhost
}

absent_root="${test_root}/absent"
mkdir -p "${absent_root}"
absent_before="$(snapshot_tree "${absent_root}")"
test "$(inspect_host "${absent_root}")" = "absent"
assert_unchanged absent "${absent_before}" "${absent_root}"

complete_root="${test_root}/complete"
mkdir -p "${complete_root}"
LEZI_ALLOW_TLS_BOOTSTRAP=1 LEZI_TLS_USE_HOST_OPENSSL=1 \
  "${SCRIPT_DIR}/init-tls.sh" "${complete_root}" ignored localhost >/dev/null
complete_before="$(snapshot_tree "${complete_root}")"
test "$(inspect_host "${complete_root}")" = "present"
assert_unchanged complete "${complete_before}" "${complete_root}"

partial_root="${test_root}/partial"
cp -a "${complete_root}" "${partial_root}"
rm -- "${partial_root}/tls/server.crt"
partial_before="$(snapshot_tree "${partial_root}")"
if inspect_host "${partial_root}" >/dev/null 2>&1; then
  echo "error: read-only inspection accepted a partial TLS identity" >&2
  exit 1
fi
assert_unchanged partial "${partial_before}" "${partial_root}"

unsafe_root="${test_root}/unsafe"
mkdir -p "${unsafe_root}/tls"
ln -s "${complete_root}/tls/server.crt" "${unsafe_root}/tls/server.crt"
cp "${complete_root}/tls/server.key" "${unsafe_root}/tls/server.key"
unsafe_before="$(snapshot_tree "${unsafe_root}")"
if inspect_host "${unsafe_root}" >/dev/null 2>&1; then
  echo "error: read-only inspection accepted a symlink TLS path" >&2
  exit 1
fi
assert_unchanged unsafe-symlink "${unsafe_before}" "${unsafe_root}"

unreadable_root="${test_root}/unreadable"
cp -a "${complete_root}" "${unreadable_root}"
unreadable_certificate_sha="$(sha256sum "${unreadable_root}/tls/server.crt" | awk '{print $1}')"
chmod 000 "${unreadable_root}/tls/server.crt"
unreadable_before="$(snapshot_tree "${unreadable_root}")"
if inspect_host "${unreadable_root}" >/dev/null 2>&1; then
  echo "error: read-only inspection accepted an unreadable TLS identity" >&2
  exit 1
fi
assert_unchanged read-failure "${unreadable_before}" "${unreadable_root}"
chmod 600 "${unreadable_root}/tls/server.crt"
test "$(sha256sum "${unreadable_root}/tls/server.crt" | awk '{print $1}')" \
  = "${unreadable_certificate_sha}"

container_root="${test_root}/mode-700-data"
fake_bin="${test_root}/bin"
docker_log="${test_root}/docker.log"
mkdir -p "${container_root}" "${fake_bin}"
chmod 700 "${container_root}"
cat >"${fake_bin}/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${LEZI_TEST_DOCKER_LOG:?}"
if [[ "${1:-}" == "image" && "${2:-}" == "inspect" ]]; then
  exit 0
fi
if [[ "${1:-}" == "run" ]]; then
  if [[ " $* " != *" --mount "* || " $* " != *"readonly"* ]]; then
    echo "error: TLS inspection container bind was not read-only" >&2
    exit 73
  fi
  if [[ " $* " != *" --user 10001:10001 "* \
      || " $* " != *" --entrypoint /bin/sh "* \
      || " $* " != *"LEZI_TLS_IDENTITY_CLASSIFIER_V1"* \
      || " $* " != *" /data /data/tls /data/tls/server.crt /data/tls/server.key "* ]]; then
    echo "error: container adapter did not invoke the shared uid-10001 classifier" >&2
    exit 76
  fi
  if [[ "${LEZI_TEST_CONTAINER_RESULT:-read-failure}" == "absent" ]]; then
    printf 'absent\n'
    exit 0
  fi
  echo "error: TLS identity cannot be read as uid 10001" >&2
  exit 74
fi
exit 75
EOF
chmod +x "${fake_bin}/docker"
container_before="$(snapshot_tree "${container_root}")"
test "$(PATH="${fake_bin}:${PATH}" LEZI_TEST_DOCKER_LOG="${docker_log}" \
  LEZI_TEST_CONTAINER_RESULT=absent LEZI_TLS_INSPECT_ONLY=1 \
  "${SCRIPT_DIR}/init-tls.sh" "${container_root}" fake-image localhost)" = "absent"
assert_unchanged container-absent "${container_before}" "${container_root}"
if PATH="${fake_bin}:${PATH}" LEZI_TEST_DOCKER_LOG="${docker_log}" \
    LEZI_TLS_INSPECT_ONLY=1 \
    "${SCRIPT_DIR}/init-tls.sh" "${container_root}" fake-image localhost \
    >/dev/null 2>&1; then
  echo "error: container inspection accepted a uid-10001 read failure" >&2
  exit 1
fi
assert_unchanged mode-700-read-failure "${container_before}" "${container_root}"
if ! grep -q -- '--mount' "${docker_log}" || ! grep -q -- 'readonly' "${docker_log}"; then
  echo "error: container inspection did not request a read-only bind mount" >&2
  cat "${docker_log}" >&2
  exit 1
fi

# Optional local proof through a real existing lezi-sync image. CI exercises
# the same classifier through the host adapter and never pulls/builds an image;
# release validation may opt in with an already inspected local image.
if [[ -n "${LEZI_TLS_TEST_IMAGE:-}" ]]; then
  real_root="${test_root}/real-container"
  real_complete="${real_root}/complete"
  real_partial="${real_root}/partial"
  real_unsafe="${real_root}/unsafe"
  real_mode_700="${real_root}/mode-700-read-failure"
  mkdir -p "${real_root}"
  cp -a "${complete_root}" "${real_complete}"
  chmod 755 "${real_complete}" "${real_complete}/tls"
  chmod 644 "${real_complete}/tls/server.crt" "${real_complete}/tls/server.key"
  cp -a "${real_complete}" "${real_partial}"
  rm -- "${real_partial}/tls/server.crt"
  cp -a "${real_complete}" "${real_unsafe}"
  rm -- "${real_unsafe}/tls/server.crt"
  ln -s "${real_complete}/tls/server.crt" "${real_unsafe}/tls/server.crt"
  cp -a "${real_complete}" "${real_mode_700}"
  chmod 700 "${real_mode_700}"

  real_before="$(snapshot_tree "${real_complete}")"
  test "$(LEZI_TLS_INSPECT_ONLY=1 \
    "${SCRIPT_DIR}/init-tls.sh" \
    "${real_complete}" "${LEZI_TLS_TEST_IMAGE}" localhost)" = "present"
  assert_unchanged real-container-complete "${real_before}" "${real_complete}"

  for fixture in "${real_partial}" "${real_unsafe}" "${real_mode_700}"; do
    real_before="$(snapshot_tree "${fixture}")"
    if LEZI_TLS_INSPECT_ONLY=1 \
        "${SCRIPT_DIR}/init-tls.sh" \
        "${fixture}" "${LEZI_TLS_TEST_IMAGE}" localhost >/dev/null 2>&1; then
      echo "error: real uid-10001 inspection accepted ${fixture##*/}" >&2
      exit 1
    fi
    assert_unchanged "real-container-${fixture##*/}" "${real_before}" "${fixture}"
  done
fi

echo "read-only TLS inspection smoke passed"
