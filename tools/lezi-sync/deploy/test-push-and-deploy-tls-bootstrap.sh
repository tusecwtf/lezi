#!/usr/bin/env bash
# Current push protocol, with an attested synthetic package and local transport
# adapters. Never opens SSH or Docker; production requires explicit operator configuration.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
work=$(mktemp -d "${TMPDIR:-/tmp}/lezi-push-tls.XXXXXX")
cleanup() {
  local status=$?
  if [[ "$status" != 0 ]]; then
    find "$work" -name '*.log' -type f -exec tail -n 35 {} \; >&2
  fi
  rm -rf -- "$work"
  exit "$status"
}
trap cleanup EXIT
mkdir -p "$work/repo/tools/lezi-sync" "$work/repo/config" "$work/bin"
cp -a "$SCRIPT_DIR" "$work/repo/tools/lezi-sync/deploy"
cp "$SCRIPT_DIR/../Cargo.toml" "$work/repo/tools/lezi-sync/Cargo.toml"
cp "$REPO_ROOT/config/android-release-compatibility.json" "$REPO_ROOT/config/local-data-contracts.json" "$REPO_ROOT/config/release-apk-signer-sha256.txt" "$work/repo/config/"
deploy="$work/repo/tools/lezi-sync/deploy"
# This test owns forwarding/lease/ordering. Encryption/export validation has a
# separate contract; its adapter models only unavailable-live vs valid backup.
cat >"$deploy/backup-nas-credentials.sh" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf 'backup\n' >>"$TEST_ACTIONS"
[[ "$LEZI_DEPLOY_LOCK_TOKEN" =~ ^[0-9a-f]{64}$ ]]
[[ "$TEST_MODE" != backup-failure ]] || exit 9
if [[ "$TEST_MODE" == fresh && ! -f "$TEST_CASE/deployed" ]]; then exit 3; fi
FAKE
cat >"$work/bin/docker" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  'image inspect lezi-sync:0.5.4') printf '[{"Id":"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}]\n' ;;
  'image inspect lezi-sync:0.5.4 --format {{.Os}}') printf 'linux\n' ;;
  'image inspect lezi-sync:0.5.4 --format {{.Architecture}}') printf 'amd64\n' ;;
  'save lezi-sync:0.5.4 -o '*) printf 'synthetic image archive\n' >"$4" ;;
  *) echo "unexpected Docker adapter call: $*" >&2; exit 64 ;;
esac
FAKE
cat >"$work/bin/apkanalyzer" <<'FAKE'
#!/usr/bin/env bash
cat <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionCode="34" android:versionName="0.5.4" package="com.lezi.babylog"><application>
<meta-data android:name="com.lezi.babylog.LOCAL_DATA_CONTRACT_VERSION" android:value="6" />
<meta-data android:name="com.lezi.babylog.MINIMUM_MIGRATABLE_LOCAL_DATA_CONTRACT_VERSION" android:value="1" />
</application></manifest>
XML
FAKE
cat >"$work/bin/apksigner" <<'FAKE'
#!/usr/bin/env bash
printf 'Signer #1 certificate SHA-256 digest: %s\n' "$TEST_SIGNER"
FAKE
cat >"$work/bin/scp" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf 'scp\n' >>"$TEST_ACTIONS"
args=("$@")
source="${args[${#args[@]}-2]}"
destination="${args[${#args[@]}-1]}"
[[ "$destination" == fixture@example.invalid:"$TEST_CASE/"* ]]
shopt -s dotglob nullglob
for entry in "${source%/.}/"*; do cp -a "$entry" "${destination#*:}"; done
FAKE
cat >"$work/bin/ssh" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
command="${!#}"
[[ "$*" == *'fixture@example.invalid'* ]]
printf '%s\n' "$command" >>"$TEST_CASE/ssh-argv"
case "$command" in
  *' ./remote-deploy.sh')
    # Do not run remote-deploy: its TLS safety behavior has dedicated fixtures.
    # Validate that this invocation owns the real isolated lease before success.
    token=$(printf '%s\n' "$command" | sed -nE 's/.*LEZI_DEPLOY_LOCK_TOKEN=([0-9a-f]{64}).*/\1/p')
    [[ -n "$token" && "$(cat "$LEZI_DATA_HOST_PATH/../config/.lezi-sync-credential-deploy.lock/owner-token")" == "$token" ]]
    if [[ "$command" == *'LEZI_BOOTSTRAP_SECRET_STDIN=1'* ]]; then
      cat >"$TEST_CASE/secret-stdin"
    else
      [[ "$command" != *'LEZI_ALLOW_TLS_BOOTSTRAP=1'* ]]
    fi
    printf 'deploy\n' >>"$TEST_ACTIONS"
    touch "$TEST_CASE/deployed"
    ;;
  *)
    # Every remote path is under the owned temporary root. Execute only local
    # lease, staging, exact-package validation, and promotion helpers.
    [[ "$command" == *"$TEST_CASE/"* ]]
    bash -c "$command"
    ;;
esac
FAKE
chmod +x "$work/bin/"* "$deploy/backup-nas-credentials.sh"
export PATH="$work/bin:$PATH" LEZI_APK_ANALYZER="$work/bin/apkanalyzer" LEZI_APK_SIGNER="$work/bin/apksigner"
export TEST_SIGNER="$(tr -d '\r\n' <"$REPO_ROOT/config/release-apk-signer-sha256.txt")"
export LEZI_SYNC_VERSION=0.5.4 LEZI_SKIP_PACKAGE=1 LEZI_TLS_HOST=localhost LEZI_LAN_APK_DOWNLOAD_ORIGIN=http://localhost:8767
export NAS_SSH=fixture@example.invalid NAS_SSH_PORT=10000
# Clear inherited maintenance flags: this fixture explicitly owns each case.
unset LEZI_ALLOW_TLS_BOOTSTRAP LEZI_FORWARD_BOOTSTRAP_SECRET LEZI_ALLOW_SECRET_RECOVERY LEZI_ALLOW_SECRET_RESEED LEZI_BOOTSTRAP_SECRET LEZI_DEPLOY_LOCK_TOKEN LEZI_SCHEMA12_COMPATIBLE_IMAGE_ID
prepare() {
  export TEST_MODE="$1" TEST_CASE="$work/$1" TEST_ACTIONS="$work/$1/actions"
  export LEZI_DATA_HOST_PATH="$TEST_CASE/data" NAS_REMOTE_DIR="$TEST_CASE/releases/lezi-sync-0.5.4-nas"
  export LEZI_NAS_PACKAGE_DIR="$TEST_CASE/lezi-sync-0.5.4-nas" LEZI_RELEASE_APK="$TEST_CASE/release.apk" LEZI_APP_UPDATE_JSON="$TEST_CASE/app-update.json"
  mkdir -p "$LEZI_DATA_HOST_PATH"
  printf 'synthetic release APK\n' >"$LEZI_RELEASE_APK"
  python3 - "$SCRIPT_DIR/app-update.json" "$LEZI_RELEASE_APK" "$LEZI_APP_UPDATE_JSON" <<'PY'
import hashlib,json,sys
metadata=json.load(open(sys.argv[1])); metadata['sha256']=hashlib.sha256(open(sys.argv[2],'rb').read()).hexdigest()
open(sys.argv[3],'w').write(json.dumps(metadata,indent=2)+'\n')
PY
  bash "$deploy/package-nas.sh" >"$TEST_CASE/package.log" 2>&1
}
prepare fresh
LEZI_ALLOW_TLS_BOOTSTRAP=1 LEZI_FORWARD_BOOTSTRAP_SECRET=1 LEZI_BOOTSTRAP_SECRET='synthetic-fresh-secret-only' \
  bash "$deploy/push-and-deploy.sh" >"$TEST_CASE/push.log" 2>&1
[[ "$(cat "$TEST_CASE/secret-stdin")" == synthetic-fresh-secret-only ]]
grep -q 'LEZI_ALLOW_TLS_BOOTSTRAP=1.*LEZI_DEPLOY_LOCK_TOKEN=.*LEZI_BOOTSTRAP_SECRET_STDIN=1 ./remote-deploy.sh' "$TEST_CASE/ssh-argv"
! grep -q 'synthetic-fresh-secret-only' "$TEST_CASE/ssh-argv" "$TEST_CASE/push.log"
[[ "$(cat "$TEST_ACTIONS")" == $'scp\nbackup\ndeploy\nbackup' ]]
[[ ! -e "$TEST_CASE/config/.lezi-sync-credential-deploy.lock" ]]
[[ ! -e "$TEST_CASE/config/.lezi-sync-credential-deploy.lock.app-update" ]]
prepare ordinary
LEZI_BOOTSTRAP_SECRET='synthetic-unused-local-secret' bash "$deploy/push-and-deploy.sh" >"$TEST_CASE/push.log" 2>&1
[[ ! -e "$TEST_CASE/secret-stdin" ]]
! grep -Eq 'LEZI_ALLOW_TLS_BOOTSTRAP=1|LEZI_BOOTSTRAP_SECRET_STDIN=1|synthetic-unused-local-secret|init-tls.sh' "$TEST_CASE/ssh-argv"
[[ "$(cat "$TEST_ACTIONS")" == $'scp\nbackup\ndeploy' ]]
prepare backup-failure
if bash "$deploy/push-and-deploy.sh" >"$TEST_CASE/push.log" 2>&1; then exit 1; fi
[[ ! -e "$TEST_CASE/deployed" ]]
grep -q 'pre-replace encrypted credential backup failed' "$TEST_CASE/push.log"
prepare invalid
for mode in missing-forward invalid-flag; do
  flag=1; [[ "$mode" != invalid-flag ]] || flag=2
  if LEZI_ALLOW_TLS_BOOTSTRAP="$flag" bash "$deploy/push-and-deploy.sh" >"$TEST_CASE/$mode.log" 2>&1; then exit 1; fi
  [[ ! -e "$TEST_CASE/ssh-argv" ]]
done
prepare competing-lease
bash "$deploy/credential-deploy-lock.sh" acquire "$TEST_CASE/config/.lezi-sync-credential-deploy.lock" "$(printf 'f%.0s' {1..64})"
if bash "$deploy/push-and-deploy.sh" >"$TEST_CASE/push.log" 2>&1; then exit 1; fi
[[ ! -e "$TEST_ACTIONS" && ! -e "$TEST_CASE/deployed" ]]
printf 'push TLS protocol: fresh/stdin, ordinary reuse, backup failure, two invalid flags, competing lease passed\n'
