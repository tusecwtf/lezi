#!/usr/bin/env bash
# Local fail-closed privacy regression: omitted operator inputs must fail before
# invoking any transport or container tool. All supplied targets are synthetic.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d "${TMPDIR:-/tmp}/lezi-operator-config-test.XXXXXX")"
zfs_fixture=''
trap 'rm -rf -- "$work"; if [[ -n "$zfs_fixture" ]]; then rmdir -- "$zfs_fixture"; fi' EXIT
mkdir "$work/bin"
for tool in ssh scp rsync curl docker age; do
  cat >"$work/bin/$tool" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' 'unexpected external tool invocation' >>"${LEZI_TEST_TRANSPORT_MARKER:?}"
exit 98
STUB
  chmod +x "$work/bin/$tool"
done
checked=0
check_missing() {
  local script="$1" missing="$2" key
  local -a configuration=(
    "PATH=$work/bin:$PATH" "HOME=$work" "LEZI_TEST_TRANSPORT_MARKER=$work/transport"
    'NAS_SSH=fixture@example.invalid' 'NAS_SSH_PORT=10000'
    "LEZI_DATA_HOST_PATH=$work/nas-data" 'LEZI_TLS_HOST=192.168.77.10'
    'LEZI_LAN_HOST=192.168.77.10' "LEZI_SCHEMA_CUTOVER_STATE_DIR=$work/state"
    'LEZI_SYNC_VERSION=0.5.5'
  )
  local -a filtered=()
  for key in "${configuration[@]}"; do
    [[ "$key" == "$missing="* ]] || filtered+=("$key")
  done
  if env -i "${filtered[@]}" bash "$HERE/$script" >"$work/out" 2>"$work/err"; then
    echo "FAIL: $script accepted missing $missing" >&2; exit 1
  fi
  grep -Fq "$missing" "$work/err" || { echo "FAIL: $script did not explain missing $missing" >&2; exit 1; }
  [[ ! -e "$work/transport" ]] || { echo 'FAIL: external tool ran before input validation' >&2; exit 1; }
  checked=$((checked + 1))
}
for script in backup-nas-credentials.sh backup-pre-tls-cutover-state.sh copy-back-nas-data.sh copy-out-nas-data.sh live-cutover-probe.sh push-and-deploy.sh schema-cutover-steps.sh; do
  check_missing "$script" NAS_SSH
  check_missing "$script" NAS_SSH_PORT
done
for script in copy-back-nas-data.sh copy-out-nas-data.sh package-nas.sh push-and-deploy.sh schema-cutover-steps.sh; do
  check_missing "$script" LEZI_DATA_HOST_PATH
done
for script in package-nas.sh push-and-deploy.sh; do check_missing "$script" LEZI_TLS_HOST; done
for script in live-cutover-probe.sh schema-cutover-steps.sh; do check_missing "$script" LEZI_LAN_HOST; done
# A generalized mount ban must still refuse every formerly protected mount
# ancestry. This brand-new empty synthetic directory is not a real NAS bind.
zfs_fixture="$(mktemp -d /tmp/zfs-lezi-privacy-fixture.XXXXXX)"
if env -i "PATH=$work/bin:$PATH" "HOME=$work" \
    "LEZI_TEST_TRANSPORT_MARKER=$work/transport" \
    "LEZI_SCHEMA_CUTOVER_REHEARSAL_ROOT=$work" \
    "LEZI_REHEARSAL_SOURCE_ROOT_11=$zfs_fixture" \
    bash "$HERE/schema-cutover-rehearsal-steps.sh" probe "$work/case" 11 \
    >"$work/zfs.out" 2>"$work/zfs.err"; then
  echo 'FAIL: rehearsal accepted NAS storage mount ancestry' >&2; exit 1
fi
grep -Fq 'NAS storage mount ancestry is forbidden' "$work/zfs.err"
[[ ! -e "$work/transport" ]]
rmdir -- "$zfs_fixture"
zfs_fixture=''
# Remote deploy gets the identity from the validated package, with no host guess.
grep -Fq 'TLS_HOST="${LEZI_TLS_HOST:-}"' "$HERE/remote-deploy.sh"
grep -Fq 'manifest_tls_host="$(manifest_string tls_host)"' "$HERE/remote-deploy.sh"
grep -Fq 'ZDOCKER_COMPOSE="${ZDOCKER_COMPOSE:-}"' "$HERE/remote-deploy.sh"
grep -Fq 'remote_env_prefix+="ZDOCKER_COMPOSE=${compose_path_q} "' "$HERE/push-and-deploy.sh"
printf '%s missing-input cases rejected before transport; mount ancestry rejected; package identity and optional Compose wiring checked\n' "$checked"
